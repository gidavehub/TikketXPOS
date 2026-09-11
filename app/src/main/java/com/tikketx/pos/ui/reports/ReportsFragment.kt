package com.tikketx.pos.ui.reports

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.tikketx.pos.data.SessionManager
import com.tikketx.pos.data.SwipeBackend
import com.tikketx.pos.databinding.FragmentReportsBinding
import com.tikketx.pos.ui.common.padWithSystemBars
import kotlinx.coroutines.launch

/** Today at a glance — the deep analytics live on the Swipe dashboard. */
class ReportsFragment : Fragment() {

    private var _binding: FragmentReportsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentReportsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        binding.header.padWithSystemBars(top = true)
        binding.swipeRefresh.setOnRefreshListener { load() }
        load()
    }

    private fun load() {
        val terminal = SessionManager.terminal ?: return
        binding.swipeRefresh.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching { SwipeBackend.todayStats(terminal.companyId) }
            if (_binding == null) return@launch
            binding.swipeRefresh.isRefreshing = false

            result.onSuccess { stats ->
                binding.todaysSales.text = SwipeBackend.fmtMoney(stats.salesTotal)
                binding.todaysTransactions.text = stats.transactions.toString()
                val avg = if (stats.transactions > 0) stats.salesTotal / stats.transactions else 0.0
                binding.averageSale.text = SwipeBackend.fmtMoney(avg)
                binding.itemsSold.text = SwipeBackend.fmtQty(stats.itemsSold)
                binding.cashTotal.text = SwipeBackend.fmtMoney(stats.cashTotal)
                binding.nonCashTotal.text = SwipeBackend.fmtMoney(stats.nonCashTotal)
            }.onFailure { e ->
                // Zeroes here would read as "no sales today" when the real
                // answer is that the terminal couldn't see them.
                Snackbar.make(
                    binding.root,
                    SwipeBackend.describeError(e as Exception),
                    Snackbar.LENGTH_LONG,
                ).show()
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
