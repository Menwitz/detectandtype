package com.menwitz.humanliketyping.ui

import android.os.Bundle
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.preference.PreferenceManager
import com.menwitz.humanliketyping.R

class SettingsActivity : AppCompatActivity() {

    private lateinit var sbMinDelay: SeekBar
    private lateinit var sbMaxDelay: SeekBar
    private lateinit var sbWordMin: SeekBar
    private lateinit var sbWordMax: SeekBar
    private lateinit var tvMinValue: TextView
    private lateinit var tvMaxValue: TextView
    private lateinit var tvWordMinValue: TextView
    private lateinit var tvWordMaxValue: TextView
    private lateinit var switchInjection: SwitchCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.title = getString(R.string.settings_title)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        sbMinDelay     = findViewById(R.id.sbMinDelay)
        sbMaxDelay     = findViewById(R.id.sbMaxDelay)
        sbWordMin      = findViewById(R.id.sbWordPauseMin)
        sbWordMax      = findViewById(R.id.sbWordPauseMax)
        tvMinValue     = findViewById(R.id.tvMinDelayValue)
        tvMaxValue     = findViewById(R.id.tvMaxDelayValue)
        tvWordMinValue = findViewById(R.id.tvWordPauseMinValue)
        tvWordMaxValue = findViewById(R.id.tvWordPauseMaxValue)
        switchInjection= findViewById(R.id.switchInjection)

        // Load existing prefs
        sbMinDelay.progress = prefs.getLong("keystroke_min_delay", 100L).toInt()
        sbMaxDelay.progress = prefs.getLong("keystroke_max_delay", 300L).toInt()
        sbWordMin.progress  = prefs.getLong("word_pause_min", 300L).toInt()
        sbWordMax.progress  = prefs.getLong("word_pause_max", 600L).toInt()
        switchInjection.isChecked = prefs.getBoolean("use_direct_injection", true)

        // Display values
        tvMinValue.text = sbMinDelay.progress.toString()
        tvMaxValue.text = sbMaxDelay.progress.toString()
        tvWordMinValue.text = sbWordMin.progress.toString()
        tvWordMaxValue.text = sbWordMax.progress.toString()

        // Listeners
        sbMinDelay.setOnSeekBarChangeListener(seekBarListener("keystroke_min_delay", tvMinValue))
        sbMaxDelay.setOnSeekBarChangeListener(seekBarListener("keystroke_max_delay", tvMaxValue))
        sbWordMin.setOnSeekBarChangeListener(seekBarListener("word_pause_min", tvWordMinValue))
        sbWordMax.setOnSeekBarChangeListener(seekBarListener("word_pause_max", tvWordMaxValue))
        switchInjection.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("use_direct_injection", checked).apply()
        }
    }

    private fun seekBarListener(prefKey: String, valueView: TextView) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                valueView.text = progress.toString()
                PreferenceManager.getDefaultSharedPreferences(this@SettingsActivity)
                    .edit().putLong(prefKey, progress.toLong()).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        }
}
