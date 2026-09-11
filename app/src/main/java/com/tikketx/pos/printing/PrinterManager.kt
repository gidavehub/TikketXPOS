package com.tikketx.pos.printing

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.IBinder
import android.os.RemoteException
import androidx.print.PrintHelper
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import woyou.aidlservice.jiuiv5.ICallback
import woyou.aidlservice.jiuiv5.IWoyouService

/**
 * On-device printing.
 *
 * Primary path: the native AIDL printer service that ships on Sunmi/Woyou
 * Android POS terminals (package `woyou.aidlservice.jiuiv5`). We bind once at
 * startup and talk straight to the thermal head — receipt bitmaps, feeds,
 * cuts, cash drawer.
 *
 * Fallback path: when the device has no built-in printer (a normal phone or
 * tablet), the rendered receipt goes through the Android print framework, so
 * any paired printer / PDF target still works.
 */
object PrinterManager {

    private const val SERVICE_PACKAGE = "woyou.aidlservice.jiuiv5"
    private const val SERVICE_ACTION = "woyou.aidlservice.jiuiv5.IWoyouService"

    private var appContext: Context? = null
    private var service: IWoyouService? = null
    private var bound = false

    val hasNativePrinter: Boolean get() = service != null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IWoyouService.Stub.asInterface(binder)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        bind()
    }

    fun bind() {
        val ctx = appContext ?: return
        if (bound && service != null) return
        try {
            val intent = Intent().apply {
                setPackage(SERVICE_PACKAGE)
                action = SERVICE_ACTION
            }
            bound = ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (_: Exception) {
            bound = false
        }
    }

    private val silentCallback = object : ICallback.Stub() {
        override fun onRunResult(isSuccess: Boolean) {}
        override fun onReturnString(result: String?) {}
        override fun onRaiseException(code: Int, msg: String?) {}
        override fun onPrintResult(code: Int, msg: String?) {}
    }

    /**
     * Pushes the receipt at the built-in thermal head, or reports that it
     * cannot.
     *
     * Returns [Outcome.NATIVE] once the job is on the head, or null when there
     * is no head bound or it refused the job — either way the caller should
     * fall back to [printFallback].
     *
     * These are synchronous binder transactions carrying a bitmap, so this must
     * not be called on the main thread: on a slow head it would hold up the
     * frame that dismisses checkout, which is the one frame that has to be
     * quick.
     */
    fun printNative(bitmap: Bitmap): Outcome? {
        val svc = service ?: return null
        return try {
            svc.printerInit(silentCallback)
            svc.setAlignment(1, silentCallback)
            svc.printBitmap(bitmap, silentCallback)
            svc.lineWrap(4, silentCallback)
            try { svc.cutPaper(silentCallback) } catch (_: RemoteException) { /* no cutter */ }
            svc.commitPrinterBuffer()
            Outcome.NATIVE
        } catch (_: RemoteException) {
            null
        }
    }

    /**
     * Hands the receipt to the Android print framework, for devices with no
     * thermal head.
     *
     * Main thread only, and with an Activity context — this opens the system
     * print dialog, which needs a human.
     */
    fun printFallback(context: Context, bitmap: Bitmap, jobName: String): Outcome {
        PrintHelper(context).apply {
            scaleMode = PrintHelper.SCALE_MODE_FIT
        }.printBitmap(jobName, bitmap)
        return Outcome.FALLBACK
    }

    /** Pops the cash drawer on devices that have one. */
    fun openDrawer() {
        try { service?.openDrawer(silentCallback) } catch (_: RemoteException) {}
    }

    /** What actually happened, so the UI can say something true. */
    enum class Outcome {
        /** Printed on the built-in thermal head. */
        NATIVE,
        /** No built-in printer — handed to the Android print dialog. */
        FALLBACK,
        /** The thermal head is present but rejected the job. */
        FAILED,
    }

    /**
     * Waits for the AIDL service to finish binding.
     *
     * `bindService` is asynchronous: it returns immediately and the binder
     * arrives later on `onServiceConnected`. Calling `bind()` and then printing
     * on the next line — which is what the Settings screen did — races that
     * callback and sees a null service on a device that has a perfectly good
     * printer. Returns true once connected, false if it never arrives.
     */
    suspend fun awaitBound(timeoutMs: Long = 2_000): Boolean {
        if (service != null) return true
        bind()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (service == null && System.currentTimeMillis() < deadline) {
            delay(50)
        }
        return service != null
    }

    /**
     * Test print, used by the button in Settings.
     *
     * Previously this returned false the moment no thermal head was bound,
     * which meant the button did nothing whatsoever on an ordinary phone or
     * tablet and reported "printer not found" — even though the receipt path had
     * a working fallback all along. It now takes the same fallback, so the
     * button proves the print path on every device.
     */
    suspend fun testPrint(context: Context): Outcome {
        if (awaitBound()) {
            val svc = service
            if (svc != null) {
                try {
                    svc.printerInit(silentCallback)
                    svc.setAlignment(1, silentCallback)
                    svc.setFontSize(32f, silentCallback)
                    svc.printText("TikketX POS\n", silentCallback)
                    svc.setFontSize(24f, silentCallback)
                    // Plain ASCII only: thermal heads run a limited code page
                    // and a check mark prints as a stray glyph or nothing.
                    svc.printText("Printer OK\n", silentCallback)
                    svc.printText("--------------------------------\n", silentCallback)
                    svc.lineWrap(3, silentCallback)
                    try { svc.cutPaper(silentCallback) } catch (_: RemoteException) { /* no cutter */ }
                    svc.commitPrinterBuffer()
                    return Outcome.NATIVE
                } catch (_: RemoteException) {
                    return Outcome.FAILED
                }
            }
        }

        PrintHelper(context).apply {
            scaleMode = PrintHelper.SCALE_MODE_FIT
        }.printBitmap("TikketX POS test print", testBitmap())
        return Outcome.FALLBACK
    }

    /** A small receipt-shaped page, for the fallback print path. */
    private fun testBitmap(): Bitmap {
        val width = 384 // 58mm thermal width in dots, the usual receipt raster
        val height = 260
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
        }

        paint.textSize = 34f
        paint.isFakeBoldText = true
        canvas.drawText("TikketX POS", width / 2f, 56f, paint)

        paint.textSize = 24f
        paint.isFakeBoldText = false
        canvas.drawText("Printer OK", width / 2f, 100f, paint)

        paint.textSize = 18f
        canvas.drawText("Test print", width / 2f, 136f, paint)
        canvas.drawText(
            SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.US).format(Date()),
            width / 2f,
            164f,
            paint,
        )

        paint.strokeWidth = 2f
        canvas.drawLine(24f, 192f, width - 24f, 192f, paint)

        paint.textSize = 16f
        canvas.drawText("Powered by Swipe", width / 2f, 224f, paint)

        return bmp
    }
}
