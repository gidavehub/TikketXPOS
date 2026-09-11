package com.tikketx.pos.ui.inventory

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.tikketx.pos.R
import com.tikketx.pos.data.SessionManager
import com.tikketx.pos.data.SwipeBackend
import com.tikketx.pos.data.model.Product
import com.tikketx.pos.data.model.StockChange
import com.tikketx.pos.data.model.StockMovementType
import com.tikketx.pos.data.model.Terminal
import com.tikketx.pos.databinding.DialogStockChangeBinding
import com.tikketx.pos.databinding.FragmentInventoryBinding
import com.tikketx.pos.ui.common.padWithSystemBars
import com.tikketx.pos.ui.scan.ScannerActivity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Stock management on the device, against the same `products` rows the Swipe
 * Inventory page edits: search, add, edit, restock, stock-take adjustments and
 * the movement history behind each count.
 *
 * Everything that moves stock goes through [SwipeBackend.applyStockChanges], so
 * a count changed here and a count changed on the web are the same event in the
 * same ledger.
 */
class InventoryFragment : Fragment() {

    private var _binding: FragmentInventoryBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: InventoryAdapter
    private var registration: SwipeBackend.ScanRequestSubscription? = null

    private var products: List<Product> = emptyList()
    private var query: String = ""

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val code = result.data?.getStringExtra(ScannerActivity.EXTRA_CODE)
        if (result.resultCode == Activity.RESULT_OK && !code.isNullOrBlank()) {
            val match = SwipeBackend.matchBarcode(products, code)
            if (match != null) showActions(match)
            else binding.inputSearch.setText(code.trim())
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentInventoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        val terminal = SessionManager.terminal ?: return

        binding.header.padWithSystemBars(top = true)

        adapter = InventoryAdapter { product -> showActions(product) }
        binding.inventoryList.layoutManager = LinearLayoutManager(requireContext())
        binding.inventoryList.adapter = adapter

        binding.inputSearch.doOnTextChanged { text, _, _, _ ->
            query = text?.toString()?.trim().orEmpty()
            render()
        }
        binding.btnScan.setOnClickListener {
            scanLauncher.launch(ScannerActivity.intent(requireContext()))
        }
        binding.fabAdd.setOnClickListener {
            ProductSheet.forNew().show(parentFragmentManager, "add_product")
        }

        registration = SwipeBackend.listenProducts(
            terminal.companyId,
            onChange = { list ->
                products = list
                if (_binding != null) {
                    binding.progress.visibility = View.GONE
                    render()
                }
            },
            onError = { e ->
                if (_binding != null) {
                    binding.progress.visibility = View.GONE
                    // A rules rejection returns nothing, which looks exactly
                    // like an empty shop — say which one it is.
                    binding.emptyState.text = SwipeBackend.describeError(e)
                    binding.emptyState.visibility = View.VISIBLE
                }
            },
        )
    }

    private fun render() {
        val q = query.lowercase(Locale.ROOT)
        val filtered = if (q.isEmpty()) products else products.filter {
            it.name.lowercase(Locale.ROOT).contains(q) ||
                it.sku?.lowercase(Locale.ROOT)?.contains(q) == true ||
                it.category?.lowercase(Locale.ROOT)?.contains(q) == true
        }
        adapter.submit(filtered)

        if (filtered.isEmpty()) {
            binding.emptyState.setText(
                if (products.isEmpty()) R.string.no_inventory else R.string.no_products
            )
            binding.emptyState.visibility = View.VISIBLE
        } else {
            binding.emptyState.visibility = View.GONE
        }

        // What the shelf is worth at cost — the number an owner checks the app for.
        val value = products.sumOf { it.currentStock * it.costPrice }
        binding.headerSubtitle.text = getString(R.string.products_count, products.size) +
            " · " + getString(R.string.stock_value) + " " + SwipeBackend.fmtMoney(value)
    }

    // -----------------------------------------------------------------
    // Per-product actions
    // -----------------------------------------------------------------

    private fun showActions(product: Product) {
        val terminal = SessionManager.terminal ?: return
        val canEdit = terminal.permissions.addInventory

        // A cashier without stock rights can still look, which is useful at the
        // counter ("how many left?") — they just can't change anything.
        val labels = mutableListOf(getString(R.string.action_history))
        val actions = mutableListOf<() -> Unit>({ showHistory(product) })

        if (canEdit) {
            labels.add(0, getString(R.string.action_stock_in))
            actions.add(0) { showStockDialog(terminal, product, StockMovementType.STOCK_IN) }
            labels.add(1, getString(R.string.action_adjust))
            actions.add(1) { showStockDialog(terminal, product, StockMovementType.ADJUSTMENT) }
            labels.add(getString(R.string.action_edit))
            actions.add {
                ProductSheet.forEdit(product).show(parentFragmentManager, "edit_product")
            }
            labels.add(getString(R.string.action_remove))
            actions.add { confirmRemove(terminal, product) }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(product.name)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    /**
     * Stock-in adds what arrived; adjust sets the count to what was physically
     * counted. Both end up in the ledger — adjust as a signed correction, which
     * is why the difference is computed here rather than asked for.
     */
    private fun showStockDialog(terminal: Terminal, product: Product, type: StockMovementType) {
        val view = DialogStockChangeBinding.inflate(layoutInflater)
        val adjusting = type == StockMovementType.ADJUSTMENT

        view.stockCurrent.text = getString(
            R.string.current_stock_is,
            SwipeBackend.fmtQty(product.currentStock),
            product.unit,
        )
        view.quantityLayout.hint =
            getString(if (adjusting) R.string.counted_quantity else R.string.quantity)
        if (adjusting) view.inputQuantity.setText(SwipeBackend.fmtQty(product.currentStock))

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (adjusting) R.string.adjust_title else R.string.stock_in_title)
            .setView(view.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.done) { _, _ ->
                val entered = view.inputQuantity.text?.toString()?.toDoubleOrNull()
                if (entered == null) return@setPositiveButton
                val note = view.inputNote.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }

                val quantity = if (adjusting) entered - product.currentStock else entered
                if (quantity == 0.0) return@setPositiveButton

                applyStock(
                    terminal,
                    StockChange(
                        productId = product.id,
                        type = type,
                        quantity = quantity,
                        unitPrice = product.costPrice,
                        totalAmount = product.costPrice * kotlin.math.abs(quantity),
                        notes = note ?: if (adjusting) {
                            "Stock take (TikketX POS · ${terminal.terminalName})"
                        } else {
                            "Restock (TikketX POS · ${terminal.terminalName})"
                        },
                        reference = null,
                    ),
                    successMessage = if (adjusting) R.string.stock_adjusted else R.string.stock_added,
                )
            }
            .show()
    }

    private fun applyStock(terminal: Terminal, change: StockChange, successMessage: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Adjustments are the one signed movement type, so a stock-take
                // that finds fewer than expected writes a negative delta. The
                // zero clamp never fights it: the new count is what was counted,
                // which is what the cashier typed.
                SwipeBackend.applyStockChanges(terminal, listOf(change))
                if (_binding != null) {
                    Snackbar.make(binding.root, successMessage, Snackbar.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (_binding != null) {
                    Snackbar.make(binding.root, SwipeBackend.describeError(e), Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showHistory(product: Product) {
        val companyId = SessionManager.terminal?.companyId ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val movements = runCatching {
                SwipeBackend.stockHistory(companyId, product.id)
            }.getOrNull()
            if (_binding == null) return@launch

            if (movements.isNullOrEmpty()) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(product.name)
                    .setMessage(R.string.no_movements)
                    .setPositiveButton(R.string.close, null)
                    .show()
                return@launch
            }

            val dayFmt = SimpleDateFormat("dd MMM · HH:mm", Locale.getDefault())
            val lines = movements.map { m ->
                val delta = m.type.delta(m.quantity)
                val sign = if (delta >= 0) "+" else "−"
                val when_ = SwipeBackend.parseIso(m.createdAt)?.let { dayFmt.format(it) } ?: ""
                val label = when (m.type) {
                    StockMovementType.STOCK_IN -> "Stock in"
                    StockMovementType.SALE -> "Sale"
                    StockMovementType.RETURN -> "Return"
                    StockMovementType.ADJUSTMENT -> "Adjustment"
                }
                "$sign${SwipeBackend.fmtQty(kotlin.math.abs(delta))} ${product.unit}  ·  $label\n" +
                    listOfNotNull(when_, m.reference, m.notes).joinToString("  ·  ")
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(product.name)
                .setItems(lines.toTypedArray(), null)
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    private fun confirmRemove(terminal: Terminal, product: Product) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.remove_product_title, product.name))
            .setMessage(R.string.remove_product_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.remove) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        SwipeBackend.deactivateProduct(terminal, product.id)
                        if (_binding != null) {
                            Snackbar.make(
                                binding.root,
                                getString(R.string.product_removed, product.name),
                                Snackbar.LENGTH_SHORT,
                            ).show()
                        }
                    } catch (e: Exception) {
                        if (_binding != null) {
                            Snackbar.make(
                                binding.root,
                                SwipeBackend.describeError(e),
                                Snackbar.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }
            .show()
    }

    override fun onDestroyView() {
        registration?.remove()
        registration = null
        _binding = null
        super.onDestroyView()
    }
}
