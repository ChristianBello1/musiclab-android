package com.example.musiclab

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale
import androidx.core.widget.ImageViewCompat
import android.content.res.ColorStateList
import androidx.media3.common.Player

class PlayerActivity : AppCompatActivity() {

    // Views
    private lateinit var backButton: ImageButton
    private lateinit var queueButton: ImageButton
    private lateinit var songTitle: TextView
    private lateinit var songArtist: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var currentTime: TextView
    private lateinit var totalTime: TextView

    private lateinit var settingsButton: ImageButton

    // Control buttons
    private lateinit var skipBack10Button: ImageButton
    private lateinit var previousButton: ImageButton
    private lateinit var playPauseButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var skipForward10Button: ImageButton
    private lateinit var shuffleButton: ImageButton
    private lateinit var repeatButton: ImageButton

    // Player state
    private lateinit var musicPlayer: MusicPlayer
    private val progressHandler = Handler(Looper.getMainLooper())
    private var isUpdatingProgress = false

    // NUOVO: Listener per cambiamenti della coda (shuffle)
    private val playerQueueChangeListener: () -> Unit = {
        runOnUiThread {
            Log.d("PlayerActivity", "🔄 Queue changed - updating shuffle/repeat UI")
            updateShuffleButton(musicPlayer.isShuffleEnabled())
            updateRepeatButton(musicPlayer.getRepeatMode())
            updateSongInfo() // Potrebbe essere cambiata la canzone corrente
        }
    }

    // Listener per cambiamenti di stato del player
    private val playerActivityListener: (Boolean, Song?) -> Unit = { isPlaying, currentSong ->
        runOnUiThread {
            updatePlayPauseButton()
            updateSongInfo()
            updateShuffleButton(musicPlayer.isShuffleEnabled())
            updateRepeatButton(musicPlayer.getRepeatMode())
            Log.d("PlayerActivity", "Player state updated: playing=$isPlaying, song=${currentSong?.title}")
        }
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            progressHandler.postDelayed(this, 1000) // Aggiorna ogni secondo
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Nascondi ActionBar anche nel PlayerActivity
        supportActionBar?.hide()

        setContentView(R.layout.activity_player)

        Log.d("PlayerActivity", "=== CREAZIONE PLAYER ACTIVITY ===")

        // Ottieni il MusicPlayer globale
        musicPlayer = MusicPlayerManager.getInstance().getMusicPlayer(this)

        setupViews()
        setupClickListeners()
        updateUI()
        startProgressUpdates()

        Log.d("PlayerActivity", "=== SETUP COMPLETATO ===")
    }

    private fun setupViews() {
        Log.d("PlayerActivity", "=== SETUP VIEWS START ===")

        // Header views
        backButton = findViewById(R.id.btn_back)
        queueButton = findViewById(R.id.btn_queue)
        songTitle = findViewById(R.id.player_song_title)
        songArtist = findViewById(R.id.player_song_artist)
        seekBar = findViewById(R.id.player_seek_bar)
        currentTime = findViewById(R.id.player_current_time)
        totalTime = findViewById(R.id.player_total_time)
        settingsButton = findViewById(R.id.btn_settings)

        Log.d("PlayerActivity", "Header views caricati")

        // Main control buttons
        skipBack10Button = findViewById(R.id.btn_skip_back_10)
        previousButton = findViewById(R.id.btn_player_previous)
        playPauseButton = findViewById(R.id.btn_player_play_pause)
        nextButton = findViewById(R.id.btn_player_next)
        skipForward10Button = findViewById(R.id.btn_skip_forward_10)

        Log.d("PlayerActivity", "Main control buttons caricati")

        // Secondary control buttons
        try {
            shuffleButton = findViewById(R.id.btn_shuffle)
            repeatButton = findViewById(R.id.btn_repeat)

            // Assicurati che siano visibili e cliccabili
            shuffleButton.visibility = View.VISIBLE
            repeatButton.visibility = View.VISIBLE
            shuffleButton.isClickable = true
            repeatButton.isClickable = true
            shuffleButton.alpha = 1.0f
            repeatButton.alpha = 1.0f

            Log.d("PlayerActivity", "Pulsanti shuffle/repeat configurati come visibili")

        } catch (e: Exception) {
            Log.e("PlayerActivity", "Error finding secondary buttons: $e")
            e.printStackTrace()
        }

        Log.d("PlayerActivity", "=== SETUP VIEWS END ===")

        // Setup SeekBar
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUpdatingProgress = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUpdatingProgress = false
                seekBar?.let { bar ->
                    val position = bar.progress
                    musicPlayer.seekTo(position)
                }
            }

            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentTime.text = formatTime(progress)
                }
            }
        })
    }

    private fun setupClickListeners() {
        Log.d("PlayerActivity", "=== SETUP CLICK LISTENERS START ===")

        backButton.setOnClickListener {
            Log.d("PlayerActivity", "Back button clicked")
            finish()
        }

        queueButton.setOnClickListener {
            Log.d("PlayerActivity", "Queue button clicked - opening QueueActivity")
            val intent = Intent(this, QueueActivity::class.java)
            startActivity(intent)
        }

        skipBack10Button.setOnClickListener {
            Log.d("PlayerActivity", "Skip back 10s clicked")
            musicPlayer.skipBackward(10000)
        }

        previousButton.setOnClickListener {
            Log.d("PlayerActivity", "Previous clicked")
            musicPlayer.playPrevious()
        }

        playPauseButton.setOnClickListener {
            Log.d("PlayerActivity", "Play/Pause clicked")
            musicPlayer.playPause()
        }

        nextButton.setOnClickListener {
            Log.d("PlayerActivity", "Next clicked")
            musicPlayer.playNext()
        }

        skipForward10Button.setOnClickListener {
            Log.d("PlayerActivity", "Skip forward 10s clicked")
            musicPlayer.skipForward(10000)
        }

        settingsButton.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        // MIGLIORATO: Click listeners per shuffle e repeat
        try {
            shuffleButton.setOnClickListener {
                Log.d("PlayerActivity", "🔀 SHUFFLE BUTTON CLICKED!")
                val isShuffleEnabled = musicPlayer.toggleShuffle()
                Log.d("PlayerActivity", "✅ Shuffle toggled to: $isShuffleEnabled")
                // Non chiamare updateShuffleButton qui - il listener se ne occuperà
            }

            repeatButton.setOnClickListener {
                Log.d("PlayerActivity", "🔁 REPEAT BUTTON CLICKED!")
                val repeatMode = musicPlayer.toggleRepeat()
                Log.d("PlayerActivity", "✅ Repeat toggled to mode: $repeatMode")
                // Non chiamare updateRepeatButton qui - il listener se ne occuperà
            }

            Log.d("PlayerActivity", "✅ Shuffle/Repeat click listeners configurati")

        } catch (e: Exception) {
            Log.e("PlayerActivity", "❌ Error setting up shuffle/repeat listeners: $e")
        }

        // AGGIORNATO: Aggiungi entrambi i listener
        musicPlayer.addStateChangeListener(playerActivityListener)
        musicPlayer.addQueueChangeListener(playerQueueChangeListener)

        Log.d("PlayerActivity", "=== SETUP CLICK LISTENERS END ===")
    }

    private fun updateUI() {
        Log.d("PlayerActivity", "=== UPDATE UI START ===")

        updateSongInfo()
        updatePlayPauseButton()
        updateShuffleButton(musicPlayer.isShuffleEnabled())
        updateRepeatButton(musicPlayer.getRepeatMode())

        Log.d("PlayerActivity", "=== UPDATE UI END ===")
    }

    private fun updateSongInfo() {
        val currentSong = musicPlayer.getCurrentSong()
        if (currentSong != null) {
            songTitle.text = currentSong.title
            songArtist.text = currentSong.artist
            totalTime.text = currentSong.getFormattedDuration()
            seekBar.max = (currentSong.duration / 1000).toInt()
            Log.d("PlayerActivity", "Song info updated: ${currentSong.title}")
        } else {
            songTitle.text = getString(R.string.no_song_playing)
            songArtist.text = getString(R.string.unknown_artist)
            totalTime.text = getString(R.string.time_format)
            seekBar.max = 100
            Log.d("PlayerActivity", "No song playing")
        }
    }

    private fun updatePlayPauseButton() {
        val isPlaying = musicPlayer.isPlaying()
        val iconRes = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        playPauseButton.setImageResource(iconRes)
        Log.d("PlayerActivity", "Play/Pause updated: playing=$isPlaying")
    }

    // MIGLIORATO: Aggiornamento UI con logging esteso
    private fun updateShuffleButton(isEnabled: Boolean) {
        Log.d("PlayerActivity", "🔄 Updating shuffle button: enabled=$isEnabled")

        try {
            val colorRes = if (isEnabled) {
                ContextCompat.getColor(this, R.color.purple_500)
            } else {
                ContextCompat.getColor(this, android.R.color.darker_gray)
            }
            shuffleButton.setColorFilter(colorRes)

            Log.d("PlayerActivity", "✅ Shuffle button updated - color: ${if (isEnabled) "purple" else "gray"}")
        } catch (e: Exception) {
            Log.e("PlayerActivity", "❌ Error updating shuffle button: $e")
        }
    }

    private fun updateRepeatButton(repeatMode: Int) {
        Log.d("PlayerActivity", "🔄 Updating repeat button: mode=$repeatMode")

        try {
            val iconRes = when (repeatMode) {
                Player.REPEAT_MODE_OFF -> R.drawable.ic_repeat
                Player.REPEAT_MODE_ONE -> R.drawable.ic_repeat_one
                Player.REPEAT_MODE_ALL -> R.drawable.ic_repeat_active
                else -> R.drawable.ic_repeat
            }

            repeatButton.setImageResource(iconRes)

            val colorRes = if (repeatMode != Player.REPEAT_MODE_OFF) {
                ContextCompat.getColor(this, R.color.primary_purple)
            } else {
                ContextCompat.getColor(this, R.color.text_secondary)
            }

            ImageViewCompat.setImageTintList(
                repeatButton,
                ColorStateList.valueOf(colorRes)
            )

            Log.d("PlayerActivity", "✅ Repeat button updated")
        } catch (e: Exception) {
            Log.e("PlayerActivity", "❌ Error updating repeat button: $e")
        }
    }

    private fun updateProgress() {
        if (!isUpdatingProgress) {
            val currentPosition = musicPlayer.getCurrentPosition()
            seekBar.progress = (currentPosition / 1000).toInt()
            currentTime.text = formatTime((currentPosition / 1000).toInt())
        }
    }

    private fun formatTime(seconds: Int): String {
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, remainingSeconds)
    }

    private fun startProgressUpdates() {
        progressHandler.post(progressRunnable)
        Log.d("PlayerActivity", "Progress updates started")
    }

    private fun stopProgressUpdates() {
        progressHandler.removeCallbacks(progressRunnable)
        Log.d("PlayerActivity", "Progress updates stopped")
    }


    override fun onDestroy() {
        super.onDestroy()
        stopProgressUpdates()

        // AGGIORNATO: Rimuovi entrambi i listener per evitare memory leak
        musicPlayer.removeStateChangeListener(playerActivityListener)
        musicPlayer.removeQueueChangeListener(playerQueueChangeListener)

        Log.d("PlayerActivity", "PlayerActivity destroyed, all listeners removed")
    }

    override fun onPause() {
        super.onPause()
        stopProgressUpdates()
        Log.d("PlayerActivity", "PlayerActivity paused")
    }

    override fun onResume() {
        super.onResume()
        Log.d("PlayerActivity", "=== ON RESUME ===")
        updateUI()
        startProgressUpdates()

        // Debug: Re-check pulsanti dopo resume
        try {
            Log.d("PlayerActivity", "Post-resume shuffle visible: ${shuffleButton.visibility}")
            Log.d("PlayerActivity", "Post-resume repeat visible: ${repeatButton.visibility}")
            Log.d("PlayerActivity", "Post-resume shuffle clickable: ${shuffleButton.isClickable}")
            Log.d("PlayerActivity", "Post-resume repeat clickable: ${repeatButton.isClickable}")
            Log.d("PlayerActivity", "Resumed - automix enabled: ${SettingsActivity.isAutomixEnabled(this)}")
        } catch (e: Exception) {
            Log.e("PlayerActivity", "Error checking buttons post-resume: $e")
        }
    }
}