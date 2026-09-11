package com.tikketx.pos.diag

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the last fatal crash so it can be read back off the device.
 *
 * A till in a shop has no logcat attached, so "the app closed itself" is all
 * anyone can report — which is not enough to fix anything. This catches the
 * stack trace on the way down and keeps it in its own preferences file (not the
 * session one, which is wiped on sign-out), for Settings to show afterwards.
 *
 * The default handler is still called: the process must die as Android expects.
 * Suppressing that would leave the app alive in a broken state, which is worse
 * than the crash.
 */
object CrashReporter {

    private const val PREFS = "tikketx_pos_diagnostics"
    private const val KEY_REPORT = "last_crash"

    private lateinit var prefs: SharedPreferences

    fun install(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Never let the recorder itself throw — that would replace a
            // useful stack trace with a useless one.
            runCatching { prefs.edit().putString(KEY_REPORT, format(thread, error)).commit() }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The last recorded crash, or null if the app has not crashed since install. */
    val lastReport: String?
        get() = if (::prefs.isInitialized) prefs.getString(KEY_REPORT, null) else null

    fun clear() {
        if (::prefs.isInitialized) prefs.edit().remove(KEY_REPORT).apply()
    }

    private fun format(thread: Thread, error: Throwable): String {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val when_ = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            appendLine("TikketX POS crash · $when_")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
            appendLine("thread: ${thread.name}")
            appendLine()
            append(stack)
        }
    }
}
