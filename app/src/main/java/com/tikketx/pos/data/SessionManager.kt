package com.tikketx.pos.data

import android.content.Context
import android.content.SharedPreferences
import com.tikketx.pos.data.model.Terminal
import com.tikketx.pos.data.model.TerminalPermissions

/** Persists the signed-in terminal between launches. */
object SessionManager {

    private const val PREFS = "tikketx_pos_session"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    var terminal: Terminal?
        get() {
            val id = prefs.getString("id", null) ?: return null
            return Terminal(
                id = id,
                companyId = prefs.getString("company_id", "") ?: "",
                companyName = prefs.getString("company_name", "") ?: "",
                posDomain = prefs.getString("pos_domain", "") ?: "",
                terminalName = prefs.getString("terminal_name", "Terminal") ?: "Terminal",
                username = prefs.getString("username", "") ?: "",
                authEmail = prefs.getString("auth_email", "") ?: "",
                authUid = prefs.getString("auth_uid", "") ?: "",
                role = prefs.getString("role", "cashier") ?: "cashier",
                permissions = TerminalPermissions(
                    addInventory = prefs.getBoolean("perm_add_inventory", false),
                    viewReports = prefs.getBoolean("perm_view_reports", false),
                    allowRefunds = prefs.getBoolean("perm_allow_refunds", false),
                ),
                active = true,
            )
        }
        set(value) {
            val e = prefs.edit()
            if (value == null) {
                // Keep device-local preferences that outlive the session: the
                // printer's ticket style shouldn't reset when a cashier signs
                // out, and the address is what makes signing back in one field.
                val style = ticketStyle
                val email = lastEmail
                e.clear()
                e.putString("ticket_style", style)
                e.putString("last_email", email)
            } else {
                e.putString("id", value.id)
                e.putString("company_id", value.companyId)
                e.putString("company_name", value.companyName)
                e.putString("pos_domain", value.posDomain)
                e.putString("terminal_name", value.terminalName)
                e.putString("username", value.username)
                e.putString("auth_email", value.authEmail)
                e.putString("auth_uid", value.authUid)
                e.putString("role", value.role)
                e.putBoolean("perm_add_inventory", value.permissions.addInventory)
                e.putBoolean("perm_view_reports", value.permissions.viewReports)
                e.putBoolean("perm_allow_refunds", value.permissions.allowRefunds)
            }
            e.apply()
        }

    /** Last address signed in with, pre-filled on the login screen. */
    var lastEmail: String
        get() = prefs.getString("last_email", "") ?: ""
        set(value) { prefs.edit().putString("last_email", value).apply() }

    /** Receipt/ticket style chosen in Settings. */
    var ticketStyle: String
        get() = prefs.getString("ticket_style", "classic") ?: "classic"
        set(value) { prefs.edit().putString("ticket_style", value).apply() }
}
