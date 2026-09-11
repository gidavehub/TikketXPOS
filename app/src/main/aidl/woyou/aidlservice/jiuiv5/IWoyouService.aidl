/**
 * Woyou/Sunmi on-device printer service (built into Sunmi V1/V2/T-series
 * Android POS terminals and many white-label clones).
 *
 * IMPORTANT: binder transaction codes are assigned by declaration order, so
 * the order below mirrors the vendor interface exactly. Never reorder or
 * insert methods — append-only, and only if the vendor interface has them.
 */
package woyou.aidlservice.jiuiv5;

import woyou.aidlservice.jiuiv5.ICallback;
import android.graphics.Bitmap;

interface IWoyouService {

    /** 1. Update printer firmware (unused here). */
    void updateFirmware();

    /** 2. Firmware status. */
    int getFirmwareStatus();

    /** 3. WoyouService version. */
    String getServiceVersion();

    /** 4. Reset the printer's logic (clears style/alignment, keeps buffer). */
    void printerInit(in ICallback callback);

    /** 5. Printer self check. */
    void printerSelfChecking(in ICallback callback);

    /** 6. Board serial number. */
    String getPrinterSerialNo();

    /** 7. Printer firmware version. */
    String getPrinterVersion();

    /** 8. Printer model. */
    String getPrinterModal();

    /** 9. Printed length since power-on. */
    void getPrintedLength(in ICallback callback);

    /** 10. Feed n lines. */
    void lineWrap(int n, in ICallback callback);

    /** 11. Raw ESC/POS bytes. */
    void sendRAWData(in byte[] data, in ICallback callback);

    /** 12. Alignment: 0 left, 1 center, 2 right. */
    void setAlignment(int alignment, in ICallback callback);

    /** 13. Custom font by name. */
    void setFontName(String typeface, in ICallback callback);

    /** 14. Font size. */
    void setFontSize(float fontsize, in ICallback callback);

    /** 15. Print text (append \n to flush). */
    void printText(String text, in ICallback callback);

    /** 16. Print text with font + size. */
    void printTextWithFont(String text, String typeface, float fontsize, in ICallback callback);

    /** 17. Print a table row. */
    void printColumnsText(in String[] colsTextArr, in int[] colsWidthArr, in int[] colsAlign, in ICallback callback);

    /** 18. Print a bitmap (max width 384 dots). */
    void printBitmap(in Bitmap bitmap, in ICallback callback);

    /** 19. Print a 1D barcode. */
    void printBarCode(String data, int symbology, int height, int width, int textposition, in ICallback callback);

    /** 20. Print a QR code. */
    void printQRCode(String data, int modulesize, int errorlevel, in ICallback callback);

    /** 21. Print text exactly as given (no width auto-wrap tricks). */
    void printOriginalText(String text, in ICallback callback);

    /** 22. Commit the print buffer. */
    void commitPrinterBuffer();

    /** 23. Cut paper (devices with a cutter). */
    void cutPaper(in ICallback callback);

    /** 24. Open the cash drawer. */
    void openDrawer(in ICallback callback);
}
