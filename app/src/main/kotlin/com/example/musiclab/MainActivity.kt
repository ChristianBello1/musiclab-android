package com.example.musiclab

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var musicScanner: MusicScanner
    private lateinit var songAdapter: SongAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var songCountText: TextView
    private lateinit var musicPlayer: MusicPlayer
    private var songs: List<Song> = emptyList()

    // Player Bottom Bar Views
    private lateinit var playerBottomContainer: LinearLayout
    private lateinit var currentSongTitle: TextView
    private lateinit var currentSongArtist: TextView
    private lateinit var btnBottomPrevious: ImageButton
    private lateinit var btnBottomPlayPause: ImageButton
    private lateinit var btnBottomNext: ImageButton
    private lateinit var btnExpandPlayer: ImageButton

    // Progress bar views
    private lateinit var bottomSeekBar: SeekBar
    private lateinit var bottomCurrentTime: TextView
    private lateinit var bottomTotalTime: TextView

    // Progress handler
    private val progressHandler = Handler(Looper.getMainLooper())
    private var isUpdatingBottomProgress = false

    private val bottomProgressRunnable = object : Runnable {
        override fun run() {
            updateBottomProgress()
            progressHandler.postDelayed(this, 1000) // Aggiorna ogni secondo
        }
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            loadMusicFiles()
        } else {
            Toast.makeText(this, getString(R.string.permissions_needed), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupViews()
        musicScanner = MusicScanner(this)

        // Usa il MusicPlayer globale
        musicPlayer = MusicPlayerManager.getInstance().getMusicPlayer(this)

        // Setup player state listener
        musicPlayer.onPlayerStateChanged = { isPlaying, currentSong ->
            runOnUiThread {
                updatePlayerBottomBar(isPlaying, currentSong)
            }
        }

        checkPermissionsAndLoadMusic()
    }

    private fun setupViews() {
        recyclerView = findViewById(R.id.songs_recycler_view)
        songCountText = findViewById(R.id.song_count)

        // Setup Player Bottom Bar
        playerBottomContainer = findViewById(R.id.player_bottom_container)
        currentSongTitle = findViewById(R.id.current_song_title)
        currentSongArtist = findViewById(R.id.current_song_artist)
        btnBottomPrevious = findViewById(R.id.btn_previous)
        btnBottomPlayPause = findViewById(R.id.btn_play_pause)
        btnBottomNext = findViewById(R.id.btn_next)
        btnExpandPlayer = findViewById(R.id.btn_expand_player)

        // Setup Progress bar views
        bottomSeekBar = findViewById(R.id.seek_bar)
        bottomCurrentTime = findViewById(R.id.current_time)
        bottomTotalTime = findViewById(R.id.total_time)

        // Setup RecyclerView
        recyclerView.layoutManager = LinearLayoutManager(this)
        songAdapter = SongAdapter(songs) { song ->
            onSongClick(song)
        }
        recyclerView.adapter = songAdapter

        setupPlayerBottomBarListeners()
    }

    private fun setupPlayerBottomBarListeners() {
        btnBottomPrevious.setOnClickListener {
            musicPlayer.playPrevious()
        }

        btnBottomPlayPause.setOnClickListener {
            musicPlayer.playPause()
        }

        btnBottomNext.setOnClickListener {
            musicPlayer.playNext()
        }

        // NUOVO: Pulsante coda invece di espandere player
        btnExpandPlayer.setOnClickListener {
            // TODO: Aprire QueueActivity
            Log.d("MainActivity", "Queue button clicked - opening queue...")
            Toast.makeText(this, "Coda di riproduzione (coming soon!)", Toast.LENGTH_SHORT).show()
        }

        // Click su tutta la barra per aprire il player (ESCLUSO il pulsante coda)
        playerBottomContainer.setOnClickListener { view ->
            // Controlla se il click è sul pulsante coda
            if (view != btnExpandPlayer) {
                openPlayerActivity()
            }
        }

        // Setup area song info per aprire player
        val songInfoArea = playerBottomContainer.getChildAt(0) // Il LinearLayout con le song info
        songInfoArea.setOnClickListener {
            openPlayerActivity()
        }

        // Setup SeekBar per la barra in basso
        bottomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUpdatingBottomProgress = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUpdatingBottomProgress = false
                seekBar?.let { bar ->
                    val position = bar.progress
                    musicPlayer.seekTo(position)
                    Log.d("MainActivity", "Bottom seekbar: seeked to $position seconds")
                }
            }

            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    bottomCurrentTime.text = formatTime(progress)
                }
            }
        })
    }

    private fun onSongClick(song: Song) {
        // Imposta playlist e riproduci canzone
        musicPlayer.setPlaylist(songs, songs.indexOf(song))
        musicPlayer.playSong(song)

        // Mostra la barra del player e avvia progress update
        showPlayerBottomBar()
        startBottomProgressUpdates()

        Toast.makeText(this, getString(R.string.now_playing_format, song.title), Toast.LENGTH_SHORT).show()
        Log.d("MainActivity", "Riproduzione avviata: ${song.title} - ${song.artist}")
    }

    private fun updatePlayerBottomBar(isPlaying: Boolean, currentSong: Song?) {
        if (currentSong != null) {
            // Mostra la barra se c'è una canzone
            showPlayerBottomBar()

            // Aggiorna informazioni canzone
            currentSongTitle.text = currentSong.title
            currentSongArtist.text = currentSong.artist

            // Aggiorna durata totale e max della seekbar
            bottomTotalTime.text = currentSong.getFormattedDuration()
            bottomSeekBar.max = (currentSong.duration / 1000).toInt()

            // Aggiorna icona play/pause
            val iconRes = if (isPlaying) {
                android.R.drawable.ic_media_pause
            } else {
                android.R.drawable.ic_media_play
            }
            btnBottomPlayPause.setImageResource(iconRes)

            // Avvia progress updates se sta suonando
            if (isPlaying) {
                startBottomProgressUpdates()
            }
        } else {
            // Nascondi la barra se non c'è musica
            hidePlayerBottomBar()
            stopBottomProgressUpdates()
        }
    }

    private fun updateBottomProgress() {
        if (!isUpdatingBottomProgress && musicPlayer.isPlaying()) {
            val currentPosition = musicPlayer.getCurrentPosition()
            val currentSeconds = (currentPosition / 1000).toInt()

            bottomSeekBar.progress = currentSeconds
            bottomCurrentTime.text = formatTime(currentSeconds)
        }
    }

    private fun formatTime(seconds: Int): String {
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, remainingSeconds)
    }

    private fun startBottomProgressUpdates() {
        stopBottomProgressUpdates() // Stop existing updates
        progressHandler.post(bottomProgressRunnable)
        Log.d("MainActivity", "Started bottom progress updates")
    }

    private fun stopBottomProgressUpdates() {
        progressHandler.removeCallbacks(bottomProgressRunnable)
    }

    private fun showPlayerBottomBar() {
        if (playerBottomContainer.visibility != View.VISIBLE) {
            playerBottomContainer.visibility = View.VISIBLE
            Log.d("MainActivity", "Player bottom bar shown")
        }
    }

    private fun hidePlayerBottomBar() {
        if (playerBottomContainer.visibility != View.GONE) {
            playerBottomContainer.visibility = View.GONE
            stopBottomProgressUpdates()
            Log.d("MainActivity", "Player bottom bar hidden")
        }
    }

    private fun openPlayerActivity() {
        val intent = Intent(this, PlayerActivity::class.java)
        startActivity(intent)
    }

    private fun checkPermissionsAndLoadMusic() {
        val permissionsNeeded = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ usa READ_MEDIA_AUDIO
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else {
            // Android 12 e precedenti usano READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissionsNeeded.isNotEmpty()) {
            requestPermissions.launch(permissionsNeeded.toTypedArray())
        } else {
            loadMusicFiles()
        }
    }

    private fun loadMusicFiles() {
        try {
            songs = musicScanner.scanMusicFiles()

            // Aggiorna UI
            songCountText.text = getString(R.string.song_count_format, songs.size)
            songAdapter.updateSongs(songs)

            Toast.makeText(this, getString(R.string.songs_loaded_format, songs.size), Toast.LENGTH_SHORT).show()
            Log.d("MainActivity", "Caricate ${songs.size} canzoni")

        } catch (e: Exception) {
            Log.e("MainActivity", "Errore caricamento musica", e)
            Toast.makeText(this, getString(R.string.error_loading_music, e.message), Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Aggiorna la UI del player quando si torna alla MainActivity
        val currentSong = musicPlayer.getCurrentSong()
        val isPlaying = musicPlayer.isPlaying()
        updatePlayerBottomBar(isPlaying, currentSong)
    }

    override fun onPause() {
        super.onPause()
        stopBottomProgressUpdates()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBottomProgressUpdates()
        // Non rilasciare il player qui perché è globale
        // MusicPlayerManager.getInstance().release()
    }
}