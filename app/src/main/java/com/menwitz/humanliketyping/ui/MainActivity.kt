// MainActivity.kt
package com.menwitz.humanliketyping.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.menwitz.humanliketyping.R
import com.menwitz.humanliketyping.service.HumanLikeTypingService

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"
    private lateinit var prefs: SharedPreferences
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private val requestNotif = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d(TAG, "POST_NOTIFICATIONS granted=$granted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        setContentView(R.layout.activity_main)
        supportActionBar?.title = "Human-Like Typing"

        btnStart = findViewById(R.id.btnStart)
        btnStop  = findViewById(R.id.btnStop)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        btnStart.setOnClickListener {
            if (!isAccessibilityEnabled()) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this,
                    "Please enable the Accessibility Service, then press Start again",
                    Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Accessibility service not enabled")
            } else {
                prefs.edit().putBoolean("service_active", true).apply()
                Intent(HumanLikeTypingService.ACTION_START).apply {
                    setPackage(packageName)
                    sendBroadcast(this)
                }
                Toast.makeText(this, "Auto-typing STARTED", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Sent START broadcast; prefs service_active=true")
            }
        }

        btnStop.setOnClickListener {
            prefs.edit().putBoolean("service_active", false).apply()
            Intent(HumanLikeTypingService.ACTION_STOP).apply {
                setPackage(packageName)
                sendBroadcast(this)
            }
            Toast.makeText(this, "Auto-typing STOPPED", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Sent STOP broadcast; prefs service_active=false")
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val flat = ComponentName(this, HumanLikeTypingService::class.java)
            .flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return TextUtils.split(enabled, ":").any {
            it.equals(flat, true)
        }
    }
}
