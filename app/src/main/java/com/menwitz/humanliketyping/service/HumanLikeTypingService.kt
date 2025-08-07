// HumanLikeTypingService.kt
package com.menwitz.humanliketyping.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
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
        const val ACTION_START          = "com.menwitz.humanliketyping.START_SERVICE"
        const val ACTION_STOP           = "com.menwitz.humanliketyping.STOP_SERVICE"
        const val ACTION_DUMP_HIERARCHY = "com.menwitz.humanliketyping.DUMP_HIERARCHY"
        private const val CHANNEL_ID    = "typing_status_channel"
        private const val CHANNEL_NAME  = "Typing Service Status"
        private const val NOTIF_ID      = 1001
    }

    private var currentConfig: AppConfig? = null
    private lateinit var prefs: SharedPreferences
    private lateinit var sentences: List<SentenceEntry>
    private var serviceActive = false
    private var isTypingInProgress = false    // guard flag
    private val handler = Handler(Looper.getMainLooper())

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            prefs = PreferenceManager.getDefaultSharedPreferences(this@HumanLikeTypingService)
            serviceActive = prefs.getBoolean("service_active", false)
            when (intent.action) {
                ACTION_START -> {
                    Log.d(TAG, "START received; serviceActive=$serviceActive")
                    if (serviceActive) showStatusNotification()
                }
                ACTION_STOP -> {
                    Log.d(TAG, "STOP received; serviceActive=$serviceActive")
                    NotificationManagerCompat.from(this@HumanLikeTypingService)
                        .cancel(NOTIF_ID)
                }
                ACTION_DUMP_HIERARCHY -> {
                    Log.d(TAG, "DUMP_HIERARCHY received")
                    debugDumpWindow()
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
                addAction(ACTION_DUMP_HIERARCHY)
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
        // Update which app config to use
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            val pkg = event.packageName?.toString()
            val cfg = pkg?.let { AppRegistry.map[it] }
            if (cfg !== currentConfig) {
                currentConfig = cfg
                isTypingInProgress = false    // reset typing on app switch
                val status = if (cfg != null) "supported" else "ignored"
                Log.d(TAG, "Config switched: $pkg → $status")
            }
        }

        // Only when active and no typing in progress
        if (!serviceActive || isTypingInProgress) return
        val cfg = currentConfig ?: return

        // On content change or focus, find input and type
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {

            rootInActiveWindow?.let { root ->
                findInputNode(root, cfg)?.let { input ->
                    Log.d(TAG, "Detected input in ${event.packageName}")
                    simulateTypingAndSend(input, cfg)
                }
            }
        }
    }

    override fun onInterrupt() {
        handler.removeCallbacksAndMessages(null)
        isTypingInProgress = false    // clear on interrupt
    }

    override fun onDestroy() {
        unregisterReceiver(controlReceiver)
        super.onDestroy()
    }

    private fun debugDumpWindow() {
        rootInActiveWindow?.let { root ->
            fun traverse(node: AccessibilityNodeInfo, indent: String = "") {
                val id  = node.viewIdResourceName ?: "<no-id>"
                val cls = node.className ?: "<no-class>"
                val txt = node.text ?: ""
                Log.d(TAG, "$indent$id [$cls] text='$txt'")
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { traverse(it, indent + "  ") }
                }
            }
            traverse(root)
        }
    }

    private fun findInputNode(root: AccessibilityNodeInfo, cfg: AppConfig): AccessibilityNodeInfo? {
        for (id in cfg.inputIds) {
            val list = root.findAccessibilityNodeInfosByViewId(id)
            if (list.isNotEmpty()) return list.first()
        }
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.className == cfg.inputClass) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun simulateTypingAndSend(node: AccessibilityNodeInfo, cfg: AppConfig) {
        isTypingInProgress = true    // typing started
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val text = sentences.firstOrNull()?.text.orEmpty()
        var current = ""
        val backspaceIndex = if (text.length > 3) Random.nextInt(1, text.length - 1) else -1
        var delayMs = 0L

        text.forEachIndexed { i, c ->
            val charDelay = Random.nextLong(100, 300)
            delayMs += charDelay
            handler.postDelayed({
                current += c
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, current)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }, delayMs)

            if (i == backspaceIndex) {
                val bsDelay = delayMs + Random.nextLong(200, 400)
                handler.postDelayed({
                    val truncated = current.dropLast(1)
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, truncated)
                    }
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }, bsDelay)
                handler.postDelayed({
                    current += c
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, current)
                    }
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }, bsDelay + Random.nextLong(100, 200))
                delayMs = bsDelay + 100
            }
        }

        val sendDelay = delayMs + Random.nextLong(300, 700)
        handler.postDelayed({
            rootInActiveWindow?.let { root ->
                for (id in cfg.sendButtonIds) {
                    val list = root.findAccessibilityNodeInfosByViewId(id)
                    if (list.isNotEmpty()) {
                        list.first().performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.d(TAG, "Clicked send-button id=$id")
                        isTypingInProgress = false    // clear after send
                        return@let
                    }
                }
                node.performAction(AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT)
                Log.d(TAG, "Fallback send via IME action")
                isTypingInProgress = false    // clear on fallback
            }
        }, sendDelay)
    }

    private fun showStatusNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Auto-typing is active" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
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