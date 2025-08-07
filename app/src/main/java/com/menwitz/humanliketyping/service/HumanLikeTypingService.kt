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
        const val MODE_CLEAR  = "clear"   // default
        const val MODE_APPEND = "append"
        const val PREF_INPUT_HANDLING = "input_handling_mode"

        // New prefs
        const val PREF_REQUIRE_FOCUS = "require_focus"
        const val PREF_COOLDOWN_MS   = "cooldown_ms"

        // Debug overlay toggle 
        const val PREF_DEBUG_OVERLAY = "debug_overlay"

        // Per-app toggle key prefix
        private const val KEY_APP_ENABLED_PREFIX = "app_enabled_"
    }

    private var currentConfig: AppConfig? = null
    private var currentPkg: String? = null

    private lateinit var prefs: SharedPreferences
    private lateinit var sentences: List<SentenceEntry>

    private var serviceActive = false
    private var isTypingInProgress = false                      // 1 run per screen
    private var lastIncomingSignature: String? = null           // dedupe incoming capture
    private val lastSentAtMs: MutableMap<String, Long> = mutableMapOf() // cooldown per app

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

        // Cooldown gate (per app)
        val cooldownMs = prefs.getString(PREF_COOLDOWN_MS, "10000")!!.toLong() // default 10s
        if (cooldownMs > 0 && currentPkg != null) {
            val last = lastSentAtMs[currentPkg!!] ?: 0L
            val now  = System.currentTimeMillis()
            if (now - last < cooldownMs) {
                return
            }
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {

            rootInActiveWindow?.let { root ->
                // 1) Read latest incoming message (for later generator)
                extractLatestIncomingText(root, cfg)?.let { latest ->
                    Log.d(TAG, "Latest incoming ($currentPkg): $latest")
                }

                // 2) Find input and type (with Require Focus gate)
                findInputNode(root, cfg)?.let { input ->
                    if (prefs.getBoolean(PREF_REQUIRE_FOCUS, false) && !input.isFocused) {
                        // User wants explicit focus; skip if not focused
                        return
                    }
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
        // Preferred by ID
        for (id in cfg.inputIds) {
            val list = root.findAccessibilityNodeInfosByViewId(id)
            if (list.isNotEmpty()) return list.first()
        }
        // Fallback by class scan
        val q = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            if (n.className == cfg.inputClass) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return null
    }

    /**
     * Extract latest incoming message via configured bubble TextView IDs.
     * Dedupe by a simple signature so we don't spam logs.
     */
    private fun extractLatestIncomingText(root: AccessibilityNodeInfo, cfg: AppConfig): String? {
        if (cfg.messageTextIds.isEmpty()) return null
        val texts = mutableListOf<CharSequence>()
        for (id in cfg.messageTextIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (n in nodes) n.text?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
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

        // Focus if user does NOT require manual focus
        if (!prefs.getBoolean(PREF_REQUIRE_FOCUS, false)) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        val mode = prefs.getString(PREF_INPUT_HANDLING, MODE_CLEAR) ?: MODE_CLEAR
        val existing = node.text?.toString().orEmpty()

        // Handle Skip / Clear / Append
        when (mode) {
            MODE_SKIP -> if (existing.isNotBlank()) { isTypingInProgress = false; return }
            MODE_CLEAR -> {
                val ok = node.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, ""
                        )
                    }
                )
                // Clearing itself can be blocked; if blocked, we'll rely on paste which overwrites
                if (!ok) Log.d(TAG, "SET_TEXT clear blocked; will try paste route")
            }
            MODE_APPEND -> { /* keep existing; we’ll append below */ }
        }

        val base = if (mode == MODE_APPEND && existing.isNotBlank()) existing + "\n" else ""
        val payload = sentences.firstOrNull()?.text.orEmpty()
        val finalText = base + payload

        // Try incremental human-like typing. Probe first char to see if SET_TEXT is allowed.
        val firstChar = if (finalText.isNotEmpty()) finalText[0].toString() else ""
        if (firstChar.isNotEmpty()) {
            val ok = node.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, firstChar
                    )
                }
            )
            if (!ok) {
                // Blocked: fall back to paste full text (with small delay to feel natural)
                Log.d(TAG, "SET_TEXT blocked; using clipboard paste fallback")
                handler.postDelayed({
                    pasteAllText(node, finalText)
                    scheduleSend(cfg, node, extraDelay = Random.nextLong(250, 600))
                }, Random.nextLong(150, 300))
                return
            }
        }

        // Allowed: proceed with human-like typing from char #2
        var current = firstChar
        val backspaceIndex = if (payload.length > 3) Random.nextInt(1, payload.length - 1) else -1
        var delayMs = Random.nextLong(90, 180) // small pause after first char

        // If we appended, we already consumed firstChar from 'finalText' not 'payload'; compute offsets
        val startIndex = 1
        for (i in startIndex until finalText.length) {
            val c = finalText[i]
            val charDelay = if (c.isWhitespace()) Random.nextLong(180, 320) else Random.nextLong(95, 240)
            delayMs += charDelay
            handler.postDelayed({
                current += c
                node.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, current
                        )
                    }
                )
            }, delayMs)

            // One occasional correction inside the payload region only
            if ((i - base.length) == backspaceIndex) {
                val bsDelay = delayMs + Random.nextLong(180, 360)
                handler.postDelayed({
                    val truncated = if (current.isNotEmpty()) current.dropLast(1) else current
                    node.performAction(
                        AccessibilityNodeInfo.ACTION_SET_TEXT,
                        Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, truncated
                            )
                        }
                    )
                }, bsDelay)
                handler.postDelayed({
                    current += c
                    node.performAction(
                        AccessibilityNodeInfo.ACTION_SET_TEXT,
                        Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, current
                            )
                        }
                    )
                }, bsDelay + Random.nextLong(100, 220))
                delayMs = bsDelay + 100
            }
        }

        scheduleSend(cfg, node, extraDelay = Random.nextLong(280, 700), baseDelay = delayMs)
    }

    // Clipboard/paste fallback path (with long-press menu backup)
    private fun pasteAllText(node: AccessibilityNodeInfo, text: String) {
        // Put text to clipboard
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("hlt", text))

        // Ensure focus
        if (!node.isFocused) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        // Try direct ACTION_PASTE
        val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (pasted) return

        // Fallback: long-press to open context menu then click "Paste"
        if (longPressCenter(node)) {
            rootInActiveWindow?.let { root ->
                val paste = findFirst(root) { n ->
                    val t = n.text?.toString()?.lowercase() ?: ""
                    val d = n.contentDescription?.toString()?.lowercase() ?: ""
                    (t.contains("paste") || d.contains("paste")) && n.isClickable
                }
                paste?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
    }

    private fun scheduleSend(cfg: AppConfig, inputNode: AccessibilityNodeInfo, extraDelay: Long, baseDelay: Long = 0L) {
        val sendAt = baseDelay + extraDelay
        handler.postDelayed({
            val res = trySend(rootInActiveWindow, inputNode, cfg)
            Log.d(TAG, "Send result: $res")

            // Mark cooldown on any non-failure path
            if (res != SendResult.FAILED && currentPkg != null) {
                lastSentAtMs[currentPkg!!] = System.currentTimeMillis()
            }
            // Keep isTypingInProgress=true until screen changes
        }, sendAt)
    }

    // ───────────────────────── robust send paths ─────────────────────────

    private enum class SendResult { CLICKED_ID, CLICKED_ANCESTOR, CONTENT_DESC_TEXT, TAP_GESTURE, IME_FALLBACK, FAILED }

    private fun trySend(root: AccessibilityNodeInfo?, inputNode: AccessibilityNodeInfo, cfg: AppConfig): SendResult {
        root ?: return SendResult.FAILED

        // 1) Explicit send IDs
        for (id in cfg.sendButtonIds) {
            val list = root.findAccessibilityNodeInfosByViewId(id)
            if (list.isNotEmpty()) {
                val n = list.first()
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return SendResult.CLICKED_ID
                firstClickableAncestor(n)?.let { anc ->
                    if (anc.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return SendResult.CLICKED_ANCESTOR
                }
                if (tapCenter(n)) return SendResult.TAP_GESTURE
            }
        }

        // 2) Content-desc / text contains "send"
        val candidate = findFirst(root) { n ->
            val cd = n.contentDescription?.toString()?.lowercase() ?: ""
            val tx = n.text?.toString()?.lowercase() ?: ""
            (cd.contains("send") || tx == "send" || tx.contains("send")) && n.isVisibleToUser
        }
        if (candidate != null) {
            if (candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return SendResult.CONTENT_DESC_TEXT
            firstClickableAncestor(candidate)?.let { anc ->
                if (anc.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return SendResult.CONTENT_DESC_TEXT
            }
            if (tapCenter(candidate)) return SendResult.TAP_GESTURE
        }

        // 3) Last resort: newline (IME) fallback
        val args = Bundle().apply {
            val cur = inputNode.text?.toString().orEmpty()
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cur + "\n")
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
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 36)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun longPressCenter(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 450) // long press ~450ms
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    private inline fun findFirst(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val q = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            if (predicate(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
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