package com.menwitz.humanliketyping.ui.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.preference.*
import com.menwitz.humanliketyping.R
import com.menwitz.humanliketyping.config.AppRegistry
import com.menwitz.humanliketyping.service.HumanLikeTypingService

class TypingSettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = requireContext()
        val pm  = ctx.packageManager

        val screen = preferenceManager.createPreferenceScreen(ctx)

        // 1) Input handling mode (default = CLEAR)
        val modeList = ListPreference(ctx).apply {
            key = HumanLikeTypingService.PREF_INPUT_HANDLING
            title = getString(R.string.pref_input_mode_title)
            summary = "%s"
            entries = arrayOf(
                getString(R.string.pref_input_mode_skip),
                getString(R.string.pref_input_mode_clear),
                getString(R.string.pref_input_mode_append)
            )
            entryValues = arrayOf(
                HumanLikeTypingService.MODE_SKIP,
                HumanLikeTypingService.MODE_CLEAR,   // default
                HumanLikeTypingService.MODE_APPEND
            )
            setDefaultValue(HumanLikeTypingService.MODE_CLEAR)
        }
        screen.addPreference(modeList)

        // 2) Require focus toggle
        val reqFocus = SwitchPreferenceCompat(ctx).apply {
            key = HumanLikeTypingService.PREF_REQUIRE_FOCUS
            title = getString(R.string.pref_require_focus_title)
            summary = getString(R.string.pref_require_focus_summary)
            setDefaultValue(false) // default: we auto-focus
        }
        screen.addPreference(reqFocus)

        // 3) Cooldown selector (per app)
        val cooldown = ListPreference(ctx).apply {
            key = HumanLikeTypingService.PREF_COOLDOWN_MS
            title = getString(R.string.pref_cooldown_title)
            summary = "%s"
            entries = arrayOf(
                getString(R.string.cooldown_off),
                "5 s",
                "10 s",
                "30 s",
                "60 s"
            )
            entryValues = arrayOf(
                "0",
                "5000",
                "10000",
                "30000",
                "60000"
            )
            setDefaultValue("10000") // 10s
        }
        screen.addPreference(cooldown)

        // 4) Per-app toggles (default enabled = true)
        val appsCat = PreferenceCategory(ctx).apply {
            title = getString(R.string.pref_cat_apps_title)
        }
        screen.addPreference(appsCat)

        AppRegistry.map.keys.sorted().forEach { pkg ->
            val (label, icon) = resolveAppLabelIcon(pm, pkg)
            val sw = SwitchPreferenceCompat(ctx).apply {
                key = "app_enabled_$pkg"
                title = label
                summary = pkg
                isIconSpaceReserved = true
                icon?.let { this.icon = it }
                setDefaultValue(true) // enabled by default
            }
            appsCat.addPreference(sw)
        }

        // 5) Debug: dump hierarchy
        val debugCat = PreferenceCategory(ctx).apply {
            title = getString(R.string.pref_cat_debug_title)
        }
        screen.addPreference(debugCat)

        val overlaySw = SwitchPreferenceCompat(ctx).apply {
            key = HumanLikeTypingService.PREF_DEBUG_OVERLAY
            title = getString(R.string.pref_debug_overlay_title)
            summary = getString(R.string.pref_debug_overlay_summary)
            setDefaultValue(false)
        }
        debugCat.addPreference(overlaySw)

        val dumpBtn = Preference(ctx).apply {
            title = getString(R.string.pref_dump_hierarchy_title)
            summary = getString(R.string.pref_dump_hierarchy_summary)
            setOnPreferenceClickListener {
                ctx.sendBroadcast(Intent(HumanLikeTypingService.ACTION_DUMP_HIERARCHY))
                true
            }
        }
        debugCat.addPreference(dumpBtn)

        preferenceScreen = screen
    }

    private fun resolveAppLabelIcon(pm: PackageManager, pkg: String): Pair<String, Drawable?> {
        return try {
            val ai = pm.getApplicationInfo(pkg, 0)
            val label = pm.getApplicationLabel(ai)?.toString() ?: pkg
            val icon = pm.getApplicationIcon(pkg)
            label to icon
        } catch (_: Exception) {
            pkg to null
        }
    }
}