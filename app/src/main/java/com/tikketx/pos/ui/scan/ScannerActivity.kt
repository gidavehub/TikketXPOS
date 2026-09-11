package com.tikketx.pos.ui.scan

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.tikketx.pos.R
import com.tikketx.pos.databinding.ActivityScannerBinding
import com.tikketx.pos.ui.common.drawBehindSystemBars
import com.tikketx.pos.ui.common.marginTopSystemBars
import java.util.concurrent.Executors

/**
 * Full-screen barcode / QR scanner (CameraX + ML Kit, fully on-device).
 * Returns the first decoded value via EXTRA_CODE.
 */
class ScannerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CODE = "code"
        fun intent(context: Context): Intent = Intent(context, ScannerActivity::class.java)
    }

    private lateinit var binding: ActivityScannerBinding
    private val executor = Executors.newSingleThreadExecutor()
    private var returned = false

    private val scanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
                    Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39,
                    Barcode.FORMAT_CODE_93, Barcode.FORMAT_ITF,
                    Barcode.FORMAT_CODABAR, Barcode.FORMAT_QR_CODE,
                    Barcode.FORMAT_DATA_MATRIX,
                )
                .build()
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else {
            Snackbar.make(binding.root, "Camera permission is needed to scan.", Snackbar.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Full-bleed camera view: white icons, close button below the notch.
        drawBehindSystemBars(darkStatusIcons = false, darkNavIcons = false)
        binding.btnClose.marginTopSystemBars()

        binding.btnClose.setOnClickListener { finish() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            // This listener runs on the main thread, so anything thrown here
            // takes the app down rather than failing the scan. Binding is the
            // riskiest call in the app: it throws when the device exposes no
            // back camera, when another app still holds it, and on a handful of
            // vendor camera stacks — all of which are "close the scanner and
            // tell the cashier", never "crash the till".
            try {
                val provider = providerFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build().also {
                        it.setAnalyzer(executor) { proxy -> analyze(proxy) }
                    }

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val mediaImage = proxy.image
        if (mediaImage == null || returned) {
            proxy.close()
            return
        }
        val image = try {
            InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        } catch (e: Exception) {
            // A malformed frame is not worth killing the analyzer thread over;
            // the next one is 30 ms away.
            proxy.close()
            return
        }
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val value = barcodes.firstOrNull()?.rawValue
                if (!value.isNullOrBlank() && !returned) {
                    returned = true
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_CODE, value))
                    finish()
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
