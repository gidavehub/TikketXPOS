package com.tikketx.pos.ui.inventory

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.tikketx.pos.R
import com.tikketx.pos.data.SessionManager
import com.tikketx.pos.data.SwipeBackend
import com.tikketx.pos.data.model.Product
import com.tikketx.pos.databinding.SheetAddProductBinding
import com.tikketx.pos.ui.scan.ScannerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Create or edit a product, straight into the shared catalogue — name, prices,
 * barcode (scan it in), category and a photo pushed to Cloud Storage. Whatever
 * is saved here is on the Swipe Inventory page the moment it lands.
 *
 * Editing deliberately leaves the stock count alone: an existing count only
 * moves through a stock movement, so that the ledger always explains it. Use
 * "Add stock" or "Adjust count" on the product for that.
 */
class ProductSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_PRODUCT = "product"

        fun forNew(): ProductSheet = ProductSheet()

        fun forEdit(product: Product): ProductSheet = ProductSheet().apply {
            arguments = Bundle().apply { putSerializable(ARG_PRODUCT, product) }
        }
    }

    private var _binding: SheetAddProductBinding? = null
    private val binding get() = _binding!!

    private var imageUri: Uri? = null

    @Suppress("DEPRECATION")
    private val editing: Product? by lazy {
        arguments?.getSerializable(ARG_PRODUCT) as? Product
    }

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val code = result.data?.getStringExtra(ScannerActivity.EXTRA_CODE)
        if (result.resultCode == Activity.RESULT_OK && !code.isNullOrBlank()) {
            binding.inputSku.setText(code)
        }
    }

    /** Where the camera writes, held so the result callback can read it back. */
    private var pendingCameraUri: Uri? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) showPickedPhoto(uri)
    }

    private val takePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { saved ->
        val uri = pendingCameraUri
        if (saved && uri != null) showPickedPhoto(uri) else pendingCameraUri = null
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
        else Snackbar.make(
            binding.root,
            getString(R.string.camera_permission_needed),
            Snackbar.LENGTH_LONG,
        ).show()
    }

    private fun showPickedPhoto(uri: Uri) {
        imageUri = uri
        binding.productPhoto.setImageURI(uri)
        binding.btnPickPhoto.text = getString(R.string.change_photo)
    }

    /**
     * Asks whether to shoot a new photo or pick an existing one.
     *
     * Stock is usually added with the product in hand, so going through the
     * gallery meant leaving the app, taking a photo, and coming back. The
     * camera is the more common intent of the two.
     */
    private fun choosePhotoSource() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_photo_title)
            .setItems(
                arrayOf(getString(R.string.photo_take), getString(R.string.photo_choose))
            ) { _, which ->
                if (which == 0) requestCamera() else pickImageLauncher.launch("image/*")
            }
            .show()
    }

    private fun requestCamera() {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) launchCamera()
        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun launchCamera() {
        try {
            // Cache subdirectory, matching res/xml/file_paths.xml — the
            // provider only exposes this one folder.
            val dir = File(requireContext().cacheDir, "product_photos").apply { mkdirs() }
            val file = File(dir, "product_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file,
            )
            pendingCameraUri = uri
            takePhotoLauncher.launch(uri)
        } catch (e: Exception) {
            Snackbar.make(
                binding.root,
                e.message ?: getString(R.string.camera_unavailable),
                Snackbar.LENGTH_LONG,
            ).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = SheetAddProductBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        binding.btnScanSku.setOnClickListener {
            scanLauncher.launch(ScannerActivity.intent(requireContext()))
        }
        binding.btnPickPhoto.setOnClickListener { choosePhotoSource() }
        binding.btnSave.setOnClickListener { save() }

        val product = editing
        if (product == null) {
            binding.inputUnit.setText("pcs")
            binding.inputLowStock.setText("5")
            return
        }

        binding.sheetTitle.setText(R.string.edit_product)
        binding.btnSave.setText(R.string.save_changes)
        binding.stockLayout.visibility = View.GONE

        binding.inputName.setText(product.name)
        binding.inputPrice.setText(SwipeBackend.trimNumber(product.unitPrice))
        binding.inputCost.setText(SwipeBackend.trimNumber(product.costPrice))
        binding.inputLowStock.setText(SwipeBackend.trimNumber(product.lowStockThreshold))
        binding.inputUnit.setText(product.unit)
        binding.inputSku.setText(product.sku.orEmpty())
        binding.inputCategory.setText(product.category.orEmpty())
        binding.inputDescription.setText(product.description.orEmpty())
        if (!product.imageUrl.isNullOrBlank()) {
            binding.btnPickPhoto.text = getString(R.string.change_photo)
        }
    }

    private fun save() {
        val terminal = SessionManager.terminal ?: return
        if (!terminal.permissions.addInventory) {
            Snackbar.make(binding.root, "This terminal can't add stock.", Snackbar.LENGTH_LONG).show()
            return
        }

        val name = binding.inputName.text?.toString()?.trim().orEmpty()
        val price = binding.inputPrice.text?.toString()?.toDoubleOrNull()
        if (name.isEmpty() || price == null) {
            Snackbar.make(binding.root, "Name and selling price are required.", Snackbar.LENGTH_LONG).show()
            return
        }
        val cost = binding.inputCost.text?.toString()?.toDoubleOrNull() ?: 0.0
        val stock = binding.inputStock.text?.toString()?.toDoubleOrNull() ?: 0.0
        val lowStock = binding.inputLowStock.text?.toString()?.toDoubleOrNull() ?: 5.0
        val sku = binding.inputSku.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val category = binding.inputCategory.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val description = binding.inputDescription.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val unit = binding.inputUnit.text?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: "pcs"

        binding.btnSave.isEnabled = false
        binding.progress.visibility = View.VISIBLE

        val product = editing
        lifecycleScope.launch {
            try {
                val imageBytes = withContext(Dispatchers.IO) { compressImage() }
                if (product == null) {
                    SwipeBackend.addProduct(
                        terminal = terminal,
                        name = name,
                        sku = sku,
                        category = category,
                        description = description,
                        unitPrice = price,
                        costPrice = cost,
                        stock = stock,
                        lowStockThreshold = lowStock,
                        unit = unit,
                        imageBytes = imageBytes,
                    )
                } else {
                    SwipeBackend.updateProduct(
                        terminal = terminal,
                        productId = product.id,
                        name = name,
                        sku = sku,
                        category = category,
                        description = description,
                        unitPrice = price,
                        costPrice = cost,
                        lowStockThreshold = lowStock,
                        unit = unit,
                        imageBytes = imageBytes,
                    )
                }
                parentFragment?.view?.let {
                    Snackbar.make(it, getString(R.string.product_saved, name), Snackbar.LENGTH_LONG).show()
                }
                dismissAllowingStateLoss()
            } catch (e: Exception) {
                // Same reason as the checkout sheet: this ran on the fragment's
                // scope, so the view may already be gone by the time it fails.
                val view = _binding ?: return@launch
                Snackbar.make(view.root, SwipeBackend.describeError(e), Snackbar.LENGTH_LONG).show()
                view.btnSave.isEnabled = true
                view.progress.visibility = View.GONE
            }
        }
    }

    /** Downscale + JPEG-compress the picked photo (~≤1280px, 80%). */
    private fun compressImage(): ByteArray? {
        val uri = imageUri ?: return null
        return try {
            @Suppress("DEPRECATION")
            val source = MediaStore.Images.Media.getBitmap(requireContext().contentResolver, uri)
            val maxDim = 1280
            val scale = minOf(1f, maxDim.toFloat() / maxOf(source.width, source.height))
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    source,
                    (source.width * scale).toInt(),
                    (source.height * scale).toInt(),
                    true,
                )
            } else source
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
