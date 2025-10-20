package com.example.musiclab

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var backButton: ImageButton

    // Automix
    private lateinit var automixSwitch: Switch
    private lateinit var crossfadeDurationSeekBar: SeekBar
    private lateinit var crossfadeDurationText: TextView
    private lateinit var crossfadeSettingsContainer: View

    // Sleep Timer
    private lateinit var btnTimer5: Button
    private lateinit var btnTimer10: Button
    private lateinit var btnTimer15: Button
    private lateinit var btnTimer30: Button
    private lateinit var btnTimer60: Button
    private lateinit var btnTimerCancel: Button
    private lateinit var timerActiveContainer: LinearLayout
    private lateinit var timerRemainingText: TextView
    private lateinit var sleepTimerManager: SleepTimerManager
    private lateinit var musicPlayer: MusicPlayer

    // Battery Saver
    private lateinit var batterySaverSwitch: Switch

    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerUpdateRunnable = object : Runnable {
        override fun run() {
            if (sleepTimerManager.isTimerActive()) {
                updateTimerUI()
                timerHandler.postDelayed(this, 1000)
            }
        }
    }

    companion object {
        const val PREFS_NAME = "MusicLabPrefs"
        const val KEY_AUTOMIX_ENABLED = "automix_enabled"
        const val KEY_CROSSFADE_DURATION = "crossfade_duration"
        const val KEY_BATTERY_SAVER = "battery_saver_enabled"

        const val DEFAULT_CROSSFADE_DURATION = 8
        const val MIN_CROSSFADE_DURATION = 5
        const val MAX_CROSSFADE_DURATION = 10

        fun isAutomixEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_AUTOMIX_ENABLED, false)
        }

        fun getCrossfadeDuration(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_CROSSFADE_DURATION, DEFAULT_CROSSFADE_DURATION)
        }

        fun isBatterySaverEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_BATTERY_SAVER, true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        musicPlayer = MusicPlayerManager.getInstance().getMusicPlayer(this)
        sleepTimerManager = SleepTimerManager.getInstance(this)

        initializeViews()
        loadSettings()
        setupListeners()

        if (sleepTimerManager.isTimerActive()) {
            updateTimerUI()
            startTimerUpdates()
        }
    }

    private fun initializeViews() {
        backButton = findViewById(R.id.back_button)

        automixSwitch = findViewById(R.id.automix_switch)
        crossfadeDurationSeekBar = findViewById(R.id.crossfade_duration_seekbar)
        crossfadeDurationText = findViewById(R.id.crossfade_duration_text)
        crossfadeSettingsContainer = findViewById(R.id.crossfade_settings_container)

        btnTimer5 = findViewById(R.id.btn_timer_5)
        btnTimer10 = findViewById(R.id.btn_timer_10)
        btnTimer15 = findViewById(R.id.btn_timer_15)
        btnTimer30 = findViewById(R.id.btn_timer_30)
        btnTimer60 = findViewById(R.id.btn_timer_60)
        btnTimerCancel = findViewById(R.id.btn_timer_cancel)
        timerActiveContainer = findViewById(R.id.timer_active_container)
        timerRemainingText = findViewById(R.id.timer_remaining_text)

        batterySaverSwitch = findViewById(R.id.battery_saver_switch)

        crossfadeDurationSeekBar.max = MAX_CROSSFADE_DURATION - MIN_CROSSFADE_DURATION
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val automixEnabled = prefs.getBoolean(KEY_AUTOMIX_ENABLED, false)
        val crossfadeDuration = prefs.getInt(KEY_CROSSFADE_DURATION, DEFAULT_CROSSFADE_DURATION)
        automixSwitch.isChecked = automixEnabled
        updateCrossfadeSettingsVisibility(automixEnabled)
        crossfadeDurationSeekBar.progress = crossfadeDuration - MIN_CROSSFADE_DURATION
        updateCrossfadeDurationText(crossfadeDuration)

        val batterySaverEnabled = prefs.getBoolean(KEY_BATTERY_SAVER, true)
        batterySaverSwitch.isChecked = batterySaverEnabled
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

        btnTimer5.setOnClickListener { startSleepTimer(5) }
        btnTimer10.setOnClickListener { startSleepTimer(10) }
        btnTimer15.setOnClickListener { startSleepTimer(15) }
        btnTimer30.setOnClickListener { startSleepTimer(30) }
        btnTimer60.setOnClickListener { startSleepTimer(60) }

        btnTimerCancel.setOnClickListener {
            sleepTimerManager.stopTimer()
            updateTimerUI()
            stopTimerUpdates()
        }

        batterySaverSwitch.setOnCheckedChangeListener { _, isChecked ->
            saveBatterySaverEnabled(isChecked)
        }
    }

    private fun startSleepTimer(minutes: Int) {
        sleepTimerManager.startTimer(minutes, musicPlayer)
        updateTimerUI()
        startTimerUpdates()
    }

    private fun updateTimerUI() {
        if (sleepTimerManager.isTimerActive()) {
            timerActiveContainer.visibility = View.VISIBLE
            timerRemainingText.text = sleepTimerManager.getFormattedRemainingTime()
        } else {
            timerActiveContainer.visibility = View.GONE
            stopTimerUpdates()
        }
    }

    private fun startTimerUpdates() {
        timerHandler.post(timerUpdateRunnable)
    }

    private fun stopTimerUpdates() {
        timerHandler.removeCallbacks(timerUpdateRunnable)
    }

    private fun updateCrossfadeSettingsVisibility(automixEnabled: Boolean) {
        crossfadeSettingsContainer.visibility = if (automixEnabled) {
            View.VISIBLE
        } else {
            View.GONE
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

    private fun saveBatterySaverEnabled(enabled: Boolean) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_BATTERY_SAVER, enabled).apply()
    }

    override fun onResume() {
        super.onResume()
        if (sleepTimerManager.isTimerActive()) {
            updateTimerUI()
            startTimerUpdates()
        }
    }

    override fun onPause() {
        super.onPause()
        stopTimerUpdates()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTimerUpdates()
    }
}