// MainActivity.kt
package com.menwitz.humanliketyping.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.menwitz.humanliketyping.R
import com.menwitz.humanliketyping.service.HumanLikeTypingService

class MainActivity : AppCompatActivity() {

    private val requestNotifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "Notification permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.title = "Human-Like Typing Service"

        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop  = findViewById<Button>(R.id.btnStop)
        val btnSettings  = findViewById<Button>(R.id.btnSettings)
        val btnSentences  = findViewById<Button>(R.id.btnSentences)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        btnStart.setOnClickListener {
            if (!isAccessibilityServiceEnabled(this,
                    ComponentName(this, HumanLikeTypingService::class.java))) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } else {
                Toast.makeText(this, "Service already enabled", Toast.LENGTH_SHORT).show()
            }
        }

        btnStop.setOnClickListener {
            if (isAccessibilityServiceEnabled(this,
                    ComponentName(this, HumanLikeTypingService::class.java))) {
                sendBroadcast(Intent(HumanLikeTypingService.ACTION_STOP))
                Toast.makeText(this, "Service Stopped", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Service not running", Toast.LENGTH_SHORT).show()
            }
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnSentences.setOnClickListener {
            startActivity(Intent(this, SentencesActivity::class.java))
        }
    }

    companion object {
        fun isAccessibilityServiceEnabled(
            context: Context,
            service: ComponentName
        ): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val list = TextUtils.split(enabled, ":")
            return list.any { it.equals(service.flattenToString(), true) }
        }
    }
}
