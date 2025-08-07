// HumanLikeTypingService.kt
package com.menwitz.humanliketyping.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.preference.PreferenceManager
import com.menwitz.humanliketyping.R
import com.menwitz.humanliketyping.data.model.SentenceEntry
import com.menwitz.humanliketyping.data.repository.SentenceRepository
import com.menwitz.humanliketyping.ui.SettingsActivity
import com.menwitz.humanliketyping.config.AppConfig
import com.menwitz.humanliketyping.config.AppRegistry

class HumanLikeTypingService : AccessibilityService() {
    companion object {
        private const val TAG = "HLTService"
        const val ACTION_START = "com.menwitz.humanliketyping.START_SERVICE"
        const val ACTION_STOP  = "com.menwitz.humanliketyping.STOP_SERVICE"
        private const val CHANNEL_ID = "typing_status_channel"
        private const val CHANNEL_NAME = "Typing Service Status"
        private const val NOTIF_ID = 1001
    }

    private var currentConfig: AppConfig? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var sentences: List<SentenceEntry>
    private var serviceActive = false

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            prefs = PreferenceManager.getDefaultSharedPreferences(this@HumanLikeTypingService)
            serviceActive = prefs.getBoolean("service_active", false)
            when (intent.action) {
                ACTION_START -> {
                    Log.d(TAG, "Received START; serviceActive=$serviceActive")
                    if (serviceActive) {
                        showStatusNotification()
                    }
                }
                ACTION_STOP -> {
                    Log.d(TAG, "Received STOP; serviceActive=$serviceActive")
                    NotificationManagerCompat.from(this@HumanLikeTypingService)
                        .cancel(NOTIF_ID)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        sentences = SentenceRepository.loadDefault(this)

        // Register start/stop broadcasts
        registerReceiver(
            controlReceiver,
            IntentFilter().apply {
                addAction(ACTION_START)
                addAction(ACTION_STOP)
            },
            Context.RECEIVER_NOT_EXPORTED
        )

        // Configure which accessibility events to listen for
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 50
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }

        // Initialize active state and notification if needed
        serviceActive = prefs.getBoolean("service_active", false)
        Log.d(TAG, "onServiceConnected(): persisted serviceActive=$serviceActive")
        if (serviceActive) {
            showStatusNotification()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Update config on window or focus changes
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {

            val pkg = event.packageName?.toString()
            val cfg = pkg?.let { AppRegistry.map[it] }
            if (cfg !== currentConfig) {
                currentConfig = cfg
                val status = if (cfg != null) "supported" else "ignored"
                Log.d(TAG, "Switched config for package=$pkg → $status")
            }
        }

        // Only proceed if service is active and config known
        if (!serviceActive) return
        val cfg = currentConfig ?: return

        // On content change or focus, detect and queue typing
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {

            rootInActiveWindow?.let { root ->
                findInputNode(root, cfg)?.let { input ->
                    Log.d(TAG, "Detected input field in ${event.packageName}")
                    performHumanLikeTyping(input)
                }
            }
        }
    }

    override fun onInterrupt() {
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        unregisterReceiver(controlReceiver)
        super.onDestroy()
    }

    private fun showStatusNotification() {
        Log.d(TAG, "Posting status notification")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Auto-typing is active" }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(chan)
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE else 0
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentTitle("Human-Like Typing Active")
            .setContentText("Tap to configure")
            .setContentIntent(pi)
            .build()
        NotificationManagerCompat.from(this).notify(NOTIF_ID, n)
    }

    private fun performHumanLikeTyping(node: AccessibilityNodeInfo) {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        var current = ""
        sentences.firstOrNull()?.text.orEmpty().forEachIndexed { i, c ->
            current += c
            handler.postDelayed({
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        current
                    )
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }, 150L * i)
        }
    }

    /**
     * Finds the first input node according to the AppConfig:
     * 1) Try each configured view-ID in order
     * 2) If none found, scan the view hierarchy for the configured inputClass
     */
    private fun findInputNode(
        root: AccessibilityNodeInfo,
        cfg: AppConfig
    ): AccessibilityNodeInfo? {
        // 1) Explicit ID lookup
        for (id in cfg.inputIds) {
            val list = root.findAccessibilityNodeInfosByViewId(id)
            if (list.isNotEmpty()) return list.first()
        }
        // 2) Fallback to class scan
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.className == cfg.inputClass) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }
}