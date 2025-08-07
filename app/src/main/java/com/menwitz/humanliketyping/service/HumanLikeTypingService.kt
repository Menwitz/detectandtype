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
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.menwitz.humanliketyping.R
import com.menwitz.humanliketyping.data.model.SentenceEntry
import com.menwitz.humanliketyping.data.repository.SentenceRepository
import com.menwitz.humanliketyping.ui.SettingsActivity
import kotlin.random.Random

class HumanLikeTypingService : AccessibilityService() {
    companion object {
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "typing_status_channel"
        private const val CHANNEL_NAME = "Typing Service Status"
        const val ACTION_START = "com.menwitz.humanliketyping.START_SERVICE"
        const val ACTION_STOP  = "com.menwitz.humanliketyping.STOP_SERVICE"
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var sentences: List<SentenceEntry>
    private var serviceActive = false

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_STOP -> {
                    // 1. Disable the AccessibilityService
                    disableSelf()
                    // 2. Stop this Service instance immediately
                    stopSelf()
                    // 3. Remove the persistent notification
                    NotificationManagerCompat.from(this@HumanLikeTypingService)
                        .cancel(NOTIF_ID)
                }
                ACTION_START -> {
                    if (!serviceActive) {
                        serviceActive = true
                        showStatusNotification()  // re-post notification
                    }
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceActive = true
        registerReceiver(
            controlReceiver,
            IntentFilter().apply {
                addAction(ACTION_START)
                addAction(ACTION_STOP)
            },
            Context.RECEIVER_NOT_EXPORTED
        )

        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }

        sentences = SentenceRepository.loadDefault(this)
        // start active by default
        serviceActive = true
        showStatusNotification()
    }

    override fun onDestroy() {
        unregisterReceiver(controlReceiver)
        super.onDestroy()
    }

    override fun onInterrupt() {
        handler.removeCallbacksAndMessages(null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!serviceActive) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            rootInActiveWindow?.let { root ->
                val inputs = root.findAccessibilityNodeInfosByViewId("android:id/edit")
                    .ifEmpty { findNodesByClass(root, "android.widget.EditText") }
                if (inputs.isNotEmpty()) performHumanLikeTyping(inputs.first())
            }
        }
    }

    private fun showStatusNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Typing service is active" }
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
        val text = sentences.firstOrNull()?.text.orEmpty()
        var current = ""
        text.forEachIndexed { i, c ->
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
        handler.postDelayed({ /* reset typing */ }, text.length * 150L + 100L)
    }

    private fun findNodesByClass(
        root: AccessibilityNodeInfo,
        className: String
    ): List<AccessibilityNodeInfo> {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        val out = mutableListOf<AccessibilityNodeInfo>()
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n.className == className) out.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return out
    }
}
