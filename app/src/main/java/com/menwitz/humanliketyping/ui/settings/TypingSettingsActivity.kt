package com.menwitz.humanliketyping.ui.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.menwitz.humanliketyping.R

class TypingSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager
            .beginTransaction()
            .replace(android.R.id.content, TypingSettingsFragment())
            .commit()
        title = getString(R.string.title_typing_settings)
    }
}