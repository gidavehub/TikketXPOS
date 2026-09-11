package com.tikketx.pos.ui.main

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.tikketx.pos.BuildConfig
import com.tikketx.pos.R
import com.tikketx.pos.data.SessionManager
import com.tikketx.pos.data.SwipeBackend
import com.tikketx.pos.data.model.ScanRequest
import com.tikketx.pos.data.model.Terminal
import com.tikketx.pos.databinding.ActivityMainBinding
import com.tikketx.pos.ui.common.drawBehindSystemBars
import com.tikketx.pos.ui.common.padWithSystemBars
import com.tikketx.pos.ui.inventory.InventoryFragment
import com.tikketx.pos.ui.login.LoginActivity
import com.tikketx.pos.ui.reports.ReportsFragment
import com.tikketx.pos.ui.scan.ScannerActivity
import com.tikketx.pos.ui.sell.SellFragment
import com.tikketx.pos.ui.settings.SettingsFragment
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Bottom-nav shell: Sell / Stock / Reports / Settings, gated by terminal role. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // --- Remote scan requests (Swipe dashboard → this terminal) ---
    private var scanRequestsSub: SwipeBackend.ScanRequestSubscription? = null
    private var connectionSub: SwipeBackend.ScanRequestSubscription? = null
    /** Whether the notice is showing a backlog that still has to go up. */
    private var wasOffline = false
    private var scanDialog: AlertDialog? = null
    private var scanDialogRequestId: String? = null
    private var activeScanRequestId: String? = null
    /** Requests already shown/dismissed here, so the popup never nags twice. */
    private val handledScanRequests = mutableSetOf<String>()

    private val remoteScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val requestId = activeScanRequestId
        activeScanRequestId = null
        val terminal = SessionManager.terminal
        if (requestId == null || terminal == null) return@registerForActivityResult

        val code = result.data?.getStringExtra(ScannerActivity.EXTRA_CODE)
        if (result.resultCode == Activity.RESULT_OK && !code.isNullOrBlank()) {
            SwipeBackend.completeScanRequest(terminal, requestId, code)
            Snackbar.make(binding.root, getString(R.string.remote_scan_sent, code), Snackbar.LENGTH_LONG).show()
        } else {
            // Camera closed without a scan — let another device (or a retry) pick it up.
            SwipeBackend.releaseScanRequest(terminal, requestId)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val terminal = SessionManager.terminal
        if (terminal == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Red gradient headers sit under the status bar (white icons); the
        // white bottom nav absorbs the gesture-bar inset (dark icons).
        drawBehindSystemBars(darkStatusIcons = false, darkNavIcons = true)
        binding.bottomNav.padWithSystemBars(bottom = true)

        // Role gating: cashiers without permissions don't see Stock/Reports.
        applyPermissions(terminal)

        binding.bottomNav.setOnItemSelectedListener { item ->
            val fragment: Fragment = when (item.itemId) {
                R.id.nav_inventory -> InventoryFragment()
                R.id.nav_reports -> ReportsFragment()
                R.id.nav_settings -> SettingsFragment()
                else -> SellFragment()
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, fragment)
                .commit()
            true
        }

        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.nav_sell
        }

        startHeartbeat()
    }

    override fun onStart() {
        super.onStart()

        // Bails before `binding` exists when there is no session — onCreate
        // finished the activity and everything below needs a view.
        val terminal = SessionManager.terminal ?: return

        // A sale no longer waits for the server to accept it — it prints and
        // the cashier moves on. That is the right trade at a counter, but it
        // means a write the rules refuse would otherwise fail in total silence,
        // with a ticket already in the customer's hand. This is where it lands
        // instead. Cleared in onStop so a backgrounded activity is not held on
        // to by the callback.
        SwipeBackend.onDeferredWriteFailure = { message ->
            Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
        }
        scanRequestsSub = SwipeBackend.listenScanRequests(terminal) { onScanRequests(it) }
        connectionSub = SwipeBackend.observeConnection { connected -> renderConnection(connected) }
    }

    override fun onStop() {
        SwipeBackend.onDeferredWriteFailure = null
        scanRequestsSub?.remove()
        scanRequestsSub = null
        connectionSub?.remove()
        connectionSub = null
        scanDialog?.dismiss()
        super.onStop()
    }

    /**
     * Tells the cashier the till is offline, and that it is still selling.
     *
     * Worth saying out loud: a sale rung up with no signal is saved on the
     * device and goes to Swipe on its own. Without this the staff have no way
     * to tell "recorded, not yet uploaded" from "lost", and the safe assumption
     * they'd make is the wrong one — ringing the sale again.
     *
     * On reconnect the notice switches to a sync message briefly rather than
     * vanishing, so it is visible that the backlog went up.
     */
    private fun renderConnection(connected: Boolean) {
        if (!connected) {
            wasOffline = true
            binding.offlineBannerText.setText(R.string.offline_banner)
            binding.offlineBanner.visibility = View.VISIBLE
            return
        }
        if (!wasOffline) {
            binding.offlineBanner.visibility = View.GONE
            return
        }
        wasOffline = false
        binding.offlineBannerText.setText(R.string.syncing_banner)
        binding.offlineBanner.visibility = View.VISIBLE
        binding.offlineBanner.postDelayed({
            if (SwipeBackend.isOnline) binding.offlineBanner.visibility = View.GONE
        }, 4_000)
    }

    /**
     * Presence ping to RTDB — powers the live green dot on Swipe → POS Devices.
     *
     * The same tick re-reads the terminal record, so a permission granted or a
     * device deactivated on the dashboard reaches a till that is already signed
     * in, instead of waiting for someone to sign out and back in.
     */
    private fun startHeartbeat() {
        lifecycleScope.launch {
            while (isActive) {
                // Nothing in this loop may throw. It runs on a timer behind
                // whatever the cashier is doing, so an exception escaping here
                // does not fail a refresh — it takes the whole app down
                // mid-sale, which is exactly what an unguarded network call
                // used to do roughly once a minute on a weak connection.
                when (val refresh = SwipeBackend.refreshTerminal()) {
                    is SwipeBackend.TerminalRefresh.Updated -> {
                        applyPermissions(refresh.terminal)
                        pushHeartbeat(refresh.terminal)
                    }
                    // Authoritative: an owner deleted or deactivated this
                    // device, so stop selling on it.
                    SwipeBackend.TerminalRefresh.Revoked -> {
                        onTerminalRevoked()
                        return@launch
                    }
                    // Couldn't check. Carry on with the session we already have
                    // rather than throwing a working till off the counter
                    // because one request timed out.
                    SwipeBackend.TerminalRefresh.Unavailable -> {
                        SessionManager.terminal?.let { pushHeartbeat(it) }
                    }
                }
                delay(60_000)
            }
        }
    }

    private suspend fun pushHeartbeat(terminal: Terminal) {
        val stats = runCatching { SwipeBackend.todayStats(terminal.companyId) }.getOrNull()
        runCatching {
            SwipeBackend.heartbeat(
                terminal, stats,
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                appVersion = BuildConfig.VERSION_NAME,
            )
        }
    }

    private fun applyPermissions(terminal: Terminal) {
        val menu = binding.bottomNav.menu
        menu.findItem(R.id.nav_inventory).isVisible = terminal.permissions.addInventory
        menu.findItem(R.id.nav_reports).isVisible = terminal.permissions.viewReports
    }

    private fun onTerminalRevoked() {
        SwipeBackend.signOut()
        Snackbar.make(binding.root, R.string.terminal_revoked, Snackbar.LENGTH_LONG).show()
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    // -----------------------------------------------------------------
    // Remote scan: the dashboard's Inventory form asks for a barcode.
    // -----------------------------------------------------------------

    private fun onScanRequests(requests: List<ScanRequest>) {
        val terminal = SessionManager.terminal ?: return

        // Keep the visible prompt honest: if the request was cancelled on the
        // web, finished elsewhere, or claimed by another device, take it down.
        val showingId = scanDialogRequestId
        if (showingId != null) {
            val current = requests.firstOrNull { it.id == showingId }
            val stillOurs = current != null && (
                current.status == "pending" ||
                    (current.status == "claimed" && current.claimedBy == terminal.id)
                )
            if (!stillOurs) scanDialog?.dismiss()
            return
        }
        if (activeScanRequestId != null) return // camera already open for one

        val next = requests.firstOrNull { req ->
            req.status == "pending" &&
                req.id !in handledScanRequests &&
                (req.targetTerminalId == null || req.targetTerminalId == terminal.id) &&
                (SwipeBackend.isoAgeMillis(req.requestedAt)?.let { it < 5 * 60_000 } ?: true)
        } ?: return
        showScanRequestDialog(next)
    }

    private fun showScanRequestDialog(request: ScanRequest) {
        scanDialogRequestId = request.id
        scanDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.remote_scan_title)
            .setMessage(R.string.remote_scan_message)
            .setPositiveButton(R.string.remote_scan_accept) { _, _ ->
                handledScanRequests += request.id
                val terminal = SessionManager.terminal
                if (terminal != null) {
                    activeScanRequestId = request.id
                    SwipeBackend.claimScanRequest(terminal, request.id)
                    remoteScanLauncher.launch(ScannerActivity.intent(this))
                }
            }
            .setNegativeButton(R.string.remote_scan_ignore) { _, _ ->
                handledScanRequests += request.id
            }
            .setOnDismissListener {
                scanDialog = null
                scanDialogRequestId = null
            }
            .show()
    }
}
