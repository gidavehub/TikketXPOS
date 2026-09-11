package com.tikketx.pos.data.net

import com.tikketx.pos.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The Swipe data tier, over HTTP.
 *
 * Speaks exactly the protocol the web app's storage driver speaks — POST a
 * single `{op, table, ...}` to /api/data, or an array of them — so the till and
 * the dashboard go through one implementation of the query engine and one
 * storage shape. Products written here are indistinguishable from products
 * written in the browser, which is the whole point: there is one copy of the
 * data and both ends read it.
 *
 * This replaces the Firestore SDK. What the SDK also gave us — a durable write
 * queue and a readable cache with no network — is not here; that is [LocalStore]
 * and [Outbox]'s job, and the reason they exist.
 *
 * Auth is unchanged: terminals still sign in as real Firebase Auth users. This
 * class does not carry a token, because the route does not check one (the same
 * posture the Firebase rules have always had). When that changes, the header
 * goes on here and nowhere else.
 */
object SwipeApi {

    /**
     * Where the data route lives. Set per build in app/build.gradle.kts so a
     * debug install can point at a laptop and a release at the deployment.
     */
    private val baseUrl: String get() = BuildConfig.SWIPE_API_ORIGIN.trimEnd('/')

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * Timeouts are deliberately short. A till on a bad connection should fall
     * through to the offline path quickly rather than leaving a cashier on a
     * spinner — the same judgement the Firestore version made with its 4s
     * write-acknowledgement window.
     */
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** A request the server refused on its merits — retrying will not help. */
    class Rejected(val status: Int, message: String) : IOException(message)

    private fun post(payload: String): String {
        val request = Request.Builder()
            .url("$baseUrl/api/data")
            .post(payload.toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful) return body

            val message = runCatching { JSONObject(body).optString("error") }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: "Request failed (${response.code})"

            // 4xx is the caller's fault and will fail again identically; the
            // outbox must drop such an entry rather than retry it forever.
            // 5xx and network failures are worth retrying.
            if (response.code in 400..499) throw Rejected(response.code, message)
            throw IOException(message)
        }
    }

    /** Run one operation and return its `result`, which may be any JSON value. */
    fun call(op: JSONObject): Any? {
        val body = post(op.toString())
        if (body.isEmpty()) return null
        return JSONObject(body).opt("result")
    }

    /** Run several operations in one round trip. */
    fun callBatch(ops: List<JSONObject>): List<Any?> {
        if (ops.isEmpty()) return emptyList()
        val body = post(JSONArray(ops).toString())
        val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { results.opt(it) }
    }

    // ── Operation builders ──────────────────────────────────────────────────
    //
    // Named to match the storage layer the web app calls, so the two read the
    // same way when they are compared side by side.

    fun opGetById(table: String, id: String): JSONObject =
        JSONObject().put("op", "getById").put("table", table).put("id", id)

    fun opGetList(table: String, opts: JSONObject = JSONObject()): JSONObject =
        JSONObject().put("op", "getList").put("table", table).put("opts", opts)

    fun opGetOne(table: String, opts: JSONObject): JSONObject =
        JSONObject().put("op", "getOne").put("table", table).put("opts", opts)

    fun opInsert(table: String, data: JSONObject): JSONObject =
        JSONObject().put("op", "insert").put("table", table).put("data", data)

    fun opInsertMany(table: String, rows: List<JSONObject>): JSONObject =
        JSONObject().put("op", "insertMany").put("table", table).put("rows", JSONArray(rows))

    fun opUpdate(table: String, id: String, patch: JSONObject): JSONObject =
        JSONObject().put("op", "update").put("table", table).put("id", id).put("patch", patch)

    fun opUpsert(table: String, data: JSONObject): JSONObject =
        JSONObject().put("op", "upsert").put("table", table).put("data", data)

    fun opRemove(table: String, id: String): JSONObject =
        JSONObject().put("op", "remove").put("table", table).put("id", id)

    // ── Query helpers ───────────────────────────────────────────────────────

    /** `[[field, op, value], …]` in the shape the engine expects. */
    fun where(vararg clauses: Triple<String, String, Any?>): JSONArray {
        val out = JSONArray()
        for ((field, op, value) in clauses) {
            out.put(JSONArray().put(field).put(op).put(value ?: JSONObject.NULL))
        }
        return out
    }

    fun opts(
        where: JSONArray? = null,
        orderBy: JSONArray? = null,
        limit: Int? = null,
    ): JSONObject {
        val o = JSONObject()
        if (where != null) o.put("where", where)
        if (orderBy != null) o.put("orderBy", orderBy)
        if (limit != null) o.put("limit", limit)
        return o
    }

    fun orderBy(field: String, direction: String = "asc"): JSONArray =
        JSONArray().put(JSONArray().put(field).put(direction))

    /** True when the route answers at all — used for the offline banner. */
    fun ping(): Boolean = runCatching {
        call(JSONObject().put("op", "count").put("table", "companies")
            .put("opts", opts(where = where(Triple("id", "==", "__ping__")))))
        true
    }.getOrDefault(false)
}
