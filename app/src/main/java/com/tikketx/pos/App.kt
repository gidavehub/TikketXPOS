package com.tikketx.pos

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.tikketx.pos.data.SessionManager
import com.tikketx.pos.data.SwipeBackend
import com.tikketx.pos.diag.CrashReporter
import com.tikketx.pos.printing.PrinterManager

/**
 * TikketX POS.
 *
 * Two Firebase apps live side by side:
 *  - the DEFAULT app (google-services.json, project mbalit-490022) — the
 *    TikketX-owned project the app is registered under;
 *  - the "swipe" app — an explicitly configured connection to the Swipe
 *    platform's Firebase project (swipe-8bb22), so terminals created on
 *    account.madeit sign straight in and sales land on the Swipe dashboard
 *    in real time.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        // First thing, so a crash during the rest of startup is still recorded.
        CrashReporter.install(this)

        // Default app from google-services.json (TikketX project).
        FirebaseApp.initializeApp(this)

        // Secondary app: the Swipe platform project. These are public client
        // identifiers (the same ones the Swipe web bundle ships); security is
        // enforced by Firebase rules, not secrecy.
        if (FirebaseApp.getApps(this).none { it.name == SwipeBackend.APP_NAME }) {
            val options = FirebaseOptions.Builder()
                .setApiKey("AIzaSyBsOqaW_BYbAdnASuXzNBFglXQHJuBzo_w")
                .setApplicationId("1:1075100146791:web:27fc19d2446151906a48c2")
                .setProjectId("swipe-8bb22")
                .setDatabaseUrl("https://swipe-8bb22-default-rtdb.firebaseio.com")
                .setStorageBucket("swipe-8bb22.firebasestorage.app")
                .build()
            FirebaseApp.initializeApp(this, options, SwipeBackend.APP_NAME)
        }

        SessionManager.init(this)
        SwipeBackend.init()
        PrinterManager.init(this)
    }
}
