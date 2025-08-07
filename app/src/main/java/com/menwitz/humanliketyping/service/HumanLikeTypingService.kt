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
import kotlin.random.Random

private const val TAG = "HLTService"

class HumanLikeTypingService : AccessibilityService() {
    companion object {
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
                    if (serviceActive) showStatusNotification()
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
        prefs     = PreferenceManager.getDefaultSharedPreferences(this)
        sentences = SentenceRepository.loadDefault(this)

        registerReceiver(
            controlReceiver,
            IntentFilter().apply {
                addAction(ACTION_START)
                addAction(ACTION_STOP)
            },
            Context.RECEIVER_NOT_EXPORTED
        )

        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 50
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }

        serviceActive = prefs.getBoolean("service_active", false)
        if (serviceActive) showStatusNotification()
        Log.d(TAG, "onServiceConnected(): serviceActive=$serviceActive")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // update config
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            val pkg = event.packageName?.toString()
            val cfg = pkg?.let { AppRegistry.map[it] }
            if (cfg !== currentConfig) {
                currentConfig = cfg
                val status = if (cfg!=null) "supported" else "ignored"
                Log.d(TAG, "Switched config: $pkg → $status")
            }
        }

        if (!serviceActive) return
        val cfg = currentConfig ?: return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            rootInActiveWindow?.let { root ->
                findInputNode(root, cfg)?.let { input ->
                    Log.d(TAG, "Typing into ${cfg.inputClass} in ${event.packageName}")
                    simulateTypingAndSend(input, cfg)
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

    private fun simulateTypingAndSend(node: AccessibilityNodeInfo, cfg: AppConfig) {
        // 1) focus & click
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        // 2) prepare text
        val text = sentences.firstOrNull()?.text.orEmpty()
        var current = ""

        // pick a random index for a single backspace correction
        val backspaceIndex = if (text.length>3) Random.nextInt(1, text.length-1) else -1

        // 3) simulate each keystroke
        var cumulativeDelay = 0L
        for ((i, c) in text.withIndex()) {
            val delay = Random.nextLong(100, 300)
            cumulativeDelay += delay

            // type character
            handler.postDelayed({
                current += c
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        current
                    )
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }, cumulativeDelay)

            // occasional backspace
            if (i == backspaceIndex) {
                val bsDelay = cumulativeDelay + Random.nextLong(200, 400)
                // remove last char
                handler.postDelayed({
                    val truncated = current.dropLast(1)
                    val args = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            truncated
                        )
                    }
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }, bsDelay)
                // retype char
                handler.postDelayed({
                    current += c
                    val args = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            current
                        )
                    }
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }, bsDelay + Random.nextLong(100,200))
                cumulativeDelay = bsDelay + 100
            }
        }

        // 4) after typing finishes, schedule send
        val sendDelay = cumulativeDelay + Random.nextLong(300, 700)
        handler.postDelayed({
            rootInActiveWindow?.let { root ->
                // try configured send-button IDs
                for (id in cfg.sendButtonIds) {
                    val list = root.findAccessibilityNodeInfosByViewId(id)
                    if (list.isNotEmpty()) {
                        list.first().performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.d(TAG, "Clicked send-button id=$id")
                        return@let
                    }
                }
                // fallback: send via IME action
                node.performAction(AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT)
                Log.d(TAG, "Fallback send via IME action")
            }
        }, sendDelay)
    }

    private fun findInputNode(
        root: AccessibilityNodeInfo,
        cfg: AppConfig
    ): AccessibilityNodeInfo? {
        // try explicit IDs
        for (id in cfg.inputIds) {
            val list = root.findAccessibilityNodeInfosByViewId(id)
            if (list.isNotEmpty()) return list.first()
        }
        // fallback to class scan
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

    private fun showStatusNotification() {
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
}