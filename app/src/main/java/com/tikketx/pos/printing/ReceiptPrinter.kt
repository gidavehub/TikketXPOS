package com.tikketx.pos.printing

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import com.bumptech.glide.Glide
import com.tikketx.pos.data.SessionManager
import com.tikketx.pos.data.SwipeBackend
import com.tikketx.pos.data.model.CompletedSale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Renders a finished sale and sends it straight to paper.
 *
 * The receipt used to arrive as a full-screen preview with a Print button on
 * it, which put a second decision and a second tap between the cashier and the
 * ticket the customer is standing there waiting for. Charge is now the last
 * word on the sale, so by the time we reach this object the answer is already
 * yes — there is nothing left to preview and nothing left to ask.
 *
 * Work runs on this object's own scope rather than the checkout sheet's,
 * because the sheet is dismissed the moment the sale lands: a fragment-scoped
 * coroutine would be cancelled halfway through rendering.
 *
 * Nothing in the print path waits on the network. The logo is the only piece
 * that ever came off it, and a ticket prints without one rather than holding
 * the counter for one.
 */
object ReceiptPrinter {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * The business logo, kept between sales.
     *
     * Downloading it per receipt would stall the print at the counter; it only
     * changes when someone uploads a new one on Swipe, so it is fetched once
     * per session and reused.
     */
    @Volatile
    private var logo: Bitmap? = null

    /** Company the cached [logo] belongs to — null until one has been fetched. */
    @Volatile
    private var logoCompanyId: String? = null

    /**
     * Warms the logo cache.
     *
     * Called when the till opens, so the first receipt of the day prints as
     * quickly as every one after it.
     */
    fun prefetchLogo(context: Context) {
        val companyId = SessionManager.terminal?.companyId ?: return
        if (logoCompanyId == companyId) return
        val app = context.applicationContext
        scope.launch { cacheLogo(app, companyId) }
    }

    /** Drops the cached logo, so the next sale re-fetches it for the new shop. */
    fun forgetLogo() {
        logo = null
        logoCompanyId = null
    }

    private suspend fun cacheLogo(context: Context, companyId: String) {
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val url = SwipeBackend.getCompanyProfile(companyId)?.logoUrl
                if (url.isNullOrBlank()) null
                else Glide.with(context).asBitmap().load(url).submit().get()
            }.getOrNull()
        }
        logo = bitmap
        // Stamped even when the fetch came back empty: a shop with no logo
        // uploaded shouldn't retry the round trip on every single sale.
        logoCompanyId = companyId
    }

    /**
     * Prints [sale] in the ticket style chosen in Settings and reports what
     * actually happened, so the caller can say something true rather than a
     * blanket "printed".
     *
     * [onResult] is delivered on the main thread and may arrive after the
     * checkout sheet has gone, so callers should anchor any message to the
     * activity rather than to a fragment view.
     */
    fun print(
        activity: Activity,
        sale: CompletedSale,
        onResult: (PrinterManager.Outcome) -> Unit,
    ) {
        // Read once, here, rather than inside the coroutine: whatever logo is
        // in hand at the tap is the one this ticket gets.
        val logoNow = logo
        val style = SessionManager.ticketStyle
        val appContext = activity.applicationContext
        val companyId = SessionManager.terminal?.companyId

        scope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                ReceiptRenderer.render(sale, style, logoNow)
            }

            // The thermal head first, off the main thread — these are binder
            // calls carrying a bitmap. Only the framework fallback comes back
            // to the main thread, because it opens the system print dialog and
            // needs the activity to do it.
            val native = withContext(Dispatchers.IO) { PrinterManager.printNative(bitmap) }
            if (native != null) {
                onResult(native)
            } else if (!activity.isFinishing && !activity.isDestroyed) {
                onResult(PrinterManager.printFallback(activity, bitmap, sale.saleNumber))
            }

            // Only now, with the paper already moving, is it worth going after
            // a logo we do not have. Fetching it *before* the print — which is
            // what used to happen on the first sale after opening, or any time
            // the prefetch had not landed — put a Firestore read and an image
            // download between the tap and the ticket, and with no signal the
            // Glide fetch has no timeout to lapse: the receipt simply never
            // printed.
            if (companyId != null && logoCompanyId != companyId) {
                cacheLogo(appContext, companyId)
            }
        }
    }
}
