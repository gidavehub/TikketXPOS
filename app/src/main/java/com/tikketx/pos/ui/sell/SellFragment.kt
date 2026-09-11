package com.tikketx.pos.ui.sell

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.tikketx.pos.R
import com.tikketx.pos.data.CartManager
import com.tikketx.pos.data.SessionManager
import com.tikketx.pos.data.SwipeBackend
import com.tikketx.pos.data.model.Product
import com.tikketx.pos.databinding.FragmentSellBinding
import com.tikketx.pos.printing.ReceiptPrinter
import com.tikketx.pos.ui.common.padWithSystemBars
import com.tikketx.pos.ui.scan.ScannerActivity
import java.util.Locale

/**
 * The till: product grid, search, category filter, barcode scan, running cart
 * bar — over a live view of the same stock the dashboard shows, so a product
 * added on the web appears here without anyone reloading anything.
 */
class SellFragment : Fragment() {

    private var _binding: FragmentSellBinding? = null
    private val binding get() = _binding!!

    private var products: List<Product> = emptyList()
    private var query: String = ""
    private var category: String? = null
    private lateinit var adapter: ProductAdapter
    private var registration: SwipeBackend.ScanRequestSubscription? = null

    private val cartListener: () -> Unit = { if (_binding != null) renderCartBar() }

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val code = result.data?.getStringExtra(ScannerActivity.EXTRA_CODE)
        if (result.resultCode == Activity.RESULT_OK && !code.isNullOrBlank()) {
            onBarcodeScanned(code)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentSellBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        val terminal = SessionManager.terminal ?: return

        binding.header.padWithSystemBars(top = true)

        // Receipts print the moment a sale is confirmed, so the logo has to be
        // in hand before then — fetching it at the counter would stall the
        // first ticket of the day behind a download.
        ReceiptPrinter.prefetchLogo(requireContext())

        adapter = ProductAdapter { product -> addToCart(product) }
        binding.productGrid.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.productGrid.adapter = adapter

        binding.inputSearch.doOnTextChanged { text, _, _, _ ->
            query = text?.toString()?.trim().orEmpty()
            applyFilter()
        }

        binding.btnScan.setOnClickListener {
            scanLauncher.launch(ScannerActivity.intent(requireContext()))
        }

        binding.btnReturns.visibility =
            if (terminal.permissions.allowRefunds) View.VISIBLE else View.GONE
        binding.btnReturns.setOnClickListener {
            ReturnsSheet().show(parentFragmentManager, "returns")
        }

        binding.cartBar.setOnClickListener {
            if (!CartManager.isEmpty) {
                CheckoutSheet().show(parentFragmentManager, "checkout")
            }
        }

        CartManager.addListener(cartListener)
        renderCartBar()

        registration = SwipeBackend.listenProducts(
            terminal.companyId,
            onChange = { list ->
                products = list
                if (_binding != null) {
                    binding.progress.visibility = View.GONE
                    buildCategoryChips()
                    applyFilter()
                }
            },
            onError = { e ->
                if (_binding != null) {
                    binding.progress.visibility = View.GONE
                    // Denied reads come back as an empty result set, which on
                    // screen is indistinguishable from a shop with no stock.
                    binding.emptyState.text = SwipeBackend.describeError(e)
                    binding.emptyState.visibility = View.VISIBLE
                }
            },
        )
    }

    private fun addToCart(product: Product) {
        if (product.isOutOfStock) {
            Snackbar.make(binding.root, "“${product.name}” is out of stock.", Snackbar.LENGTH_SHORT).show()
            return
        }
        if (!CartManager.add(product)) {
            Snackbar.make(
                binding.root,
                getString(R.string.only_n_in_stock, SwipeBackend.fmtQty(product.currentStock)),
                Snackbar.LENGTH_SHORT,
            ).show()
        }
    }

    private fun buildCategoryChips() {
        val categories = products.mapNotNull { it.category?.takeIf { c -> c.isNotBlank() } }
            .distinct()
            .sortedBy { it.lowercase(Locale.ROOT) }

        // One category is the same as none — don't spend a row on a filter that
        // can't narrow anything.
        if (categories.size < 2) {
            binding.categoryScroll.visibility = View.GONE
            category = null
            return
        }
        binding.categoryScroll.visibility = View.VISIBLE

        // Rebuilding on every snapshot would drop the cashier's selection, so
        // leave the chips alone unless the set of categories itself changed.
        val existing = (0 until binding.categoryChips.childCount)
            .mapNotNull { (binding.categoryChips.getChildAt(it) as? Chip)?.tag as? String }
        if (existing == listOf("") + categories) return

        binding.categoryChips.removeAllViews()
        for ((index, label) in (listOf(getString(R.string.all_categories)) + categories).withIndex()) {
            val value = if (index == 0) "" else categories[index - 1]
            val chip = Chip(requireContext()).apply {
                text = label
                tag = value
                isCheckable = true
                isChecked = (category ?: "") == value
                setOnClickListener {
                    category = value.takeIf { v -> v.isNotEmpty() }
                    applyFilter()
                }
            }
            binding.categoryChips.addView(chip)
        }
        if (binding.categoryChips.checkedChipId == View.NO_ID) {
            (binding.categoryChips.getChildAt(0) as? Chip)?.isChecked = true
        }
    }

    private fun applyFilter() {
        val q = query.lowercase(Locale.ROOT)
        val filtered = products.filter { product ->
            if (category != null && product.category != category) return@filter false
            if (q.isEmpty()) return@filter true
            product.name.lowercase(Locale.ROOT).contains(q) ||
                product.sku?.lowercase(Locale.ROOT)?.contains(q) == true
        }
        adapter.submit(filtered)

        if (filtered.isEmpty()) {
            binding.emptyState.setText(R.string.no_products)
            binding.emptyState.visibility = View.VISIBLE
        } else {
            binding.emptyState.visibility = View.GONE
        }
    }

    /**
     * Matched against the live product list rather than queried, so a scan is
     * instant and still works while the connection is patchy.
     */
    private fun onBarcodeScanned(code: String) {
        val product = SwipeBackend.matchBarcode(products, code)
        if (product != null) {
            addToCart(product)
            Snackbar.make(binding.root, "Added ${product.name}", Snackbar.LENGTH_SHORT).show()
        } else {
            Snackbar.make(binding.root, "No product with barcode ${code.trim()}", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun renderCartBar() {
        val count = CartManager.itemCount
        if (count == 0) {
            binding.cartBar.visibility = View.GONE
        } else {
            binding.cartBar.visibility = View.VISIBLE
            binding.cartCount.text = count.toString()
            binding.cartTotal.text = SwipeBackend.fmtMoney(CartManager.total)
        }
    }

    override fun onDestroyView() {
        CartManager.removeListener(cartListener)
        registration?.remove()
        registration = null
        _binding = null
        super.onDestroyView()
    }
}
