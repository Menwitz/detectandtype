// HumanLikeTypingService.kt
package com.menwitz.humanliketyping.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.*
import android.graphics.Path
import android.graphics.Rect
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
import com.menwitz.humanliketyping.config.AppConfig
import com.menwitz.humanliketyping.config.AppRegistry
import com.menwitz.humanliketyping.data.model.SentenceEntry
import com.menwitz.humanliketyping.data.repository.SentenceRepository
import com.menwitz.humanliketyping.ui.settings.TypingSettingsActivity
import kotlin.random.Random

private const val TAG = "HLTService"

class HumanLikeTypingService : AccessibilityService() {

    companion object {
        const val ACTION_START          = "com.menwitz.humanliketyping.START_SERVICE"
        const val ACTION_STOP           = "com.menwitz.humanliketyping.STOP_SERVICE"
        const val ACTION_DUMP_HIERARCHY = "com.menwitz.humanliketyping.DUMP_HIERARCHY"

        private const val CHANNEL_ID   = "typing_status_channel"
        private const val CHANNEL_NAME = "Typing Service Status"
        private const val NOTIF_ID     = 1001

        // Input handling modes
        const val MODE_SKIP   = "skip"
        const val MODE_CLEAR  = "clear"   // <- NEW DEFAULT
        const val MODE_APPEND = "append"
        const val PREF_INPUT_HANDLING = "input_handling_mode"

        // Per-app toggle key prefix
        private const val KEY_APP_ENABLED_PREFIX = "app_enabled_"
    }

    private var currentConfig: AppConfig? = null
    private var currentPkg: String? = null

    private lateinit var prefs: SharedPreferences
    private lateinit var sentences: List<SentenceEntry>

    private var serviceActive = false
    private var isTypingInProgress = false      // single-run guard per screen
    private var lastIncomingSignature: String? = null  // to avoid re-reading same bubbles

    private val handler = Handler(Looper.getMainLooper())

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            prefs = PreferenceManager.getDefaultSharedPreferences(this@HumanLikeTypingService)
            serviceActive = prefs.getBoolean("service_active", false)
            when (intent.action) {
                ACTION_START -> if (serviceActive) showStatusNotification()
                ACTION_STOP  -> NotificationManagerCompat.from(this@HumanLikeTypingService).cancel(NOTIF_ID)
                ACTION_DUMP_HIERARCHY -> debugDumpWindow()
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
        // Track package + reset guards when screen/app changes
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {

            val pkg = event.packageName?.toString()
            val cfg = pkg?.let { AppRegistry.map[it] }

            // Per-app toggle
            if (pkg != null && cfg != null && !isAppEnabled(pkg)) {
                currentPkg = pkg
                currentConfig = null
                isTypingInProgress = false
                lastIncomingSignature = null
                Log.d(TAG, "Config switched: $pkg → disabled")
                return
            }

            if (cfg !== currentConfig || pkg != currentPkg) {
                currentPkg = pkg
                currentConfig = cfg
                isTypingInProgress = false
                lastIncomingSignature = null
                val status = when {
                    cfg == null -> "ignored"
                    pkg != null && isAppEnabled(pkg) -> "supported"
                    else -> "disabled"
                }
                Log.d(TAG, "Config switched: $pkg → $status")
            }
        }

        if (!serviceActive || isTypingInProgress) return
        val cfg = currentConfig ?: return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {

            rootInActiveWindow?.let { root ->
                // 1) Read latest incoming message for the model (if any)
                extractLatestIncomingText(root, cfg)?.let { latest ->
                    Log.d(TAG, "Latest incoming (for $currentPkg): $latest")
                    // TODO: feed to generator when we plug the on-device model
                }

                // 2) Find input and type
                findInputNode(root, cfg)?.let { input ->
                    Log.d(TAG, "Detected input in ${currentPkg}")
                    simulateTypingAndSend(input, cfg)
                }
            }
        }
    }

    override fun onInterrupt() {
        handler.removeCallbacksAndMessages(null)
        isTypingInProgress = false
    }

    override fun onDestroy() {
        unregisterReceiver(controlReceiver)
        super.onDestroy()
    }

    // ───────────────────────────────── helpers ─────────────────────────────────

    private fun isAppEnabled(packageName: String): Boolean {
        // default true: all apps enabled by default
        return prefs.getBoolean(KEY_APP_ENABLED_PREFIX + packageName, true)
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
        // Fallback by class scan
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.className == cfg.inputClass) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    /**
     * Extract the latest incoming message text using configured bubble TextView IDs.
     * Avoids re-emitting the same message by caching a simple signature.
     */
    private fun extractLatestIncomingText(root: AccessibilityNodeInfo, cfg: AppConfig): String? {
        if (cfg.messageTextIds.isEmpty()) return null

        val texts = mutableListOf<CharSequence>()
        for (id in cfg.messageTextIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (n in nodes) {
                n.text?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
            }
        }
        if (texts.isEmpty()) return null

        val latest = texts.last().toString().trim()
        val sig = "${currentPkg}|${latest.hashCode()}|${texts.size}"

        if (sig == lastIncomingSignature) return null
        lastIncomingSignature = sig
        return latest
    }

    private fun simulateTypingAndSend(node: AccessibilityNodeInfo, cfg: AppConfig) {
        // guard: only once per screen
        isTypingInProgress = true

        val mode = prefs.getString(PREF_INPUT_HANDLING, MODE_CLEAR) ?: MODE_CLEAR // default CLEAR
        val existing = node.text?.toString().orEmpty()

        when (mode) {
            MODE_SKIP -> if (existing.isNotBlank()) return
            MODE_CLEAR -> {
                val clearArgs = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
            }
            MODE_APPEND -> { /* keep existing; we’ll append below */ }
        }

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        val text = sentences.firstOrNull()?.text.orEmpty()
        var current = if (mode == MODE_APPEND && existing.isNotBlank()) existing + "\n" else ""
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
            val sent = trySend(rootInActiveWindow, node, cfg)
            Log.d(TAG, when (sent) {
                SendResult.CLICKED_ID        -> "Send: clicked explicit ID"
                SendResult.CLICKED_ANCESTOR  -> "Send: clicked ancestor"
                SendResult.TAP_GESTURE       -> "Send: gesture tap"
                SendResult.CONTENT_DESC_TEXT -> "Send: by content-desc/text"
                SendResult.IME_FALLBACK      -> "Send: IME fallback"
                SendResult.FAILED            -> "Send: failed (no control found)"
            })
            // Keep isTypingInProgress=true until screen changes (prevents re-runs)
        }, sendDelay)
    }

    // ───────────────────────── robust send paths ─────────────────────────

    private enum class SendResult { CLICKED_ID, CLICKED_ANCESTOR, CONTENT_DESC_TEXT, TAP_GESTURE, IME_FALLBACK, FAILED }

    private fun trySend(root: AccessibilityNodeInfo?, inputNode: AccessibilityNodeInfo, cfg: AppConfig): SendResult {
        root ?: return SendResult.FAILED

        // 1) Explicit send IDs
        for (id in cfg.sendButtonIds) {
            val list = root.findAccessibilityNodeInfosByViewId(id)
            if (list.isNotEmpty()) {
                val node = list.first()
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return SendResult.CLICKED_ID
                firstClickableAncestor(node)?.let { anc ->
                    if (anc.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return SendResult.CLICKED_ANCESTOR
                }
                // Try gesture on this node’s bounds
                if (tapCenter(node)) return SendResult.TAP_GESTURE
            }
        }

        // 2) Content-desc or text matches like "Send"
        val candidate = findFirst(root) { n ->
            val cd = n.contentDescription?.toString()?.lowercase() ?: ""
            val tx = n.text?.toString()?.lowercase() ?: ""
            // Expand this list over time if needed (localize later)
            (cd.contains("send") || tx == "send" || tx.contains("send")) && n.isVisibleToUser
        }
        if (candidate != null) {
            if (candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return SendResult.CONTENT_DESC_TEXT
            firstClickableAncestor(candidate)?.let { anc ->
                if (anc.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return SendResult.CONTENT_DESC_TEXT
            }
            if (tapCenter(candidate)) return SendResult.TAP_GESTURE
        }

        // 3) Last resort: IME-ish fallback (some fields send on newline)
        val args = Bundle().apply {
            val current = inputNode.text?.toString().orEmpty()
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                current + "\n"
            )
        }
        if (inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return SendResult.IME_FALLBACK
        }

        return SendResult.FAILED
    }

    private fun firstClickableAncestor(node: AccessibilityNodeInfo?, maxHops: Int = 4): AccessibilityNodeInfo? {
        var cur = node?.parent
        var hops = 0
        while (cur != null && hops < maxHops) {
            if (cur.isClickable) return cur
            cur = cur.parent
            hops++
        }
        return null
    }

    private fun tapCenter(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false

        val path = Path().apply {
            moveTo(r.exactCenterX(), r.exactCenterY())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 30)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        var result = false
        // dispatchGesture must run on main; we're already on main
        result = dispatchGesture(gesture, null, null)
        return result
    }

    private inline fun findFirst(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (predicate(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    // ───────────────────────── notification ─────────────────────────

    private fun showStatusNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Auto-typing is active" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, TypingSettingsActivity::class.java)
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