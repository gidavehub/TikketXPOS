package com.tikketx.pos.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import com.tikketx.pos.R
import com.tikketx.pos.data.SessionManager
import com.tikketx.pos.data.SwipeBackend
import com.tikketx.pos.databinding.FragmentSettingsBinding
import com.tikketx.pos.diag.CrashReporter
import com.tikketx.pos.printing.PrinterManager
import com.tikketx.pos.printing.ReceiptPrinter
import com.tikketx.pos.ui.common.padWithSystemBars
import com.tikketx.pos.ui.login.LoginActivity

/** Terminal info, ticket style, printer test, sign out. */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        val terminal = SessionManager.terminal ?: return

        binding.header.padWithSystemBars(top = true)

        binding.terminalName.text = terminal.terminalName
        // The sign-in address, not the bare username: it's what staff needs when
        // the device is handed over or has to be signed in again.
        binding.terminalMeta.text = getString(
            R.string.terminal_meta,
            terminal.companyName, terminal.posDomain, terminal.authEmail, terminal.role,
        )

        // Ticket style picker
        when (SessionManager.ticketStyle) {
            "compact" -> binding.styleToggle.check(R.id.styleCompact)
            "ticket" -> binding.styleToggle.check(R.id.styleTicket)
            else -> binding.styleToggle.check(R.id.styleClassic)
        }
        binding.styleToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            SessionManager.ticketStyle = when (checkedId) {
                R.id.styleCompact -> "compact"
                R.id.styleTicket -> "ticket"
                else -> "classic"
            }
        }

        // Printer
        binding.printerStatus.text = getString(
            if (PrinterManager.hasNativePrinter) R.string.printer_native
            else R.string.printer_fallback
        )
        binding.btnTestPrint.setOnClickListener {
            // Runs in a coroutine because binding to the printer service is
            // asynchronous — the old code bound and printed on the next line,
            // which raced the connection callback and reported "printer not
            // found" on devices that had one.
            binding.btnTestPrint.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                val outcome = PrinterManager.testPrint(requireContext())
                binding.printerStatus.text = getString(
                    if (PrinterManager.hasNativePrinter) R.string.printer_native
                    else R.string.printer_fallback
                )
                Snackbar.make(
                    binding.root,
                    getString(
                        when (outcome) {
                            PrinterManager.Outcome.NATIVE -> R.string.printed
                            PrinterManager.Outcome.FALLBACK -> R.string.printed_fallback
                            PrinterManager.Outcome.FAILED -> R.string.printer_failed
                        }
                    ),
                    Snackbar.LENGTH_SHORT,
                ).show()
                binding.btnTestPrint.isEnabled = true
            }
        }

        showLastCrashIfAny()

        binding.btnSignOut.setOnClickListener {
            SwipeBackend.signOut()
            // The cached receipt logo belongs to the shop that just signed out.
            ReceiptPrinter.forgetLogo()
            startActivity(Intent(requireContext(), LoginActivity::class.java))
            requireActivity().finish()
        }
    }

    /**
     * Surfaces the last fatal crash, if there was one.
     *
     * The device is behind a counter with no cable attached, so this is the
     * only route a stack trace has out of it.
     */
    private fun showLastCrashIfAny() {
        val report = CrashReporter.lastReport
        if (report.isNullOrBlank()) {
            binding.crashCard.visibility = View.GONE
            return
        }
        binding.crashCard.visibility = View.VISIBLE
        binding.crashSummary.text = report.lineSequence().take(6).joinToString(System.lineSeparator())

        binding.btnCopyCrash.setOnClickListener {
            val clipboard = requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("TikketX POS crash", report))
            Snackbar.make(binding.root, R.string.crash_copied, Snackbar.LENGTH_LONG).show()
        }
        binding.btnDismissCrash.setOnClickListener {
            CrashReporter.clear()
            binding.crashCard.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
