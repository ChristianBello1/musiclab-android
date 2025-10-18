package com.example.musiclab

import android.content.Context
import android.os.Bundle
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var backButton: ImageButton
    private lateinit var automixSwitch: Switch
    private lateinit var crossfadeDurationSeekBar: SeekBar
    private lateinit var crossfadeDurationText: TextView
    private lateinit var crossfadeSettingsContainer: android.view.View

    companion object {
        const val PREFS_NAME = "MusicLabPrefs"
        const val KEY_AUTOMIX_ENABLED = "automix_enabled"
        const val KEY_CROSSFADE_DURATION = "crossfade_duration"

        // Default: 8 secondi di crossfade
        const val DEFAULT_CROSSFADE_DURATION = 8
        const val MIN_CROSSFADE_DURATION = 5
        const val MAX_CROSSFADE_DURATION = 10

        /**
         * Helper per leggere se automix è abilitato
         */
        fun isAutomixEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_AUTOMIX_ENABLED, false)
        }

        /**
         * Helper per leggere la durata del crossfade
         */
        fun getCrossfadeDuration(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_CROSSFADE_DURATION, DEFAULT_CROSSFADE_DURATION)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        initializeViews()
        loadSettings()
        setupListeners()
    }

    private fun initializeViews() {
        backButton = findViewById(R.id.back_button)
        automixSwitch = findViewById(R.id.automix_switch)
        crossfadeDurationSeekBar = findViewById(R.id.crossfade_duration_seekbar)
        crossfadeDurationText = findViewById(R.id.crossfade_duration_text)
        crossfadeSettingsContainer = findViewById(R.id.crossfade_settings_container)

        // Configura la SeekBar (0-5 per rappresentare 5-10 secondi)
        crossfadeDurationSeekBar.max = MAX_CROSSFADE_DURATION - MIN_CROSSFADE_DURATION
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val automixEnabled = prefs.getBoolean(KEY_AUTOMIX_ENABLED, false)
        val crossfadeDuration = prefs.getInt(KEY_CROSSFADE_DURATION, DEFAULT_CROSSFADE_DURATION)

        automixSwitch.isChecked = automixEnabled
        updateCrossfadeSettingsVisibility(automixEnabled)

        // Imposta la posizione della seekbar
        crossfadeDurationSeekBar.progress = crossfadeDuration - MIN_CROSSFADE_DURATION
        updateCrossfadeDurationText(crossfadeDuration)
    }

    private fun setupListeners() {
        backButton.setOnClickListener {
            finish()
        }

        automixSwitch.setOnCheckedChangeListener { _, isChecked ->
            saveAutomixEnabled(isChecked)
            updateCrossfadeSettingsVisibility(isChecked)
        }

        crossfadeDurationSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val duration = progress + MIN_CROSSFADE_DURATION
                updateCrossfadeDurationText(duration)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val duration = (seekBar?.progress ?: 0) + MIN_CROSSFADE_DURATION
                saveCrossfadeDuration(duration)
            }
        })
    }

    private fun updateCrossfadeSettingsVisibility(automixEnabled: Boolean) {
        crossfadeSettingsContainer.visibility = if (automixEnabled) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
    }

    private fun updateCrossfadeDurationText(duration: Int) {
        crossfadeDurationText.text = "$duration secondi"
    }

    private fun saveAutomixEnabled(enabled: Boolean) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTOMIX_ENABLED, enabled).apply()
    }

    private fun saveCrossfadeDuration(duration: Int) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_CROSSFADE_DURATION, duration).apply()
    }
}