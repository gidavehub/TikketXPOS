package com.tikketx.pos.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/**
 * The till's local database: a read mirror and a durable write queue.
 *
 * Firestore gave both of these for free and they were load-bearing. A shop in
 * Kombo does not stop trading because the network did, so the SDK's persistent
 * cache kept products readable and its write queue kept sales durable until the
 * connection returned. Moving to Postgres over HTTP removes both, so they are
 * rebuilt here — deliberately, rather than discovered missing by a cashier.
 *
 * Two tables, and the design is intentionally dull:
 *
 *   rows    a generic mirror, keyed (table_name, id), value is the row JSON.
 *           Whatever the server said last. Read paths fall back to this.
 *   outbox  writes that have not been acknowledged, FIFO by seq.
 *
 * Nothing here understands products or sales. Keeping it schemaless means a new
 * field on a row needs no migration of the till — which matters, because a till
 * is updated far less often than the dashboard.
 *
 * Raw SQLiteOpenHelper rather than Room: no annotation processor, no new Gradle
 * plugin, nothing to go wrong in a build that cannot be exercised on a device
 * before it ships.
 */
class LocalStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "swipe_local.db"
        private const val DB_VERSION = 1

        /**
         * How many times a queued write is retried before it is set aside.
         *
         * A write that keeps failing on a transport error is worth retrying for
         * a long time — the shop may be offline all afternoon. This only guards
         * against a poison entry spinning forever; a request the server refuses
         * on its merits is dropped immediately instead (see [Outbox]).
         */
        const val MAX_ATTEMPTS = 50
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE rows (
              table_name TEXT NOT NULL,
              id         TEXT NOT NULL,
              json       TEXT NOT NULL,
              updated_at TEXT,
              PRIMARY KEY (table_name, id)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX ix_rows_table ON rows (table_name)")
        db.execSQL(
            """
            CREATE TABLE outbox (
              seq        INTEGER PRIMARY KEY AUTOINCREMENT,
              payload    TEXT NOT NULL,
              label      TEXT,
              created_at TEXT NOT NULL,
              attempts   INTEGER NOT NULL DEFAULT 0,
              last_error TEXT
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // The mirror is a cache and the outbox is short-lived. Rebuilding is
        // safe as long as the outbox is empty; if it is not, the queue is
        // carried across, because those are sales that have not reached the
        // server yet and must not be thrown away by an app update.
        val pending = readOutbox(db)
        db.execSQL("DROP TABLE IF EXISTS rows")
        db.execSQL("DROP TABLE IF EXISTS outbox")
        onCreate(db)
        for (entry in pending) {
            db.insert("outbox", null, ContentValues().apply {
                put("payload", entry.payload)
                put("label", entry.label)
                put("created_at", entry.createdAt)
                put("attempts", entry.attempts)
            })
        }
    }

    // ── Row mirror ──────────────────────────────────────────────────────────

    /** Replace the mirrored copy of one table. */
    fun putAll(table: String, rows: List<JSONObject>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("rows", "table_name = ?", arrayOf(table))
            for (row in rows) {
                val id = row.optString("id").ifEmpty { continue }
                db.insertWithOnConflict(
                    "rows", null,
                    ContentValues().apply {
                        put("table_name", table)
                        put("id", id)
                        put("json", row.toString())
                        put("updated_at", row.optString("updated_at", null))
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Insert or replace a single mirrored row — used for optimistic writes. */
    fun put(table: String, row: JSONObject) {
        val id = row.optString("id").ifEmpty { return }
        writableDatabase.insertWithOnConflict(
            "rows", null,
            ContentValues().apply {
                put("table_name", table)
                put("id", id)
                put("json", row.toString())
                put("updated_at", row.optString("updated_at", null))
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun get(table: String, id: String): JSONObject? =
        readableDatabase.query(
            "rows", arrayOf("json"), "table_name = ? AND id = ?",
            arrayOf(table, id), null, null, null,
        ).use { c -> if (c.moveToFirst()) JSONObject(c.getString(0)) else null }

    fun getAll(table: String): List<JSONObject> =
        readableDatabase.query(
            "rows", arrayOf("json"), "table_name = ?", arrayOf(table), null, null, "id ASC",
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(JSONObject(c.getString(0)))
            }
        }

    fun delete(table: String, id: String) {
        writableDatabase.delete("rows", "table_name = ? AND id = ?", arrayOf(table, id))
    }

    fun clearMirror() {
        writableDatabase.delete("rows", null, null)
    }

    // ── Outbox ──────────────────────────────────────────────────────────────

    data class Entry(
        val seq: Long,
        val payload: String,
        val label: String?,
        val createdAt: String,
        val attempts: Int,
    )

    /** Queue one write. `label` is what the cashier is told if it never lands. */
    fun enqueue(payload: JSONObject, label: String?, nowIso: String): Long =
        writableDatabase.insert("outbox", null, ContentValues().apply {
            put("payload", payload.toString())
            put("label", label)
            put("created_at", nowIso)
            put("attempts", 0)
        })

    fun pending(limit: Int = 100): List<Entry> = readOutbox(readableDatabase, limit)

    private fun readOutbox(db: SQLiteDatabase, limit: Int = 1000): List<Entry> =
        db.query(
            "outbox", arrayOf("seq", "payload", "label", "created_at", "attempts"),
            "attempts < ?", arrayOf(MAX_ATTEMPTS.toString()),
            null, null, "seq ASC", limit.toString(),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Entry(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4)))
                }
            }
        }

    fun acknowledge(seq: Long) {
        writableDatabase.delete("outbox", "seq = ?", arrayOf(seq.toString()))
    }

    fun recordFailure(seq: Long, error: String?) {
        writableDatabase.execSQL(
            "UPDATE outbox SET attempts = attempts + 1, last_error = ? WHERE seq = ?",
            arrayOf(error, seq.toString()),
        )
    }

    /** How many writes have not reached the server. Drives the syncing banner. */
    fun pendingCount(): Int =
        readableDatabase.rawQuery("SELECT count(*) FROM outbox", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    /** Entries that exhausted their retries and need a human to look at them. */
    fun stuckCount(): Int =
        readableDatabase.rawQuery(
            "SELECT count(*) FROM outbox WHERE attempts >= ?", arrayOf(MAX_ATTEMPTS.toString()),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
}
