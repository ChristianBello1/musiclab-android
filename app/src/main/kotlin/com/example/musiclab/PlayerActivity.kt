package com.example.musiclab

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

class PlayerActivity : AppCompatActivity() {

    // Views
    private lateinit var backButton: ImageButton
    private lateinit var queueButton: ImageButton
    private lateinit var songTitle: TextView
    private lateinit var songArtist: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var currentTime: TextView
    private lateinit var totalTime: TextView

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

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            progressHandler.postDelayed(this, 1000) // Aggiorna ogni secondo
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        // Ottieni il MusicPlayer globale
        musicPlayer = MusicPlayerManager.getInstance().getMusicPlayer(this)

        setupViews()
        setupClickListeners()
        updateUI()
        startProgressUpdates()
    }

    private fun setupViews() {
        backButton = findViewById(R.id.btn_back)
        queueButton = findViewById(R.id.btn_queue)
        songTitle = findViewById(R.id.player_song_title)
        songArtist = findViewById(R.id.player_song_artist)
        seekBar = findViewById(R.id.player_seek_bar)
        currentTime = findViewById(R.id.player_current_time)
        totalTime = findViewById(R.id.player_total_time)

        skipBack10Button = findViewById(R.id.btn_skip_back_10)
        previousButton = findViewById(R.id.btn_player_previous)
        playPauseButton = findViewById(R.id.btn_player_play_pause)
        nextButton = findViewById(R.id.btn_player_next)
        skipForward10Button = findViewById(R.id.btn_skip_forward_10)
        shuffleButton = findViewById(R.id.btn_shuffle)
        repeatButton = findViewById(R.id.btn_repeat)

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
        backButton.setOnClickListener { finish() }

        queueButton.setOnClickListener {
            // TODO: Aprire QueueActivity
            Log.d("PlayerActivity", "Queue button clicked")
        }

        skipBack10Button.setOnClickListener {
            musicPlayer.skipBackward(10000) // 10 secondi
        }

        previousButton.setOnClickListener {
            musicPlayer.playPrevious()
        }

        playPauseButton.setOnClickListener {
            musicPlayer.playPause()
            updatePlayPauseButton()
        }

        nextButton.setOnClickListener {
            musicPlayer.playNext()
        }

        skipForward10Button.setOnClickListener {
            musicPlayer.skipForward(10000) // 10 secondi
        }

        shuffleButton.setOnClickListener {
            val isShuffleEnabled = musicPlayer.toggleShuffle()
            updateShuffleButton(isShuffleEnabled)
        }

        repeatButton.setOnClickListener {
            val repeatMode = musicPlayer.toggleRepeat()
            updateRepeatButton(repeatMode)
        }

        // Listener per cambiamenti di stato del player
        musicPlayer.onPlayerStateChanged = { isPlaying, currentSong ->
            runOnUiThread {
                updatePlayPauseButton()
                updateSongInfo()
            }
        }
    }

    private fun updateUI() {
        updateSongInfo()
        updatePlayPauseButton()
        updateShuffleButton(musicPlayer.isShuffleEnabled())
        updateRepeatButton(musicPlayer.getRepeatMode())
    }

    private fun updateSongInfo() {
        val currentSong = musicPlayer.getCurrentSong()
        if (currentSong != null) {
            songTitle.text = currentSong.title
            songArtist.text = currentSong.artist
            totalTime.text = currentSong.getFormattedDuration()
            seekBar.max = (currentSong.duration / 1000).toInt()
        } else {
            songTitle.text = getString(R.string.no_song_playing)
            songArtist.text = getString(R.string.unknown_artist)
            totalTime.text = getString(R.string.time_format)
            seekBar.max = 100
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
    }

    private fun updateShuffleButton(isEnabled: Boolean) {
        val colorRes = if (isEnabled) {
            ContextCompat.getColor(this, R.color.purple_500)
        } else {
            ContextCompat.getColor(this, android.R.color.darker_gray)
        }
        shuffleButton.setColorFilter(colorRes)
    }

    private fun updateRepeatButton(repeatMode: Int) {
        val colorRes = if (repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF) {
            ContextCompat.getColor(this, R.color.purple_500)
        } else {
            ContextCompat.getColor(this, android.R.color.darker_gray)
        }
        repeatButton.setColorFilter(colorRes)
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
    }

    private fun stopProgressUpdates() {
        progressHandler.removeCallbacks(progressRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProgressUpdates()
    }

    override fun onPause() {
        super.onPause()
        stopProgressUpdates()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
        startProgressUpdates()
    }
}