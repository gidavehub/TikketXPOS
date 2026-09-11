package com.tikketx.pos.data

import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.storage.FirebaseStorage
import com.tikketx.pos.data.local.LocalStore
import com.tikketx.pos.data.net.Outbox
import com.tikketx.pos.data.net.SwipeApi
import org.json.JSONArray
import org.json.JSONObject
import com.tikketx.pos.data.model.CartItem
import com.tikketx.pos.data.model.CompanyProfile
import com.tikketx.pos.data.model.CompletedSale
import com.tikketx.pos.data.model.DayStats
import com.tikketx.pos.data.model.PaymentMethod
import com.tikketx.pos.data.model.PosSale
import com.tikketx.pos.data.model.PosSaleItem
import com.tikketx.pos.data.model.Product
import com.tikketx.pos.data.model.ScanRequest
import com.tikketx.pos.data.model.StockChange
import com.tikketx.pos.data.model.StockMovement
import com.tikketx.pos.data.model.StockMovementType
import com.tikketx.pos.data.model.TaxRate
import com.tikketx.pos.data.model.Terminal
import com.tikketx.pos.data.model.TerminalPermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * All communication with the Swipe platform.
 *
 * There is exactly one copy of the data and both ends read it: products, stock
 * movements and sales live in Neon Postgres, reached through the same
 * `/api/data` route the dashboard uses, with the same field names and the same
 * semantics — so nothing has to be reconciled afterwards.
 *
 * Auth is unchanged. A terminal still signs in as a real Firebase Auth user
 * (`<username>@<company-domain>`, created from Swipe → POS Devices); only the
 * database moved.
 *
 * ── What replaced the Firestore SDK, and why it had to ──────────────────────
 *
 * The SDK was not just a transport. It gave this app a persistent read cache
 * and a durable write queue, and both were load-bearing: a shop in Kombo does
 * not stop trading because the network did. HTTP has neither, so they are
 * rebuilt explicitly — [LocalStore] mirrors rows for reading, [Outbox] queues
 * writes until they land.
 *
 * Reads go to the server and fall back to the mirror. Writes go to the outbox
 * first and are acknowledged immediately, which is the same bargain the old
 * code struck with its 4-second write window, for the same reason: "with no
 * signal, ringing up a sale sat on the spinner until the cashier gave up".
 *
 * Stock is the one place the shape changed. It used to be a
 * `FieldValue.increment()` on the product; it is now an appended
 * `stock_movements` row, and a Postgres trigger moves `current_stock`. That is
 * what makes a queued sale safe to retry — the trigger fires AFTER INSERT, and
 * a replayed row takes the UPDATE path, so stock cannot be decremented twice.
 *
 * Tables used, all shared with the dashboard:
 *   products, stock_movements, pos_sales, pos_sale_items, customer_credits,
 *   tax_rates, companies, pos_terminals, pos_live, pos_scan_requests
 */
object SwipeBackend {

    const val APP_NAME = "swipe"

    private lateinit var auth: FirebaseAuth
    private lateinit var storage: FirebaseStorage
    private lateinit var local: LocalStore

    /**
     * Where the polling watchers live.
     *
     * Firestore and RTDB pushed; HTTP does not, so anything that used to be a
     * snapshot listener is a loop here. Each returns a [ScanRequestSubscription]
     * so call sites cancel them exactly as they did before.
     */
    private val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * @param context needed for the local database. The offline mirror and the
     * write queue are what used to be Firestore's persistence, so they have to
     * be up before anything reads or writes.
     */
    fun init(context: android.content.Context) {
        val app = FirebaseApp.getInstance(APP_NAME)
        auth = FirebaseAuth.getInstance(app)
        storage = FirebaseStorage.getInstance(app)

        local = LocalStore(context.applicationContext)
        Outbox.init(local)
    }

    // ---------------------------------------------------------------------
    // Reads: server first, mirror second
    // ---------------------------------------------------------------------

    /**
     * Fetch a list and mirror it, falling back to the mirror when the request
     * fails.
     *
     * This is the behaviour Firestore's persistent cache gave: the catalogue a
     * till needs to sell stays readable through an outage. The fallback is
     * silent on purpose — the cashier is told the till is offline by the
     * banner, not by an empty product list.
     */
    private fun fetchList(table: String, opts: JSONObject, mirror: Boolean = true): List<JSONObject> =
        try {
            val result = SwipeApi.call(SwipeApi.opGetList(table, opts)) as? JSONArray ?: JSONArray()
            val rows = (0 until result.length()).mapNotNull { result.optJSONObject(it) }
            if (mirror) local.putAll(table, rows)
            rows
        } catch (err: Exception) {
            if (mirror) local.getAll(table) else emptyList()
        }

    private fun fetchOne(table: String, opts: JSONObject): JSONObject? =
        try {
            SwipeApi.call(SwipeApi.opGetOne(table, opts)) as? JSONObject
        } catch (err: Exception) {
            null
        }

    private fun fetchById(table: String, id: String): JSONObject? =
        try {
            (SwipeApi.call(SwipeApi.opGetById(table, id)) as? JSONObject)
                ?.also { local.put(table, it) }
        } catch (err: Exception) {
            local.get(table, id)
        }

    // ---------------------------------------------------------------------
    // Writes: queued, then acknowledged
    // ---------------------------------------------------------------------

    /**
     * Queue a write and update the mirror straight away.
     *
     * The optimistic mirror write is what lets the screen show the sale the
     * instant it is rung up, whether or not the server has heard about it yet.
     */
    private fun queueWrite(op: JSONObject, label: String?, table: String? = null, row: JSONObject? = null) {
        if (table != null && row != null) local.put(table, row)
        Outbox.enqueue(op, label)
    }

    /** JSON object from a vararg of pairs, dropping nulls the way the app expects. */
    private fun json(vararg pairs: Pair<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in pairs) if (v != null) o.put(k, v)
        return o
    }

    // ---------------------------------------------------------------------
    // Offline writes
    // ---------------------------------------------------------------------

    /**
     * Where a write that failed after the cashier walked away gets reported.
     *
     * Queued writes are acknowledged locally and pushed in the background, so
     * nothing is watching when the server finally answers. Set this —
     * MainActivity does — and a rejection still reaches a human.
     */
    @Volatile
    var onDeferredWriteFailure: ((String) -> Unit)? = null

    /**
     * Queue a write and return. The counter does not stand still for it.
     *
     * This is the same bargain the Firestore version struck, for the same
     * reason, and it is worth restating because it is the whole design: a write
     * is durable the moment it is recorded locally. Whether the *server* has it
     * yet is a different question, and not one a cashier should wait on.
     *
     * The old code learned this the hard way — [completeSale] awaited one
     * acknowledgement after another, sale then each line then each stock
     * movement, so a one-line sale on a dead link burned four four-second
     * timeouts back to back before anything printed, to reach a state it had
     * already been in since the first millisecond.
     */
    private fun fireAndForget(op: JSONObject, what: String) {
        Outbox.enqueue(op, what)
    }


    /** True when the device currently has a live connection to the backend. */
    @Volatile
    var isOnline: Boolean = true
        private set

    /**
     * Tracks connectivity so the UI can say so.
     *
     * RTDB publishes this at `.info/connected`, which is a real backend
     * connection rather than the phone merely having a network interface — the
     * distinction that matters when the shop's wifi is up but its uplink is not.
     */
    fun observeConnection(onChange: (Boolean) -> Unit): ScanRequestSubscription {
        // RTDB published this at `.info/connected`, which was a real backend
        // connection rather than the phone merely holding a network interface.
        // Polling the data route keeps that distinction — it answers only if
        // the server does, so a shop whose wifi is up but whose uplink is not
        // still reads as offline.
        val job = pollScope.launch {
            while (isActive) {
                val connected = withContext(Dispatchers.IO) { SwipeApi.ping() }
                if (connected != isOnline) {
                    isOnline = connected
                    withContext(Dispatchers.Main) { onChange(connected) }
                    // Coming back up is the moment to flush queued sales.
                    if (connected) Outbox.drain()
                }
                delay(if (isOnline) 30_000 else 5_000)
            }
        }
        return ScanRequestSubscription(job)
    }

    // ---------------------------------------------------------------------
    // Time helpers — the platform stores ISO-8601 UTC strings.
    // ---------------------------------------------------------------------

    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun nowIso(): String = isoFmt.format(Date())

    /** Milliseconds since the given ISO timestamp, or null if unparsable. */
    fun isoAgeMillis(iso: String?): Long? = try {
        iso?.let { System.currentTimeMillis() - isoFmt.parse(it)!!.time }
    } catch (_: Exception) {
        null
    }

    fun parseIso(iso: String?): Date? = try {
        iso?.let { isoFmt.parse(it) }
    } catch (_: Exception) {
        null
    }

    private fun startOfTodayIso(): String {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return isoFmt.format(cal.time)
    }

    private fun rand4(): String =
        UUID.randomUUID().toString().replace("-", "").take(4).uppercase()

    /** Sale number matching the web: POS-{YYMMDD}-{RAND4}. */
    fun generateSaleNumber(): String {
        val stamp = SimpleDateFormat("yyMMdd", Locale.US).format(Date())
        return "POS-$stamp-${rand4()}"
    }

    // ---------------------------------------------------------------------
    // Errors
    // ---------------------------------------------------------------------

    /**
     * Turns a rules rejection into something a cashier can act on.
     *
     * A denied query returns no rows, which on screen is indistinguishable from
     * a company that genuinely has no stock. Every listener and write in this
     * file routes its failures through here so "the device is not linked to
     * this business" never again looks like "you haven't added anything yet".
     */
    fun describeError(e: Exception): String {
        val denied = e is SwipeApi.Rejected && (e.status == 401 || e.status == 403)
        return if (denied) {
            "This terminal isn't linked to the business yet, so it can't see the " +
                "shared inventory. On Swipe, open POS Devices — that repairs the link — " +
                "then sign out and back in here."
        } else {
            e.message ?: "Something went wrong. Check the connection and try again."
        }
    }

    // ---------------------------------------------------------------------
    // Auth / terminal login
    // ---------------------------------------------------------------------

    val isSignedIn: Boolean get() = auth.currentUser != null && SessionManager.terminal != null

    /**
     * Signs in with the terminal's address and password, then loads its
     * terminal record from RTDB.
     *
     * The address is the whole identity — `<username>@<company-domain>` — so
     * sign-in takes two fields, not three. Terminals provisioned before domains
     * existed keep working: their old `…pos.tikketx.app` address is still a
     * valid Firebase account until the dashboard migrates it.
     */
    suspend fun login(email: String, password: String): Terminal {
        val address = email.trim().lowercase()
        val uid = try {
            auth.signInWithEmailAndPassword(address, password).await().user?.uid
                ?: throw IllegalStateException("Sign-in failed. Try again.")
        } catch (e: FirebaseAuthInvalidUserException) {
            throw IllegalStateException("No terminal is registered at $address.")
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            throw IllegalStateException("Wrong address or password — check with your manager.")
        }

        val terminal = findTerminalByUid(uid)
        if (terminal != null) {
            if (!terminal.active) {
                auth.signOut()
                throw IllegalStateException("This terminal is deactivated. Ask your manager to re-activate it.")
            }
            SessionManager.terminal = terminal
            return terminal
        }

        // Not a provisioned terminal — try the address as an ordinary Swipe
        // account. An owner signing in with their dashboard email had no
        // `pos_terminals` record and was turned away with "this terminal was
        // removed", which is both wrong and unactionable: nothing had been
        // removed, the account simply was not a terminal. They get a session
        // scoped to their own company instead.
        val account = findAccountSession(uid, address)
        if (account == null) {
            auth.signOut()
            throw IllegalStateException(
                "$address signed in, but it is not a POS terminal and is not linked to a " +
                    "business on Swipe. Ask an owner to add you to the company, or sign in " +
                    "with terminal credentials from POS Devices."
            )
        }

        SessionManager.terminal = account
        return account
    }

    /**
     * Builds a session for a Swipe account holder rather than a terminal.
     *
     * Company membership lives in Firestore `user_company_roles`; the company's
     * display name and POS domain come off the company record. The result is a
     * synthetic Terminal so everything downstream — sales, stock, presence —
     * keeps working against one type instead of branching on session kind.
     *
     * Permissions follow the account's role: an owner or admin gets the manager
     * set, anyone else gets the cashier set.
     */
    private suspend fun findAccountSession(uid: String, address: String): Terminal? {
        val roleRow = withContext(Dispatchers.IO) {
            fetchOne(
                "user_company_roles",
                SwipeApi.opts(where = SwipeApi.where(Triple("user_id", "==", uid))),
            )
        } ?: return null
        val companyId = roleRow.optString("company_id").ifEmpty { return null }
        val role = roleRow.optString("role", "member").lowercase()

        val companyRow = withContext(Dispatchers.IO) { fetchById("companies", companyId) }
        val companyName = companyRow?.optString("name")?.ifEmpty { null } ?: "My business"

        val isManager = role in setOf("owner", "admin", "manager")

        return Terminal(
            // Namespaced so an account session can never collide with a real
            // terminal id in `pos_live` or on a sale record.
            id = "account:$uid",
            companyId = companyId,
            companyName = companyName,
            posDomain = companyRow?.optString("pos_domain").orEmpty(),
            terminalName = address.substringBefore('@'),
            username = address,
            authEmail = address,
            authUid = uid,
            role = if (isManager) "manager" else "cashier",
            permissions = TerminalPermissions(
                addInventory = isManager,
                viewReports = isManager,
                allowRefunds = isManager,
            ),
            active = true,
        )
    }

    /**
     * Keyed lookup on `auth_uid`.
     *
     * On RTDB this needed an `.indexOn` that the ruleset deliberately does not
     * carry, so the dashboard ended up reading the whole node and filtering in
     * JavaScript. Postgres has a real index on the column.
     */
    private suspend fun findTerminalByUid(uid: String): Terminal? {
        val row = withContext(Dispatchers.IO) {
            fetchOne(
                "pos_terminals",
                SwipeApi.opts(where = SwipeApi.where(Triple("auth_uid", "==", uid))),
            )
        } ?: return null
        return Terminal.fromMap(row.optString("id"), row.toMap())
    }

    /** JSONObject -> Map, for the model classes' existing `fromMap` builders. */
    private fun JSONObject.toMap(): Map<String, Any?> =
        keys().asSequence().associateWith { key ->
            when (val v = opt(key)) {
                JSONObject.NULL -> null
                is JSONObject -> v.toMap()
                is JSONArray -> (0 until v.length()).map { i -> v.opt(i) }
                else -> v
            }
        }

    /**
     * Outcome of re-reading the terminal record.
     *
     * [Unavailable] and [Revoked] have to be different answers. Collapsing them
     * into "null" meant a dropped connection read as "this device was deleted",
     * and the background refresh could not tell a cashier working through a bad
     * signal from one whose terminal an owner had just removed.
     */
    sealed interface TerminalRefresh {
        /** The dashboard's current record for this device. */
        data class Updated(val terminal: Terminal) : TerminalRefresh

        /** Authoritative: the record is gone, or the device was deactivated. */
        data object Revoked : TerminalRefresh

        /** Could not check — offline, timed out, or denied. Keep the session. */
        data object Unavailable : TerminalRefresh
    }

    /**
     * Re-reads the terminal record so permission and role changes made on the
     * dashboard reach a device that is already signed in.
     *
     * Never throws: this runs on a timer behind whatever the cashier is doing,
     * and a network blip on a background refresh must not be able to interrupt
     * a sale — let alone take the app down with it.
     */
    suspend fun refreshTerminal(): TerminalRefresh {
        val uid = auth.currentUser?.uid ?: return TerminalRefresh.Revoked
        val current = SessionManager.terminal

        return try {
            // An account session has no `pos_terminals` record by definition, so
            // looking one up would come back empty and read as "deleted from the
            // dashboard", signing the owner out on the very first refresh.
            if (current != null && current.isAccountSession) {
                val account = findAccountSession(uid, current.authEmail)
                    ?: return TerminalRefresh.Revoked
                SessionManager.terminal = account
                return TerminalRefresh.Updated(account)
            }

            val fresh = findTerminalByUid(uid) ?: return TerminalRefresh.Revoked
            if (!fresh.active) return TerminalRefresh.Revoked
            SessionManager.terminal = fresh
            TerminalRefresh.Updated(fresh)
        } catch (e: Exception) {
            TerminalRefresh.Unavailable
        }
    }

    fun signOut() {
        val t = SessionManager.terminal
        if (t != null) {
            // Best-effort: mark the device offline before the token goes away.
            // Queued rather than posted — if there is no signal this is exactly
            // the write that cannot go out, and blocking sign-out on it would
            // strand the cashier.
            runCatching {
                Outbox.enqueue(
                    SwipeApi.opUpdate("pos_live", t.id, json("status" to "offline")),
                    "Terminal offline",
                )
            }
        }
        SessionManager.terminal = null
        auth.signOut()
    }

    // ---------------------------------------------------------------------
    // Products — the same rows the Swipe Inventory page reads and writes
    // ---------------------------------------------------------------------

    /**
     * Live view of the company's active stock.
     *
     * Filtering and ordering are pushed to the database (backed by the
     * `company_id, is_active, name` index the schema ships), so the device and
     * the dashboard walk the same query rather than two client-side
     * approximations of it.
     *
     * Firestore pushed changes; this polls, and mirrors every result. The
     * mirror is what a till sells from when the signal goes — the first emit
     * comes from it immediately, so the catalogue is on screen before the
     * network is consulted at all.
     */
    fun listenProducts(
        companyId: String,
        onChange: (List<Product>) -> Unit,
        onError: (Exception) -> Unit = {},
    ): ScanRequestSubscription {
        val opts = SwipeApi.opts(
            where = SwipeApi.where(
                Triple("company_id", "==", companyId),
                Triple("is_active", "==", true),
            ),
            orderBy = SwipeApi.orderBy("name", "asc"),
        )

        val job = pollScope.launch {
            // Straight from the mirror first: instant, and correct offline.
            local.getAll("products")
                .takeIf { it.isNotEmpty() }
                ?.let { cached ->
                    val products = cached.mapNotNull { Product.fromMap(it.optString("id"), it.toMap()) }
                    withContext(Dispatchers.Main) { onChange(products) }
                }

            while (isActive) {
                try {
                    val rows = withContext(Dispatchers.IO) { fetchList("products", opts) }
                    val products = rows.mapNotNull { Product.fromMap(it.optString("id"), it.toMap()) }
                    withContext(Dispatchers.Main) { onChange(products) }
                } catch (err: Exception) {
                    withContext(Dispatchers.Main) { onError(err) }
                }
                delay(20_000)
            }
        }
        return ScanRequestSubscription(job)
    }

    /** The business details printed on receipts. */
    suspend fun getCompanyProfile(companyId: String): CompanyProfile? {
        val row = withContext(Dispatchers.IO) { fetchById("companies", companyId) } ?: return null
        return CompanyProfile.fromMap(row.optString("id"), row.toMap())
    }

    /**
     * Matches a scanned code against a SKU.
     *
     * Scanners and printed labels disagree about case and leading zeros more
     * often than not, so this normalises both sides — the same two-step match
     * the web register uses, including the leading-zero fallback that covers a
     * UPC-A code read as a 13-digit EAN. Searching the live list rather than
     * querying keeps it instant and works offline from the mirror.
     */
    fun matchBarcode(products: List<Product>, code: String): Product? {
        val raw = code.trim()
        val normalise = { v: String -> v.trim().lowercase(Locale.ROOT).trimStart('0') }
        val target = normalise(raw)
        return products.firstOrNull { it.sku?.trim()?.equals(raw, ignoreCase = true) == true }
            ?: products.firstOrNull { p -> p.sku?.let { normalise(it) == target } == true }
    }

    /** Adds a product to the shared inventory (requires the add_inventory permission). */
    suspend fun addProduct(
        terminal: Terminal,
        name: String,
        sku: String?,
        category: String?,
        description: String?,
        unitPrice: Double,
        costPrice: Double,
        stock: Double,
        lowStockThreshold: Double,
        unit: String,
        imageBytes: ByteArray?,
    ): String {
        requirePermission(terminal.permissions.addInventory, "add stock")

        val imageUrl = imageBytes?.let { uploadProductImage(terminal.companyId, it) }
        val now = nowIso()

        // These defaults are load-bearing, not cosmetic: the web lists products
        // with `is_active == true` scoped by `company_id`, so a row written
        // without them is invisible on the dashboard the moment it is saved.
        // Client-generated so the row can be written locally and queued: the
        // id has to exist before the server has seen it, and it is what makes a
        // replayed write land on the same row instead of creating a second one.
        val productId = UUID.randomUUID().toString()
        val doc = hashMapOf<String, Any?>(
            "id" to productId,
            "company_id" to terminal.companyId,
            "name" to name,
            "sku" to sku,
            "description" to description,
            "category" to category,
            "unit_price" to unitPrice,
            "cost_price" to costPrice,
            "currency" to "GMD",
            "current_stock" to 0.0,
            "low_stock_threshold" to lowStockThreshold,
            "unit" to unit,
            "is_active" to true,
            "image_url" to imageUrl,
            "created_by" to terminal.authUid,
            "created_at" to now,
            "updated_at" to now,
        )
        val row = JSONObject(doc.filterValues { it != null })
        queueWrite(SwipeApi.opInsert("products", row), "Product $name", "products", row)

        // Opening stock is a real stock event, so it goes through the same
        // write path as everything else and lands in the ledger the web's
        // repair tooling replays. `reference` is how an opening balance is told
        // apart from a later restock.
        if (stock != 0.0) {
            applyStockChanges(
                terminal,
                listOf(
                    StockChange(
                        productId = productId,
                        type = StockMovementType.STOCK_IN,
                        quantity = stock,
                        unitPrice = costPrice,
                        totalAmount = costPrice * stock,
                        notes = "Opening stock (TikketX POS · ${terminal.terminalName})",
                        reference = "opening-balance",
                    )
                ),
            )
        }
        return productId
    }

    /** Edits a product in place. Stock is not touched here — use [applyStockChanges]. */
    suspend fun updateProduct(
        terminal: Terminal,
        productId: String,
        name: String,
        sku: String?,
        category: String?,
        description: String?,
        unitPrice: Double,
        costPrice: Double,
        lowStockThreshold: Double,
        unit: String,
        imageBytes: ByteArray?,
    ) {
        requirePermission(terminal.permissions.addInventory, "edit stock")

        val patch = hashMapOf<String, Any?>(
            "name" to name,
            "sku" to sku,
            "description" to description,
            "category" to category,
            "unit_price" to unitPrice,
            "cost_price" to costPrice,
            "low_stock_threshold" to lowStockThreshold,
            "unit" to unit,
            "updated_at" to nowIso(),
        )
        imageBytes?.let { bytes ->
            uploadProductImage(terminal.companyId, bytes)?.let { patch["image_url"] = it }
        }
        val patchJson = JSONObject(patch.filterValues { it != null })
        // Mirror the edit locally so the catalogue on screen reflects it at
        // once, offline or not.
        local.get("products", productId)?.let { cached ->
            for (key in patchJson.keys()) cached.put(key, patchJson.get(key))
            local.put("products", cached)
        }
        fireAndForget(SwipeApi.opUpdate("products", productId, patchJson), "Product $name")
    }

    /**
     * Removes a product from the catalogue.
     *
     * Soft delete, exactly as the web does it: the row stays so historical sale
     * items and stock movements still resolve a name, and `is_active == false`
     * drops it out of every list on both ends.
     */
    suspend fun deactivateProduct(terminal: Terminal, productId: String) {
        requirePermission(terminal.permissions.addInventory, "remove stock")
        fireAndForget(
            SwipeApi.opUpdate(
                "products",
                productId,
                json("is_active" to false),
            ),
            "Remove product"
        )
    }

    /**
     * Uploads a product photo, or gives up and returns null.
     *
     * Cloud Storage has no offline queue — unlike the row writes, which go
     * through the outbox, an upload with no signal simply never finishes.
     * Blocking on it would mean a shop that lost its connection could not add
     * stock at all, so the photo is treated as the optional part it is: the
     * product saves without it, and the picture can be attached later. [PHOTO_UPLOAD_TIMEOUT_MS] is generous because a photo on a
     * slow connection is worth waiting for; a missing connection is not.
     */
    private suspend fun uploadProductImage(companyId: String, bytes: ByteArray): String? =
        withTimeoutOrNull(PHOTO_UPLOAD_TIMEOUT_MS) {
            runCatching {
                val ref = storage.reference
                    .child("product_images/$companyId/${UUID.randomUUID()}.jpg")
                ref.putBytes(bytes).await()
                ref.downloadUrl.await().toString()
            }.getOrNull()
        }

    private const val PHOTO_UPLOAD_TIMEOUT_MS = 20_000L

    private fun requirePermission(granted: Boolean, action: String) {
        if (!granted) throw IllegalStateException("This terminal isn't allowed to $action.")
    }

    // ---------------------------------------------------------------------
    // Stock — the single write path
    // ---------------------------------------------------------------------

    /**
     * Appends the movement rows. That IS the stock change.
     *
     * The Kotlin twin of `applyStockChanges` in the web's inventoryLedger.ts,
     * and the only place stock is written from this app.
     *
     * What changed with Neon: this used to append a movement AND move the
     * product, the second half via `FieldValue.increment()` because a
     * transaction needs a server read and that is exactly when a shop still
     * needs to sell. Now a Postgres trigger (`apply_stock_movement`) moves
     * `current_stock` from the movement row, inside the same statement. Three
     * things follow, and the third is why a till can sell offline at all:
     *
     *   * the ledger and the shelf count cannot disagree;
     *   * concurrent terminals are safe — `col = col + n` takes a row lock, so
     *     two tills selling the last unit both land;
     *   * REPLAYING IS FREE. The trigger fires AFTER INSERT, and a replayed row
     *     takes the ON CONFLICT ... DO UPDATE path, which is not an insert. A
     *     queued sale retried after an ambiguous failure cannot decrement stock
     *     twice — the property the whole outbox rests on.
     *
     * Stock clamps at zero server-side unless [allowNegative]: a negative shelf
     * count is almost always a double-posted sale rather than a real state.
     *
     * Changes are grouped by nothing — each line is its own movement row, which
     * is what the ledger wants; the trigger sums them.
     */
    suspend fun applyStockChanges(
        terminal: Terminal,
        changes: List<StockChange>,
        allowNegative: Boolean = false,
    ) {
        for (write in stockChangeWrites(terminal, changes, allowNegative)) {
            fireAndForget(write.op, write.what)
        }
    }

    /** A queued write, with the words to use if the server later refuses it. */
    private class PendingWrite(val op: JSONObject, val what: String)

    /**
     * Builds the stock writes and hands them back without sending any of them,
     * so a caller can queue them ([applyStockChanges]) or fold them into a
     * larger batch ([completeSale]).
     */
    private fun stockChangeWrites(
        terminal: Terminal,
        changes: List<StockChange>,
        allowNegative: Boolean = false,
    ): List<PendingWrite> {
        val tracked = changes.filter { it.productId.isNotEmpty() && it.quantity != 0.0 }
        if (tracked.isEmpty()) return emptyList()

        val writes = mutableListOf<PendingWrite>()
        for (change in tracked) {
            // Minted locally, so the row is referable the instant it is written
            // and a replay lands on the same row rather than making a second.
            val movementId = UUID.randomUUID().toString()
            val row = json(
                "id" to movementId,
                "product_id" to change.productId,
                // stock_movements is company data; a row without company_id is
                // unfindable from the dashboard afterwards.
                "company_id" to terminal.companyId,
                "type" to change.type.wire,
                // Positive magnitude: the sign belongs to the movement type,
                // not the quantity, so a "sale" of 5 reads as 5 everywhere and
                // the trigger applies the direction.
                "quantity" to kotlin.math.abs(change.quantity),
                "unit_price" to change.unitPrice,
                "total_amount" to change.totalAmount,
                "notes" to change.notes,
                "reference" to change.reference,
                "created_by" to terminal.authUid,
                // Read by the trigger. Only the caller knows whether a
                // stock-take may go below zero.
                "allow_negative" to allowNegative,
            )
            writes += PendingWrite(
                SwipeApi.opInsert("stock_movements", row),
                "Stock movement for ${change.productId}",
            )

            // Move the mirrored product too, so the grid on screen reflects the
            // sale immediately. The server is the authority; this is just the
            // same arithmetic, locally, until the next refresh overwrites it.
            local.get("products", change.productId)?.let { cached ->
                val delta = when (change.type) {
                    StockMovementType.STOCK_IN, StockMovementType.RETURN ->
                        kotlin.math.abs(change.quantity)
                    StockMovementType.SALE -> -kotlin.math.abs(change.quantity)
                    StockMovementType.ADJUSTMENT -> change.quantity
                }
                val next = cached.optDouble("current_stock", 0.0) + delta
                cached.put("current_stock", if (allowNegative) next else maxOf(0.0, next))
                local.put("products", cached)
            }
        }
        return writes
    }

    /**
     * Recent movements for one product, newest first.
     *
     * Filtered on `company_id` as well as the product, because Firestore checks
     * rules against a query's constraints rather than its results: a rule that
     * gates on company membership rejects the whole query unless the query
     * itself proves it only asks for that company's rows.
     */
    suspend fun stockHistory(
        companyId: String,
        productId: String,
        limit: Long = 50,
    ): List<StockMovement> {
        val rows = withContext(Dispatchers.IO) {
            fetchList(
                "stock_movements",
                SwipeApi.opts(
                    where = SwipeApi.where(
                        Triple("company_id", "==", companyId),
                        Triple("product_id", "==", productId),
                    ),
                    orderBy = SwipeApi.orderBy("created_at", "desc"),
                    limit = limit.toInt(),
                ),
                // Not mirrored: this is a history screen, not something the
                // till needs in order to sell, and caching it would evict the
                // catalogue that it does need.
                mirror = false,
            )
        }
        return rows.mapNotNull { StockMovement.fromMap(it.optString("id"), it.toMap()) }
    }

    // ---------------------------------------------------------------------
    // Tax
    // ---------------------------------------------------------------------

    /** Active tax rates, offered as an optional line at checkout like the web. */
    suspend fun taxRates(companyId: String): List<TaxRate> {
        // Mirrored: checkout offers these, so they have to be there offline.
        val rows = withContext(Dispatchers.IO) {
            fetchList(
                "tax_rates",
                SwipeApi.opts(where = SwipeApi.where(Triple("company_id", "==", companyId))),
            )
        }
        return rows
            .mapNotNull { TaxRate.fromMap(it.optString("id"), it.toMap()) }
            .filter { it.isActive }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    // ---------------------------------------------------------------------
    // Sales
    // ---------------------------------------------------------------------

    /**
     * Writes the sale the way the Swipe web register does — `pos_sales` plus
     * `pos_sale_items` — takes the stock off the shelf through the single write
     * path, and records a customer credit when the sale is on account. The
     * dashboard's History, Reports and financial statements pick all of it up
     * untouched.
     *
     * Returns as soon as the writes are queued, which is the same instant they
     * become durable — see [fireAndForget]. Nothing on the ticket comes from the
     * server: the sale's id, its number and its timestamp are all minted here,
     * so the receipt can be printed the moment this returns and the sale can be
     * rung up with no signal at all.
     *
     * It still throws for what it can decide on the spot — an empty cart, a
     * credit sale with nobody's name on it. A rejection from the server arrives
     * long after the customer has left and goes to [onDeferredWriteFailure].
     */
    fun completeSale(
        terminal: Terminal,
        items: List<CartItem>,
        paymentMethod: PaymentMethod,
        amountTendered: Double?,
        customerName: String?,
        customerPhone: String? = null,
        creditDueDate: String? = null,
        taxRate: TaxRate? = null,
    ): CompletedSale {
        if (items.isEmpty()) throw IllegalStateException("The cart is empty.")
        if (paymentMethod == PaymentMethod.CREDIT && customerName.isNullOrBlank()) {
            throw IllegalStateException("Credit sales need a customer name.")
        }

        val subtotal = items.sumOf { it.grossTotal }
        val discountTotal = items.sumOf { it.discount }
        val taxable = subtotal - discountTotal
        val taxAmount = taxRate?.let { taxable * (it.rate / 100.0) } ?: 0.0
        val total = taxable + taxAmount

        // Credit sales tender nothing now; cash defaults to the exact amount.
        val tendered = when (paymentMethod) {
            PaymentMethod.CREDIT -> 0.0
            PaymentMethod.CASH -> amountTendered ?: total
            else -> total
        }
        val change = if (paymentMethod == PaymentMethod.CASH) maxOf(0.0, tendered - total) else 0.0

        val saleNumber = generateSaleNumber()
        val now = nowIso()

        val saleDoc = hashMapOf<String, Any?>(
            "company_id" to terminal.companyId,
            "shift_id" to null,
            "sale_number" to saleNumber,
            // Reporting filters on status == "completed" and the returns flow
            // sets "returned". Without this written at creation the sale reaches
            // Firestore but never appears on the dashboard or in any financial
            // statement.
            "status" to "completed",
            "currency" to "GMD",
            "subtotal" to subtotal,
            "discount_total" to discountTotal,
            "tax_total" to taxAmount,
            "tax_rate_id" to taxRate?.id,
            "total" to total,
            "payment_method" to paymentMethod.wire,
            "amount_tendered" to tendered,
            "change_given" to change,
            "cashier_id" to terminal.authUid,
            "customer_name" to customerName?.takeIf { it.isNotBlank() },
            "bank_account_id" to null,
            "source" to "tikketx_pos_android",
            "terminal_id" to terminal.id,
            "terminal_name" to terminal.terminalName,
            "created_at" to now,
        )
        // Minted locally so the sale has its id — and its receipt — with or
        // without a connection, and so a replayed write lands on this same row.
        val saleId = UUID.randomUUID().toString()
        saleDoc["id"] = saleId
        val saleRow = JSONObject(saleDoc.filterValues { it != null })
        queueWrite(SwipeApi.opInsert("pos_sales", saleRow), "Sale $saleNumber", "pos_sales", saleRow)

        for (item in items) {
            val itemRow = json(
                "id" to UUID.randomUUID().toString(),
                "sale_id" to saleId,
                "company_id" to terminal.companyId,
                "product_id" to item.product.id,
                "product_name" to item.product.name,
                "quantity" to item.quantity,
                "unit_price" to item.product.unitPrice,
                "discount" to item.discount,
                "total" to item.lineTotal,
            )
            fireAndForget(
                SwipeApi.opInsert("pos_sale_items", itemRow),
                "${item.product.name} on $saleNumber",
            )
        }

        val stockWrites = stockChangeWrites(
            terminal,
            items.map { item ->
                StockChange(
                    productId = item.product.id,
                    type = StockMovementType.SALE,
                    quantity = item.quantity,
                    unitPrice = item.product.unitPrice,
                    totalAmount = item.lineTotal,
                    notes = customerName?.takeIf { it.isNotBlank() }
                        ?.let { "POS sale to $it (TikketX POS · ${terminal.terminalName})" }
                        ?: "POS sale (TikketX POS · ${terminal.terminalName})",
                    reference = saleNumber,
                )
            },
        )
        for (write in stockWrites) fireAndForget(write.op, write.what)

        if (paymentMethod == PaymentMethod.CREDIT) {
            fireAndForget(SwipeApi.opInsert("customer_credits", JSONObject(
                hashMapOf<String, Any?>(
                    "id" to UUID.randomUUID().toString(),
                    "company_id" to terminal.companyId,
                    "customer_name" to customerName!!.trim(),
                    "customer_phone" to customerPhone?.takeIf { it.isNotBlank() },
                    "total_amount" to total,
                    "amount_paid" to 0.0,
                    "currency" to "GMD",
                    "status" to "outstanding",
                    "due_date" to creditDueDate,
                    "pos_sale_id" to saleId,
                    "notes" to "POS Credit Sale - " +
                        items.joinToString(", ") { "${fmtQty(it.quantity)}× ${it.product.name}" },
                    "created_by" to terminal.authUid,
                    "created_at" to now,
                ).filterValues { it != null }
            )), "Credit record for $saleNumber")
        }

        return CompletedSale(
            saleId = saleId,
            saleNumber = saleNumber,
            items = items.map { it.copy() },
            subtotal = subtotal,
            discountTotal = discountTotal,
            taxLabel = taxRate?.let { "${it.name} (${trimNumber(it.rate)}%)" },
            taxAmount = taxAmount,
            total = total,
            paymentMethod = paymentMethod,
            amountTendered = tendered,
            changeGiven = change,
            customerName = customerName?.takeIf { it.isNotBlank() },
            cashierName = terminal.terminalName,
            companyName = terminal.companyName,
            createdAt = now,
        )
    }

    /** Recent sales for this company, newest first — the returns entry point. */
    suspend fun recentSales(companyId: String, limit: Long = 25): List<PosSale> {
        // Mirrored: the returns screen has to work offline, and a sale rung up
        // while offline is in the mirror before it is anywhere else.
        val rows = withContext(Dispatchers.IO) {
            fetchList(
                "pos_sales",
                SwipeApi.opts(
                    where = SwipeApi.where(Triple("company_id", "==", companyId)),
                    orderBy = SwipeApi.orderBy("created_at", "desc"),
                    limit = limit.toInt(),
                ),
            )
        }
        return rows.mapNotNull { PosSale.fromMap(it.optString("id"), it.toMap()) }
    }

    suspend fun saleItems(saleId: String): List<PosSaleItem> {
        val rows = withContext(Dispatchers.IO) {
            fetchList(
                "pos_sale_items",
                SwipeApi.opts(where = SwipeApi.where(Triple("sale_id", "==", saleId))),
                mirror = false,
            )
        }
        return rows.mapNotNull { PosSaleItem.fromMap(it.optString("id"), it.toMap()) }
    }

    /**
     * Refunds part or all of a sale: puts the goods back on the shelf through
     * the single write path and marks the sale returned, so the dashboard and
     * the device agree on both the stock and the sale's status.
     */
    suspend fun processReturn(
        terminal: Terminal,
        sale: PosSale,
        quantitiesByItemId: Map<String, Double>,
        reason: String?,
    ) {
        requirePermission(terminal.permissions.allowRefunds, "process refunds")

        val returning = sale.items.filter { (quantitiesByItemId[it.id] ?: 0.0) > 0.0 }
        if (returning.isEmpty()) throw IllegalStateException("Pick at least one item to return.")

        applyStockChanges(
            terminal,
            returning.mapNotNull { item ->
                val productId = item.productId ?: return@mapNotNull null
                val qty = quantitiesByItemId[item.id] ?: return@mapNotNull null
                StockChange(
                    productId = productId,
                    type = StockMovementType.RETURN,
                    quantity = qty,
                    unitPrice = item.unitPrice,
                    totalAmount = qty * item.unitPrice,
                    notes = "Return from ${sale.saleNumber}" +
                        (reason?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""),
                    reference = sale.saleNumber,
                )
            },
        )

        fireAndForget(
            SwipeApi.opUpdate(
                "pos_sales",
                sale.id,
                json(
                    "status" to "returned",
                    "notes" to "Returned: ${reason?.takeIf { it.isNotBlank() } ?: "No reason given"}",
                ),
            ),
            "Return on ${sale.saleNumber}",
        )
    }

    /** Today's totals for this company (client-side sum — small daily volumes). */
    suspend fun todayStats(companyId: String): DayStats {
        val start = startOfTodayIso()
        // The day is filtered in SQL rather than by pulling 500 rows and
        // discarding most of them.
        val sales = withContext(Dispatchers.IO) {
            fetchList(
                "pos_sales",
                SwipeApi.opts(
                    where = SwipeApi.where(
                        Triple("company_id", "==", companyId),
                        Triple("created_at", ">=", start),
                    ),
                    orderBy = SwipeApi.orderBy("created_at", "desc"),
                ),
                mirror = false,
            )
        }

        var total = 0.0
        var cash = 0.0
        var nonCash = 0.0
        var count = 0
        val saleIds = mutableListOf<String>()
        for (row in sales) {
            // Returned sales are reversed, not receipts — the dashboard leaves
            // them out of the day's takings and so does this.
            if (row.optString("status", "completed") == "returned") continue
            val amount = row.optDouble("total", 0.0)
            total += amount
            if (row.optString("payment_method") == "cash") cash += amount else nonCash += amount
            count++
            saleIds += row.optString("id")
        }

        var items = 0.0
        if (saleIds.isNotEmpty()) {
            // One query. Firestore capped `whereIn` at 30 values, so this used
            // to walk the day in pages; Postgres has no such cap.
            val itemRows = withContext(Dispatchers.IO) {
                fetchList(
                    "pos_sale_items",
                    SwipeApi.opts(
                        where = SwipeApi.where(Triple("sale_id", "in", JSONArray(saleIds))),
                    ),
                    mirror = false,
                )
            }
            for (row in itemRows) items += row.optDouble("quantity", 0.0)
        }

        return DayStats(
            salesTotal = total,
            transactions = count,
            itemsSold = items,
            cashTotal = cash,
            nonCashTotal = nonCash,
        )
    }

    // ---------------------------------------------------------------------
    // Formatting helpers shared by the UI
    // ---------------------------------------------------------------------

    /** Drops a pointless ".0" so 3 reads as "3" and 3.5 as "3.5". */
    fun trimNumber(value: Double): String =
        if (value == Math.floor(value) && !value.isInfinite()) value.toLong().toString()
        else "%.2f".format(value).trimEnd('0').trimEnd('.')

    fun fmtQty(value: Double): String = trimNumber(value)

    fun fmtMoney(value: Double): String = "D%,.2f".format(value)

    // ---------------------------------------------------------------------
    // Remote scan requests — the Swipe Inventory form can ask a signed-in
    // terminal to scan a barcode with its camera. Channel:
    // RTDB pos_scan_requests/<companyId>/<requestId> with
    // status pending → claimed → done (dashboard creates + removes nodes;
    // terminals only flip status and attach the scanned code).
    // ---------------------------------------------------------------------

    /** Handle for a scan-request subscription; call [remove] when done. */
    /**
     * A running watcher. Named for its original job, kept because every call
     * site already stores one and calls [remove] on teardown.
     */
    class ScanRequestSubscription internal constructor(private val job: Job) {
        fun remove() = job.cancel()
    }

    /**
     * Streams every scan request for this company; filter on the caller side.
     *
     * This is the most latency-sensitive thing the till does — someone at a
     * computer is holding a dialog open waiting for this device to answer — so
     * it polls hardest of anything here. That dialog is only open for the few
     * seconds the handshake takes, so the cost is bounded by a human's patience.
     */
    fun listenScanRequests(
        terminal: Terminal,
        onRequests: (List<ScanRequest>) -> Unit,
    ): ScanRequestSubscription {
        val opts = SwipeApi.opts(
            where = SwipeApi.where(Triple("company_id", "==", terminal.companyId)),
            orderBy = SwipeApi.orderBy("created_at", "desc"),
            limit = 25,
        )
        val job = pollScope.launch {
            while (isActive) {
                val rows = withContext(Dispatchers.IO) {
                    fetchList("pos_scan_requests", opts, mirror = false)
                }
                val requests = rows.mapNotNull { ScanRequest.fromMap(it.optString("id"), it.toMap()) }
                withContext(Dispatchers.Main) { onRequests(requests) }
                delay(1_500)
            }
        }
        return ScanRequestSubscription(job)
    }

    /**
     * Claiming a scan is a race between terminals, and the loser has to know it
     * lost. RTDB settled that with a transaction; here the guard is a
     * conditional update — `updateWhere` only touches rows that still match, so
     * a second device claiming an already-claimed request changes nothing.
     *
     * Deliberately not read-then-write: two tills reading "pending" at the same
     * moment would both believe they had it.
     */
    private fun guardedScanUpdate(
        guard: JSONArray,
        patch: JSONObject,
        what: String,
    ) {
        fireAndForget(
            JSONObject()
                .put("op", "updateWhere")
                .put("table", "pos_scan_requests")
                .put("opts", JSONObject().put("where", guard))
                .put("patch", patch),
            what,
        )
    }

    /** Marks the request as being handled by this terminal (first device wins). */
    fun claimScanRequest(terminal: Terminal, requestId: String) {
        // Still pending, or already ours — re-claiming what this terminal holds
        // is harmless and happens when the camera is reopened.
        guardedScanUpdate(
            SwipeApi.where(
                Triple("id", "==", requestId),
                Triple("status", "==", "pending"),
            ),
            json(
                "status" to "claimed",
                "claimed_by" to terminal.id,
                "claimed_by_name" to terminal.terminalName,
            ),
            "Claim scan",
        )
    }

    /** Delivers the scanned code back to the dashboard. */
    fun completeScanRequest(terminal: Terminal, requestId: String, code: String) {
        // Anything not already finished can be answered.
        guardedScanUpdate(
            SwipeApi.where(
                Triple("id", "==", requestId),
                Triple("status", "in", JSONArray(listOf("pending", "claimed"))),
            ),
            json(
                "status" to "done",
                "code" to code,
                "claimed_by" to terminal.id,
                "claimed_by_name" to terminal.terminalName,
                "completed_at" to nowIso(),
            ),
            "Scanned code",
        )
    }

    /** Puts an aborted scan back up for grabs (e.g. cashier closed the camera). */
    fun releaseScanRequest(terminal: Terminal, requestId: String) {
        // Only the holder may put it back — the guard is what stops one till
        // releasing another's claim.
        guardedScanUpdate(
            SwipeApi.where(
                Triple("id", "==", requestId),
                Triple("status", "==", "claimed"),
                Triple("claimed_by", "==", terminal.id),
            ),
            json("status" to "pending", "claimed_by" to null, "claimed_by_name" to null),
            "Release scan",
        )
    }

    // ---------------------------------------------------------------------
    // Live presence — powers the green dot on the Swipe POS Devices page
    // ---------------------------------------------------------------------

    /**
     * Reports this device as alive.
     *
     * RTDB had `onDisconnect()`, which let the server mark a till offline the
     * moment its socket dropped. HTTP has no equivalent, so liveness is inferred
     * from the timestamp — which is what the dashboard already did anyway:
     * `isOnline` there treats a heartbeat older than three minutes as offline,
     * so a device that vanishes still goes grey without being told.
     *
     * Posted directly rather than queued. A heartbeat that arrives late is worse
     * than one that never arrives: queueing them would drain a backlog of stale
     * "I am online" claims the moment an outage ended.
     */
    fun heartbeat(terminal: Terminal, stats: DayStats?, deviceModel: String, appVersion: String) {
        val row = json(
            "id" to terminal.id,
            "company_id" to terminal.companyId,
            "terminal_id" to terminal.id,
            "status" to "online",
            "last_heartbeat" to nowIso(),
            "device_model" to deviceModel,
            "app_version" to appVersion,
            "todays_sales" to stats?.salesTotal,
            "todays_transactions" to stats?.transactions,
        )
        pollScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { SwipeApi.call(SwipeApi.opUpsert("pos_live", row)) }
            }
        }
    }
}
