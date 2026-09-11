package com.tikketx.pos.ui.sell

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.snackbar.Snackbar
import com.tikketx.pos.R
import com.tikketx.pos.data.CartManager
import com.tikketx.pos.data.SessionManager
import com.tikketx.pos.data.SwipeBackend
import com.tikketx.pos.data.model.CartItem
import com.tikketx.pos.data.model.CompletedSale
import com.tikketx.pos.data.model.PaymentMethod
import com.tikketx.pos.data.model.TaxRate
import com.tikketx.pos.data.model.Terminal
import com.tikketx.pos.databinding.SheetCheckoutBinding
import com.tikketx.pos.printing.PrinterManager
import com.tikketx.pos.printing.ReceiptPrinter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Cart review + payment, matching the web register: per-line discounts, an
 * optional tax rate, the four payment methods, and a customer credit record
 * when the sale goes on account. Charge is the last tap: the sale is recorded,
 * the drawer opens, the ticket prints and the sheet is gone, all from that one
 * press.
 */
class CheckoutSheet : BottomSheetDialogFragment() {

    private var _binding: SheetCheckoutBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: CartAdapter
    private var paymentMethod = PaymentMethod.CASH
    private var taxRates: List<TaxRate> = emptyList()

    /**
     * Lines and quantities as last drawn. Typing in a line's discount box
     * notifies the cart like any other change; rebinding the list on that would
     * pull focus out of the field mid-keystroke, so the list is only rebuilt
     * when the lines themselves actually change.
     */
    private var renderedSignature: String = ""

    /** Chosen due date, as UTC millis, so re-opening the picker lands on it. */
    private var selectedDueDate: Long? = null

    /**
     * Set the instant Charge is honoured.
     *
     * Charging no longer pauses on anything, so the button is still on screen
     * for the frame or two before the sheet tears down. A second press in that
     * window would ring the sale twice.
     */
    private var charged = false

    private val isoDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val cartListener: () -> Unit = {
        if (_binding != null) {
            if (CartManager.isEmpty) {
                dismissAllowingStateLoss()
            } else {
                val signature = cartSignature()
                if (signature != renderedSignature) {
                    renderedSignature = signature
                    adapter.refresh()
                }
                renderTotals()
            }
        }
    }

    private fun cartSignature(): String =
        CartManager.list().joinToString("|") { "${it.product.id}:${it.quantity}" }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = SheetCheckoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    /**
     * Opens the sheet fully expanded and keeps it there.
     *
     * A bottom sheet defaults to half height, which is what made checkout feel
     * like a third screen: the cashier dragged it up, then scrolled it, before
     * reaching Charge. Skipping the collapsed state means the sheet arrives at
     * full height with the pinned footer already visible.
     */
    override fun onStart() {
        super.onStart()
        val sheet = (dialog as? com.google.android.material.bottomsheet.BottomSheetDialog)
            ?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?: return
        BottomSheetBehavior.from(sheet).apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
        sheet.layoutParams = sheet.layoutParams.apply {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        adapter = CartAdapter()
        binding.cartList.layoutManager = LinearLayoutManager(requireContext())
        binding.cartList.adapter = adapter
        renderedSignature = cartSignature()

        binding.paymentToggle.check(R.id.payCash)
        binding.paymentToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            paymentMethod = when (checkedId) {
                R.id.payCard -> PaymentMethod.CARD
                R.id.payMobile -> PaymentMethod.MOBILE_MONEY
                R.id.payCredit -> PaymentMethod.CREDIT
                else -> PaymentMethod.CASH
            }
            applyPaymentMethod()
        }

        binding.inputTendered.doOnTextChanged { _, _, _, _ -> renderTotals() }
        binding.btnClearCart.setOnClickListener { CartManager.clear() }
        binding.btnCharge.setOnClickListener { charge() }
        binding.inputCreditDue.setOnClickListener { pickDueDate() }
        binding.creditDueLayout.setEndIconOnClickListener { pickDueDate() }

        buildQuickCash()
        applyPaymentMethod()
        loadTaxRates()

        CartManager.addListener(cartListener)
        renderTotals()
    }

    /** Quick denominations, so the usual note is one tap rather than typing. */
    private fun buildQuickCash() {
        for (amount in listOf(50, 100, 200, 500, 1000, 5000)) {
            val chip = Chip(requireContext()).apply {
                text = SwipeBackend.fmtMoney(amount.toDouble())
                isCheckable = false
                setOnClickListener { binding.inputTendered.setText(amount.toString()) }
            }
            binding.quickCash.addView(chip)
        }
    }

    private fun loadTaxRates() {
        val terminal = SessionManager.terminal ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val rates = runCatching { SwipeBackend.taxRates(terminal.companyId) }.getOrNull().orEmpty()
            if (_binding == null || rates.isEmpty()) return@launch

            taxRates = rates
            val labels = listOf(getString(R.string.no_tax)) +
                rates.map { "${it.name} (${SwipeBackend.trimNumber(it.rate)}%)" }
            binding.taxLayout.visibility = View.VISIBLE
            binding.inputTax.setSimpleItems(labels.toTypedArray())
            binding.inputTax.setText(labels[0], false)
            binding.inputTax.setOnItemClickListener { _, _, position, _ ->
                CartManager.taxRate = if (position == 0) null else taxRates[position - 1]
                renderTotals()
            }
        }
    }

    private fun applyPaymentMethod() {
        val cash = paymentMethod == PaymentMethod.CASH
        val credit = paymentMethod == PaymentMethod.CREDIT
        binding.tenderedRow.visibility = if (cash) View.VISIBLE else View.GONE
        binding.creditRow.visibility = if (credit) View.VISIBLE else View.GONE
        binding.customerLayout.hint = getString(
            if (credit) R.string.customer_required else R.string.customer_optional
        )
        renderTotals()
    }

    private fun renderTotals() {
        val subtotal = CartManager.subtotal
        val discount = CartManager.discountTotal
        val tax = CartManager.taxAmount
        val total = CartManager.total

        binding.subtotalValue.text = SwipeBackend.fmtMoney(subtotal)

        binding.discountRow.visibility = if (discount > 0.0) View.VISIBLE else View.GONE
        binding.discountValue.text = "−" + SwipeBackend.fmtMoney(discount)

        val rate = CartManager.taxRate
        binding.taxRow.visibility = if (rate != null) View.VISIBLE else View.GONE
        if (rate != null) {
            binding.taxLabel.text = "${rate.name} (${SwipeBackend.trimNumber(rate.rate)}%)"
            binding.taxValue.text = SwipeBackend.fmtMoney(tax)
        }

        binding.totalValue.text = SwipeBackend.fmtMoney(total)
        binding.btnCharge.text = if (paymentMethod == PaymentMethod.CREDIT) {
            getString(R.string.record_credit_sale)
        } else {
            getString(R.string.charge_amount, SwipeBackend.fmtMoney(total))
        }

        // While cash is short, show how short — the cashier is mid-count and
        // that is the number they need, not a hidden row.
        val tendered = binding.inputTendered.text?.toString()?.toDoubleOrNull()
        if (paymentMethod == PaymentMethod.CASH && tendered != null) {
            binding.changeRow.visibility = View.VISIBLE
            if (tendered >= total) {
                binding.changeLabel.setText(R.string.change)
                binding.changeValue.text = SwipeBackend.fmtMoney(tendered - total)
                binding.changeValue.setTextColor(
                    resources.getColor(R.color.tikketx_emerald, null)
                )
            } else {
                binding.changeLabel.setText(R.string.amount_received)
                binding.changeValue.text =
                    getString(R.string.short_by, SwipeBackend.fmtMoney(total - tendered))
                binding.changeValue.setTextColor(resources.getColor(R.color.tikketx_red, null))
            }
        } else {
            binding.changeRow.visibility = View.GONE
        }
    }

    /**
     * Calendar for the credit due date.
     *
     * Typing a date at a counter is slow and gets the format wrong; the picker
     * hands back a day and this stores it as the YYYY-MM-DD the platform's
     * `customer_credits.due_date` expects. The picker works in UTC millis, so
     * the formatter has to as well — reading it back in local time lands on the
     * previous day for anyone west of Greenwich.
     */
    private fun pickDueDate() {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.credit_due_date)
            .setSelection(selectedDueDate ?: MaterialDatePicker.todayInUtcMilliseconds())
            .build()
        picker.addOnPositiveButtonClickListener { millis ->
            selectedDueDate = millis
            _binding?.inputCreditDue?.setText(isoDate.format(Date(millis)))
        }
        picker.show(parentFragmentManager, "credit_due_date")
    }

    /**
     * Charging: check what can be checked, then do the whole thing.
     *
     * There is no longer a confirmation step. It used to sit between the tap
     * and the ticket, restating totals the cashier had been looking at the
     * whole time, and it cost a decision at the one moment there is a customer
     * waiting on the other side of the counter. The screen the cashier is
     * already reading *is* the confirmation.
     *
     * What is left are the two checks a cashier cannot make by eye and would
     * regret getting wrong: cash that does not cover the total, and a credit
     * sale with nobody's name on it. Neither is a question — both are the
     * sale being wrong, and both refuse the tap outright.
     */
    private fun charge() {
        if (charged) return
        val terminal = SessionManager.terminal ?: return
        val items = CartManager.list()
        if (items.isEmpty()) return

        val total = CartManager.total
        val tendered = binding.inputTendered.text?.toString()?.toDoubleOrNull()
        if (paymentMethod == PaymentMethod.CASH && tendered != null && tendered < total) {
            Snackbar.make(binding.root, "Amount received is less than the total.", Snackbar.LENGTH_LONG).show()
            return
        }

        val customer = binding.inputCustomer.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        if (paymentMethod == PaymentMethod.CREDIT && customer == null) {
            Snackbar.make(binding.root, R.string.credit_needs_name, Snackbar.LENGTH_LONG).show()
            return
        }

        charged = true
        submitSale(terminal, items, tendered, customer)
    }

    /**
     * Records the sale and prints the ticket, in that order, in one frame.
     *
     * Nothing here waits on the network. [SwipeBackend.completeSale] queues its
     * writes and returns — the sale is durable on the device at that point, and
     * goes up to Swipe on its own — so the cash drawer opens, the sheet closes
     * and the receipt starts rendering while those writes are still in flight.
     * That is the whole of the fix for a checkout that used to sit on a spinner
     * for the better part of twenty seconds before the paper moved.
     *
     * The activity is captured up front because the sheet is dismissed before
     * the print finishes: the print outlives this fragment, so it cannot hang
     * its context — or the snackbar it reports through — on a view that is
     * already gone.
     */
    private fun submitSale(
        terminal: Terminal,
        items: List<CartItem>,
        tendered: Double?,
        customer: String?,
    ) {
        val activity = requireActivity()
        val customerPhone = binding.inputCustomerPhone.text?.toString()?.trim()
        val creditDueDate = binding.inputCreditDue.text?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }

        val sale = try {
            SwipeBackend.completeSale(
                terminal = terminal,
                items = items,
                paymentMethod = paymentMethod,
                amountTendered = tendered,
                customerName = customer,
                customerPhone = customerPhone,
                creditDueDate = creditDueDate,
                taxRate = CartManager.taxRate,
            )
        } catch (e: Exception) {
            // Only the checks the sale itself failed reach here now; anything
            // the server objects to arrives much later, through
            // SwipeBackend.onDeferredWriteFailure.
            charged = false
            Snackbar.make(binding.root, SwipeBackend.describeError(e), Snackbar.LENGTH_LONG).show()
            return
        }

        if (paymentMethod == PaymentMethod.CASH) PrinterManager.openDrawer()
        ReceiptPrinter.print(activity, sale) { outcome ->
            announce(activity, sale, outcome)
        }
        CartManager.clear()
        dismissAllowingStateLoss()
    }

    /**
     * Says what happened, on the activity rather than the sheet — the sheet is
     * gone by now.
     *
     * Change leads when there is any: the cashier is holding the customer's
     * note and that is the number they need next. The sale number follows so a
     * return can be looked up even if the paper jams.
     */
    private fun announce(
        activity: FragmentActivity,
        sale: CompletedSale,
        outcome: PrinterManager.Outcome,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val root = activity.findViewById<View>(android.R.id.content) ?: return

        val printed = when (outcome) {
            PrinterManager.Outcome.NATIVE -> activity.getString(R.string.printed)
            PrinterManager.Outcome.FALLBACK -> activity.getString(R.string.printed_fallback)
            PrinterManager.Outcome.FAILED -> activity.getString(R.string.printer_failed)
        }
        val message = buildString {
            if (sale.changeGiven > 0) {
                append(activity.getString(R.string.change_due, SwipeBackend.fmtMoney(sale.changeGiven)))
                append(" · ")
            }
            append(sale.saleNumber)
            append(" · ")
            append(printed)
        }

        Snackbar.make(root, message, Snackbar.LENGTH_LONG).apply {
            // Change and sale number on one line would be clipped on a narrow
            // till screen, and change is the half that must not be.
            view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.maxLines = 3
        }.show()
    }

    override fun onDestroyView() {
        CartManager.removeListener(cartListener)
        _binding = null
        super.onDestroyView()
    }
}
