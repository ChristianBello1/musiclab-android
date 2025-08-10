package com.example.musiclab

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var musicScanner: MusicScanner
    private lateinit var songAdapter: SongAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var songCountText: TextView
    private lateinit var musicPlayer: MusicPlayer
    private var songs: List<Song> = emptyList()

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

        // Inizializza MusicPlayer
        musicPlayer = MusicPlayer(this)
        musicPlayer.onPlayerStateChanged = { isPlaying, currentSong ->
            Log.d("MainActivity", "Player state: isPlaying=$isPlaying, song=${currentSong?.title}")
            // Qui aggiorneremo la UI quando aggiungiamo i controlli
        }

        checkPermissionsAndLoadMusic()
    }

    private fun setupViews() {
        recyclerView = findViewById(R.id.songs_recycler_view)
        songCountText = findViewById(R.id.song_count)

        // Setup RecyclerView
        recyclerView.layoutManager = LinearLayoutManager(this)
        songAdapter = SongAdapter(songs) { song ->
            onSongClick(song)
        }
        recyclerView.adapter = songAdapter
    }

    private fun onSongClick(song: Song) {
        // Ora riproduce davvero la canzone!
        musicPlayer.setPlaylist(songs, songs.indexOf(song))
        musicPlayer.playSong(song)
        Toast.makeText(this, getString(R.string.now_playing_format, song.title), Toast.LENGTH_SHORT).show()
        Log.d("MainActivity", "Riproduzione avviata: ${song.title} - ${song.artist}")
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

    override fun onDestroy() {
        super.onDestroy()
        musicPlayer.release()
    }
}