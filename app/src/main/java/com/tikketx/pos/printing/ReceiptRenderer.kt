package com.tikketx.pos.printing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.tikketx.pos.data.model.CompletedSale
import com.tikketx.pos.data.model.PaymentMethod
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Draws the receipt/ticket as a 384-dot-wide bitmap (58 mm thermal head),
 * optionally topped with the business logo uploaded to Swipe.
 *
 * Styles:
 *  - classic : bold header, itemised list, dashed rules
 *  - compact : tight, no frills, saves paper
 *  - ticket  : stub look with a QR of the sale number for lookups/returns
 */
object ReceiptRenderer {

    const val WIDTH = 384
    private const val MARGIN = 12f

    private fun paint(size: Float, bold: Boolean = false, center: Boolean = false): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = size
            typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            else Typeface.MONOSPACE
            textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
        }

    private fun money(v: Double): String = "D%,.2f".format(v)

    fun render(sale: CompletedSale, style: String, logo: Bitmap?): Bitmap {
        // Draw on a tall scratch canvas, then crop to the used height.
        val scratch = Bitmap.createBitmap(WIDTH, 2400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scratch)
        canvas.drawColor(Color.WHITE)
        var y = 16f

        val center = WIDTH / 2f
        val compact = style == "compact"

        // ── Header: logo + business name ─────────────────────────────
        if (logo != null && !compact) {
            val maxW = 240
            val scale = maxW.toFloat() / logo.width
            val h = (logo.height * scale).toInt().coerceAtMost(120)
            val scaled = Bitmap.createScaledBitmap(logo, maxW, h, true)
            canvas.drawBitmap(scaled, center - maxW / 2f, y, null)
            y += h + 12f
        }

        val title = paint(if (compact) 26f else 32f, bold = true, center = true)
        canvas.drawText(sale.companyName, center, y + title.textSize, title)
        y += title.textSize + 10f

        val meta = paint(20f, center = true)
        val time = try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            parser.timeZone = java.util.TimeZone.getTimeZone("UTC")
            SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.US).format(parser.parse(sale.createdAt) ?: Date())
        } catch (_: Exception) {
            SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.US).format(Date())
        }
        canvas.drawText(time, center, y + meta.textSize, meta)
        y += meta.textSize + 6f
        canvas.drawText("${sale.saleNumber} · ${sale.cashierName}", center, y + meta.textSize, meta)
        y += meta.textSize + 12f

        y = dashedRule(canvas, y)

        // ── Items ────────────────────────────────────────────────────
        val itemPaint = paint(22f)
        val pricePaint = paint(22f).apply { textAlign = Paint.Align.RIGHT }
        for (item in sale.items) {
            val qty = if (item.quantity % 1.0 == 0.0) item.quantity.toInt().toString()
            else "%.2f".format(item.quantity)
            val name = item.product.name.take(20)
            canvas.drawText("$qty× $name", MARGIN, y + itemPaint.textSize, itemPaint)
            canvas.drawText(money(item.lineTotal), WIDTH - MARGIN, y + itemPaint.textSize, pricePaint)
            y += itemPaint.textSize + (if (compact) 4f else 8f)
        }

        y += 6f
        y = dashedRule(canvas, y)

        // ── Totals ───────────────────────────────────────────────────
        val small = paint(20f)
        val smallRight = paint(20f).apply { textAlign = Paint.Align.RIGHT }

        // Subtotal only earns its line when something moves the total off it —
        // otherwise it just repeats TOTAL a line above.
        if (sale.discountTotal > 0.0 || sale.taxAmount > 0.0) {
            canvas.drawText("Subtotal", MARGIN, y + small.textSize, small)
            canvas.drawText(money(sale.subtotal), WIDTH - MARGIN, y + small.textSize, smallRight)
            y += small.textSize + 4f
        }
        if (sale.discountTotal > 0.0) {
            canvas.drawText("Discount", MARGIN, y + small.textSize, small)
            canvas.drawText("-" + money(sale.discountTotal), WIDTH - MARGIN, y + small.textSize, smallRight)
            y += small.textSize + 4f
        }
        if (sale.taxAmount > 0.0) {
            canvas.drawText((sale.taxLabel ?: "Tax").take(22), MARGIN, y + small.textSize, small)
            canvas.drawText(money(sale.taxAmount), WIDTH - MARGIN, y + small.textSize, smallRight)
            y += small.textSize + 4f
        }

        val totalPaint = paint(30f, bold = true)
        val totalRight = paint(30f, bold = true).apply { textAlign = Paint.Align.RIGHT }
        canvas.drawText("TOTAL", MARGIN, y + totalPaint.textSize, totalPaint)
        canvas.drawText(money(sale.total), WIDTH - MARGIN, y + totalPaint.textSize, totalRight)
        y += totalPaint.textSize + 10f

        canvas.drawText(sale.paymentMethod.label.uppercase(), MARGIN, y + small.textSize, small)
        canvas.drawText(money(sale.amountTendered), WIDTH - MARGIN, y + small.textSize, smallRight)
        y += small.textSize + 4f
        if (sale.changeGiven > 0) {
            canvas.drawText("CHANGE", MARGIN, y + small.textSize, small)
            canvas.drawText(money(sale.changeGiven), WIDTH - MARGIN, y + small.textSize, smallRight)
            y += small.textSize + 4f
        }
        sale.customerName?.takeIf { it.isNotBlank() }?.let {
            canvas.drawText("Customer: $it", MARGIN, y + small.textSize + 4f, small)
            y += small.textSize + 8f
        }
        // A credit sale settles later, so the slip has to say what is still owed
        // — it doubles as the customer's record of the debt.
        if (sale.paymentMethod == PaymentMethod.CREDIT) {
            val owing = paint(22f, bold = true)
            val owingRight = paint(22f, bold = true).apply { textAlign = Paint.Align.RIGHT }
            canvas.drawText("BALANCE DUE", MARGIN, y + owing.textSize + 4f, owing)
            canvas.drawText(money(sale.total), WIDTH - MARGIN, y + owing.textSize + 4f, owingRight)
            y += owing.textSize + 10f
        }

        // ── Ticket style: QR stub ────────────────────────────────────
        if (style == "ticket") {
            y += 8f
            y = dashedRule(canvas, y)
            qrBitmap(sale.saleNumber, 140)?.let { qr ->
                canvas.drawBitmap(qr, center - qr.width / 2f, y + 4f, null)
                y += qr.height + 10f
            }
            val hint = paint(18f, center = true)
            canvas.drawText("Scan for returns & lookups", center, y + hint.textSize, hint)
            y += hint.textSize + 6f
        }

        // ── Footer ───────────────────────────────────────────────────
        y += 10f
        val foot = paint(18f, center = true)
        canvas.drawText("Thank you!", center, y + foot.textSize, foot)
        y += foot.textSize + 4f
        canvas.drawText("Powered by TikketX × Swipe", center, y + foot.textSize, foot)
        y += foot.textSize + 24f

        return Bitmap.createBitmap(scratch, 0, 0, WIDTH, y.toInt().coerceAtMost(scratch.height))
    }

    private fun dashedRule(canvas: Canvas, y: Float): Float {
        val p = Paint().apply {
            color = Color.BLACK
            strokeWidth = 2f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
        }
        canvas.drawLine(MARGIN, y + 6f, WIDTH - MARGIN, y + 6f, p)
        return y + 18f
    }

    /**
     * Built through a pixel array rather than [Bitmap.setPixel]: at 140 dots
     * square that is one bitmap write instead of nearly twenty thousand, each
     * of which crosses into native code. It is the only part of drawing a
     * receipt that was ever slow enough to notice.
     */
    private fun qrBitmap(content: String, size: Int): Bitmap? = try {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size)
        for (yy in 0 until size) {
            val row = yy * size
            for (x in 0 until size) {
                pixels[row + x] = if (matrix.get(x, yy)) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    } catch (_: Exception) {
        null
    }
}
