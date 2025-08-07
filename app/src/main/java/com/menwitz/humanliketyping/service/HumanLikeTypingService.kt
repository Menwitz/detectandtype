// HumanLikeTypingService.kt
package com.menwitz.humanliketyping.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.*
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.preference.PreferenceManager
import com.menwitz.humanliketyping.R
import com.menwitz.humanliketyping.config.AppConfig
import com.menwitz.humanliketyping.config.AppRegistry
import com.menwitz.humanliketyping.data.model.SentenceEntry
import com.menwitz.humanliketyping.data.repository.SentenceRepository
import com.menwitz.humanliketyping.ui.settings.TypingSettingsActivity
import kotlin.math.max
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

        // Per-app toggle key prefix
        const val KEY_APP_ENABLED_PREFIX = "app_enabled_"

        // Debug overlay toggle
        const val PREF_DEBUG_OVERLAY = "debug_overlay"
    }

    // Ignore IME packages (keyboard windows spam events)
    private val IME_PACKAGES = setOf(
        "com.google.android.inputmethod.latin",
        "com.microsoft.inputmethod.latin",
        "com.touchtype.swiftkey",
        "com.samsung.android.honeyboard",
        "com.baidu.input",
        "jp.co.omronsoft.openwnn"
    )

    private var currentConfig: AppConfig? = null
    private var currentPkg: String? = null
    private var currentWindowId: Int? = null

    private lateinit var prefs: SharedPreferences
    private lateinit var sentences: List<SentenceEntry>

    private var serviceActive = false
    private var isTypingInProgress = false  // while we’re actually typing/sending
    private var typedThisWindow = false     // one-shot latch per window
    private var lastIncomingSignature: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val scanDelayMs = 120L

    // ───────────── debug overlay members ─────────────
    private var wm: WindowManager? = null
    private var overlayView: TextView? = null
    private val overlayUpdater = Runnable { refreshOverlayText() }

    private val scanRunnable = Runnable {
        if (!serviceActive || isTypingInProgress || typedThisWindow) return@Runnable
        val cfg = currentConfig ?: return@Runnable
        val root = rootInActiveWindow ?: return@Runnable
        if (currentPkg in IME_PACKAGES) return@Runnable

        extractLatestIncomingText(root, cfg)?.let { latest ->
            Log.d(TAG, "Latest incoming ($currentPkg): $latest")
        }

        findInputNode(root, cfg)?.let { input ->
            Log.d(TAG, "scan: input found in $currentPkg → type+send")
            simulateTypingAndSend(input, cfg)
        } ?: Log.d(TAG, "scan: no input found for $currentPkg")

        scheduleOverlayUpdate()
    }

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            prefs = PreferenceManager.getDefaultSharedPreferences(this@HumanLikeTypingService)
            serviceActive = prefs.getBoolean("service_active", false)
            when (intent.action) {
                ACTION_START -> {
                    if (serviceActive) {
                        showStatusNotification()
                        scheduleScan()
                        ensureOverlayVisibility()
                        scheduleOverlayUpdate()
                    }
                }
                ACTION_STOP  -> {
                    handler.removeCallbacksAndMessages(null)
                    isTypingInProgress = false
                    typedThisWindow = false
                    NotificationManagerCompat.from(this@HumanLikeTypingService).cancel(NOTIF_ID)
                    removeOverlay()
                }
                ACTION_DUMP_HIERARCHY -> debugDumpWindow()
            }
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PREF_DEBUG_OVERLAY) {
            ensureOverlayVisibility()
            scheduleOverlayUpdate()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs     = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        sentences = SentenceRepository.loadDefault(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

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
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_FOCUSED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 50
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }

        serviceActive = prefs.getBoolean("service_active", false)
        if (serviceActive) {
            showStatusNotification()
            ensureOverlayVisibility()
            scheduleOverlayUpdate()
        }
        Log.d(TAG, "onServiceConnected(): serviceActive=$serviceActive")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()
        if (pkg != null && pkg in IME_PACKAGES) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            currentWindowId = event.windowId
            typedThisWindow = false
            isTypingInProgress = false
            lastIncomingSignature = null

            currentPkg = pkg
            val cfg = pkg?.let { AppRegistry.map[it] }
            if (pkg != null && cfg != null && !isAppEnabled(pkg)) {
                currentConfig = null
                Log.d(TAG, "Window changed → $pkg (disabled)")
            } else {
                currentConfig = cfg
                val status = when {
                    cfg == null      -> "ignored"
                    pkg == null      -> "unknown"
                    isAppEnabled(pkg)-> "supported"
                    else             -> "disabled"
                }
                Log.d(TAG, "Window changed → $pkg ($status)")
            }

            scheduleScan()
            scheduleOverlayUpdate()
            return
        }

        if (!serviceActive) return
        if (currentConfig == null) return
        if (typedThisWindow || isTypingInProgress) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                scheduleScan()
                scheduleOverlayUpdate()
            }
        }
    }

    override fun onInterrupt() {
        handler.removeCallbacksAndMessages(null)
        isTypingInProgress = false
        scheduleOverlayUpdate()
    }

    override fun onDestroy() {
        try { unregisterReceiver(controlReceiver) } catch (_: Throwable) {}
        try { prefs.unregisterOnSharedPreferenceChangeListener(prefListener) } catch (_: Throwable) {}
        removeOverlay()
        super.onDestroy()
    }

    // ───────────────────────────────── helpers ─────────────────────────────────

    private fun isAppEnabled(packageName: String): Boolean {
        return prefs.getBoolean(KEY_APP_ENABLED_PREFIX + packageName, true)
    }

    private fun scheduleScan() {
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, scanDelayMs)
    }

    private fun debugDumpWindow() {
        val root = rootInActiveWindow ?: return
        fun traverse(node: AccessibilityNodeInfo, indent: String = "") {
            val id  = node.viewIdResourceName ?: "<no-id>"
            val cls = node.className ?: "<no-class>"
            val txt = node.text ?: ""
            Log.d(TAG, "$indent$id [$cls] text='$txt'")
            for (i in 0 until node.childCount) node.getChild(i)?.let { traverse(it, indent + "  ") }
        }
        Log.d(TAG, "---- WINDOW HIERARCHY (${currentPkg}) ----")
        traverse(root)
        toast("Hierarchy dumped to logcat")
    }

    private fun findInputNode(root: AccessibilityNodeInfo, cfg: AppConfig): AccessibilityNodeInfo? {
        for (id in cfg.inputIds) {
            try {
                val list = root.findAccessibilityNodeInfosByViewId(id)
                if (list.isNotEmpty()) return list.first()
            } catch (_: Throwable) {}
        }
        val q = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            if (n.className == cfg.inputClass) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return null
    }

    private fun extractLatestIncomingText(root: AccessibilityNodeInfo, cfg: AppConfig): String? {
        if (cfg.messageTextIds.isEmpty()) return null
        val texts = mutableListOf<CharSequence>()
        for (id in cfg.messageTextIds) {
            try {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                for (n in nodes) n.text?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
            } catch (_: Throwable) {}
        }
        if (texts.isEmpty()) return null

        val latest = texts.last().toString().trim()
        val sig = "${currentPkg}|${latest.hashCode()}|${texts.size}"
        if (sig == lastIncomingSignature) return null
        lastIncomingSignature = sig
        return latest
    }

    // ───────────────── typing & send (exactly once per window) ─────────────────

    private fun simulateTypingAndSend(node: AccessibilityNodeInfo, cfg: AppConfig) {
        if (typedThisWindow || isTypingInProgress) return
        typedThisWindow = true
        isTypingInProgress = true
        scheduleOverlayUpdate()

        val mode = prefs.getString(PREF_INPUT_HANDLING, MODE_CLEAR) ?: MODE_CLEAR
        val existing = node.text?.toString().orEmpty()

        when (mode) {
            MODE_SKIP -> {
                if (existing.isNotBlank()) {
                    Log.d(TAG, "MODE_SKIP: field non-empty → do nothing this window.")
                    isTypingInProgress = false
                    scheduleOverlayUpdate()
                    return
                }
            }
            MODE_CLEAR -> {
                val clearArgs = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
            }
            MODE_APPEND -> { /* keep existing; append below */ }
        }

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        val newText = sentences.firstOrNull()?.text.orEmpty()
        if (newText.isEmpty()) {
            Log.d(TAG, "No sentence to type; abort.")
            isTypingInProgress = false
            scheduleOverlayUpdate()
            return
        }

        var current = if (mode == MODE_APPEND && existing.isNotBlank()) existing + "\n" else ""
        var delayMs = 0L
        val backspaceIndex = if (newText.length > 3) Random.nextInt(1, newText.length - 1) else -1

        newText.forEachIndexed { i, c ->
            val charDelay = Random.nextLong(90, 220)
            delayMs += charDelay
            handler.postDelayed({
                current += c
                setNodeText(node, current)
                scheduleOverlayUpdate()
            }, delayMs)

            if (i == backspaceIndex) {
                val bsDelay = delayMs + Random.nextLong(180, 320)
                handler.postDelayed({
                    if (current.isNotEmpty()) {
                        current = current.dropLast(1)
                        setNodeText(node, current)
                        scheduleOverlayUpdate()
                    }
                }, bsDelay)
                handler.postDelayed({
                    current += c
                    setNodeText(node, current)
                    scheduleOverlayUpdate()
                }, bsDelay + Random.nextLong(90, 160))
                delayMs = max(delayMs, bsDelay + 100)
            }
        }

        val sendDelay = delayMs + Random.nextLong(260, 520)
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
            // Keep isTypingInProgress=true; we only re-enable on window change.
            scheduleOverlayUpdate()
        }, sendDelay)
    }

    private fun setNodeText(node: AccessibilityNodeInfo, value: String) {
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                value
            )
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) Log.d(TAG, "ACTION_SET_TEXT failed once (may succeed later)")
    }

    // ───────────────────────── robust send paths ─────────────────────────

    private enum class SendResult { CLICKED_ID, CLICKED_ANCESTOR, CONTENT_DESC_TEXT, TAP_GESTURE, IME_FALLBACK, FAILED }

    private fun trySend(root: AccessibilityNodeInfo?, inputNode: AccessibilityNodeInfo, cfg: AppConfig): SendResult {
        root ?: return SendResult.FAILED

        for (id in cfg.sendButtonIds) {
            try {
                val list = root.findAccessibilityNodeInfosByViewId(id)
                if (list.isNotEmpty()) {
                    val node = list.first()
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return SendResult.CLICKED_ID
                    firstClickableAncestor(node)?.let { anc ->
                        if (anc.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return SendResult.CLICKED_ANCESTOR
                    }
                    if (tapCenter(node)) return SendResult.TAP_GESTURE
                }
            } catch (_: Throwable) {}
        }

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

        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 30)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
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

    private fun toast(msg: String) {
        try { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
    }

    // ───────────────────────── overlay helpers ─────────────────────────

    private fun ensureOverlayVisibility() {
        if (!prefs.getBoolean(PREF_DEBUG_OVERLAY, false)) {
            removeOverlay()
            return
        }
        if (overlayView != null) return

        val params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            x = 12
            y = 12
            flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                type = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }
        }

        overlayView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x88000000.toInt())
            setPadding(12, 10, 12, 10)
            textSize = 12f
            text = "HLT overlay"
        }
        try {
            wm?.addView(overlayView, params)
        } catch (t: Throwable) {
            overlayView = null
            Log.w(TAG, "Failed to add overlay: ${t.message}")
        }
    }

    private fun removeOverlay() {
        val v = overlayView ?: return
        try { wm?.removeView(v) } catch (_: Throwable) {}
        overlayView = null
    }

    private fun scheduleOverlayUpdate() {
        if (overlayView == null) return
        handler.removeCallbacks(overlayUpdater)
        handler.postDelayed(overlayUpdater, 50L)
    }

    private fun refreshOverlayText() {
        val v = overlayView ?: return
        val info = buildString {
            appendLine("pkg=${currentPkg ?: "none"}")
            appendLine("win=${currentWindowId ?: -1}")
            appendLine("active=$serviceActive")
            appendLine("typedThisWin=$typedThisWindow")
            appendLine("typing=$isTypingInProgress")
        }
        v.text = info
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