package com.tikketx.pos.ui.login

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.tikketx.pos.R
import com.tikketx.pos.data.SessionManager
import com.tikketx.pos.data.SwipeBackend
import com.tikketx.pos.databinding.ActivityLoginBinding
import com.tikketx.pos.ui.common.drawBehindSystemBars
import com.tikketx.pos.ui.common.padWithSystemBars
import com.tikketx.pos.ui.main.MainActivity
import kotlinx.coroutines.launch

/**
 * Terminal sign-in.
 *
 * Two fields, because the terminal's address is its whole identity:
 * `<username>@<company-domain>`, exactly as shown on Swipe → POS Devices. The
 * company no longer has to be named separately — it is the domain half of the
 * address, and the account behind it is a real Firebase user, which is what
 * puts this device on the same data as the dashboard.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Already signed in? Straight to the till.
        if (SessionManager.terminal != null) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Gradient fills the whole screen, so white icons on both bars.
        drawBehindSystemBars(darkStatusIcons = false, darkNavIcons = false)
        binding.root.padWithSystemBars(top = true, bottom = true, includeIme = true)

        // A shared terminal is signed out and back in by different staff all
        // day; the address never changes, only the shift does.
        binding.inputEmail.setText(SessionManager.lastEmail)
        if (SessionManager.lastEmail.isNotEmpty()) binding.inputPassword.requestFocus()

        binding.btnLogin.setOnClickListener { attemptLogin() }
        binding.inputPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { attemptLogin(); true } else false
        }
    }

    private fun attemptLogin() {
        val email = binding.inputEmail.text?.toString()?.trim().orEmpty()
        val password = binding.inputPassword.text?.toString().orEmpty()

        if (email.isEmpty() || password.isEmpty()) {
            Snackbar.make(binding.root, R.string.login_missing, Snackbar.LENGTH_LONG).show()
            return
        }

        setBusy(true)
        lifecycleScope.launch {
            try {
                SwipeBackend.login(email, password)
                SessionManager.lastEmail = email.lowercase()
                startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                finish()
            } catch (e: Exception) {
                Snackbar.make(binding.root, e.message ?: "Sign-in failed.", Snackbar.LENGTH_LONG).show()
                setBusy(false)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !busy
    }
}
