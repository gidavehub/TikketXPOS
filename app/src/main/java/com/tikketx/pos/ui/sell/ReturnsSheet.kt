package com.tikketx.pos.ui.sell

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.tikketx.pos.R
import com.tikketx.pos.data.SessionManager
import com.tikketx.pos.data.SwipeBackend
import com.tikketx.pos.data.model.PosSale
import com.tikketx.pos.databinding.DialogReturnBinding
import com.tikketx.pos.databinding.ItemReturnLineBinding
import com.tikketx.pos.databinding.ItemSaleBinding
import com.tikketx.pos.databinding.SheetReturnsBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Recent sales, and the returns flow that hangs off them.
 *
 * A refund needs a sale to refund against, so the list is the way in. Returning
 * puts the goods back through the same stock write path a sale takes out of it
 * and marks the sale `returned`, which is exactly what the dashboard's own
 * returns flow does — the two are the same operation on the same rows.
 */
class ReturnsSheet : BottomSheetDialogFragment() {

    private var _binding: SheetReturnsBinding? = null
    private val binding get() = _binding!!

    private val dateFmt = SimpleDateFormat("dd MMM · HH:mm", Locale.getDefault())
    private val adapter = SaleAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = SheetReturnsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        binding.salesList.layoutManager = LinearLayoutManager(requireContext())
        binding.salesList.adapter = adapter
        loadSales()
    }

    private fun loadSales() {
        val terminal = SessionManager.terminal ?: return
        binding.progress.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val sales = runCatching { SwipeBackend.recentSales(terminal.companyId) }
            if (_binding == null) return@launch
            binding.progress.visibility = View.GONE

            sales.onSuccess { list ->
                adapter.submit(list)
                binding.emptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }.onFailure { e ->
                binding.emptyState.text = SwipeBackend.describeError(e as Exception)
                binding.emptyState.visibility = View.VISIBLE
            }
        }
    }

    private fun openReturn(sale: PosSale) {
        if (sale.isReturned) return
        viewLifecycleOwner.lifecycleScope.launch {
            val items = runCatching { SwipeBackend.saleItems(sale.id) }.getOrNull().orEmpty()
            if (_binding == null) return@launch
            if (items.isEmpty()) {
                Snackbar.make(binding.root, R.string.no_sales_yet, Snackbar.LENGTH_SHORT).show()
                return@launch
            }
            showReturnDialog(sale.copy(items = items))
        }
    }

    private fun showReturnDialog(sale: PosSale) {
        val view = DialogReturnBinding.inflate(layoutInflater)
        val quantities = linkedMapOf<String, Double>()

        fun renderRefund() {
            val refund = sale.items.sumOf { item ->
                (quantities[item.id] ?: 0.0) * item.unitPrice
            }
            view.refundTotal.text =
                getString(R.string.refund_total) + "  −" + SwipeBackend.fmtMoney(refund)
        }

        for (item in sale.items) {
            val row = ItemReturnLineBinding.inflate(layoutInflater, view.returnLines, false)
            row.lineName.text = item.productName
            row.lineMeta.text = "${SwipeBackend.fmtQty(item.quantity)} × " +
                SwipeBackend.fmtMoney(item.unitPrice)
            row.lineQuantity.addTextChangedListener(
                afterChanged = { text ->
                    // Never refund more than was sold: the extra would be stock
                    // that never left the shelf.
                    val entered = (text.toDoubleOrNull() ?: 0.0).coerceIn(0.0, item.quantity)
                    quantities[item.id] = entered
                    renderRefund()
                }
            )
            view.returnLines.addView(row.root)
        }
        renderRefund()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(sale.saleNumber)
            .setView(view.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.return_action) { _, _ ->
                processReturn(sale, quantities, view.inputReason.text?.toString()?.trim())
            }
            .show()
    }

    private fun processReturn(sale: PosSale, quantities: Map<String, Double>, reason: String?) {
        val terminal = SessionManager.terminal ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                SwipeBackend.processReturn(terminal, sale, quantities, reason)
                if (_binding == null) return@launch
                Snackbar.make(binding.root, R.string.return_done, Snackbar.LENGTH_LONG).show()
                loadSales()
            } catch (e: Exception) {
                if (_binding != null) {
                    Snackbar.make(binding.root, SwipeBackend.describeError(e), Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    // -----------------------------------------------------------------

    private inner class SaleAdapter : RecyclerView.Adapter<SaleAdapter.Holder>() {

        private val sales = mutableListOf<PosSale>()

        fun submit(list: List<PosSale>) {
            sales.clear()
            sales.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemSaleBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = sales.size

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(sales[position])

        inner class Holder(private val row: ItemSaleBinding) : RecyclerView.ViewHolder(row.root) {
            fun bind(sale: PosSale) {
                row.saleNumber.text = sale.saleNumber
                row.saleTotal.text = SwipeBackend.fmtMoney(sale.total)
                row.saleMeta.text = listOfNotNull(
                    SwipeBackend.parseIso(sale.createdAt)?.let { dateFmt.format(it) },
                    sale.customerName,
                    sale.paymentMethod.replace('_', ' '),
                ).joinToString(" · ")
                row.saleReturnedBadge.visibility =
                    if (sale.isReturned) View.VISIBLE else View.GONE
                row.root.isEnabled = !sale.isReturned
                row.root.alpha = if (sale.isReturned) 0.6f else 1f
                row.root.setOnClickListener { openReturn(sale) }
            }
        }
    }
}

/** Small helper so the return rows read as one call rather than four stubs. */
private fun android.widget.EditText.addTextChangedListener(afterChanged: (String) -> Unit) {
    addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun afterTextChanged(s: android.text.Editable?) = afterChanged(s?.toString().orEmpty())
    })
}
