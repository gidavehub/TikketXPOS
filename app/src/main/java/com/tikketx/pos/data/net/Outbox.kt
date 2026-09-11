package com.tikketx.pos.data.net

import com.tikketx.pos.data.local.LocalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The durable write queue that replaces Firestore's.
 *
 * A write is recorded locally first and acknowledged to the cashier
 * immediately, then pushed to the server in the background. That ordering is
 * the point: ringing up a sale must not depend on the network, and the old
 * implementation said so plainly — "with no signal, ringing up a sale sat on
 * the spinner until the cashier gave up, and nothing was ever recorded as far
 * as they could tell."
 *
 * ── Why replaying is safe ───────────────────────────────────────────────────
 *
 * Every queued write is idempotent by construction, so the ambiguous case — the
 * server applied it but the reply was lost — costs nothing when it is retried:
 *
 *   * rows carry client-generated ids, so `insert` and `upsert` are
 *     INSERT ... ON CONFLICT DO UPDATE: replaying rewrites the same row;
 *   * stock is never sent as a delta. The till appends a `stock_movements` row
 *     and a Postgres trigger moves `products.current_stock`. The trigger fires
 *     AFTER INSERT, and a conflicting insert takes the UPDATE path, so a
 *     replayed sale cannot decrement stock twice.
 *
 * That second property is the reason the stock trigger was restored on the
 * server. Without it an offline queue would be unsafe for the one thing a till
 * exists to do.
 */
object Outbox {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val drainLock = Mutex()

    private lateinit var store: LocalStore
    private var started = false

    /** Notified when the queue length changes, so the UI can show a banner. */
    var onPendingChanged: ((pending: Int, stuck: Int) -> Unit)? = null

    fun init(localStore: LocalStore) {
        store = localStore
        if (started) return
        started = true
        scope.launch {
            // A slow, steady drain. The queue is usually empty; when it is not,
            // the shop is offline and hammering a dead connection helps nobody.
            while (true) {
                drain()
                delay(15_000)
            }
        }
    }

    private fun nowIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

    /**
     * Queue a write. Returns immediately — the caller is not kept waiting on
     * the network.
     *
     * `label` is what the cashier would be shown if this never lands, so it
     * should name the thing in their terms ("Sale POS-0042"), not the table.
     */
    fun enqueue(payload: JSONObject, label: String? = null) {
        store.enqueue(payload, label, nowIso())
        notifyChanged()
        scope.launch { drain() }
    }

    /** Push everything queued, oldest first. Safe to call from anywhere. */
    suspend fun drain() {
        if (!::store.isInitialized) return
        // One drain at a time: two concurrent drains would both read the same
        // head of the queue and post it twice. Harmless given idempotency, but
        // pointless traffic on a connection that is already struggling.
        if (drainLock.isLocked) return
        drainLock.withLock {
            val entries = withContext(Dispatchers.IO) { store.pending() }
            for (entry in entries) {
                val sent = withContext(Dispatchers.IO) { push(entry) }
                // Stop at the first failure. The queue is ordered, and a later
                // write may depend on an earlier one — a sale item on its sale.
                if (!sent) break
            }
            notifyChanged()
        }
    }

    private fun push(entry: LocalStore.Entry): Boolean = try {
        SwipeApi.call(JSONObject(entry.payload))
        store.acknowledge(entry.seq)
        true
    } catch (rejected: SwipeApi.Rejected) {
        // The server refused this on its merits — a bad table, a missing
        // parent row. Retrying changes nothing, and leaving it at the head of
        // the queue would block every sale behind it forever. Drop it, but
        // count it first so it is visible rather than silent.
        store.recordFailure(entry.seq, "${rejected.status}: ${rejected.message}")
        store.acknowledge(entry.seq)
        true
    } catch (err: Exception) {
        // Transport failure: offline, timeout, 5xx. Keep it and try later.
        store.recordFailure(entry.seq, err.message)
        false
    }

    private fun notifyChanged() {
        val pending = store.pendingCount()
        val stuck = store.stuckCount()
        onPendingChanged?.invoke(pending, stuck)
    }

    fun pendingCount(): Int = if (::store.isInitialized) store.pendingCount() else 0
}
