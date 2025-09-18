package com.example.musiclab

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class FolderSongsActivity : AppCompatActivity() {

    private lateinit var backButton: ImageButton
    private lateinit var folderNameText: TextView
    private lateinit var songCountText: TextView
    private lateinit var songsRecyclerView: RecyclerView
    private lateinit var songAdapter: SongAdapter
    private lateinit var musicPlayer: MusicPlayer

    private var folderSongs: List<Song> = emptyList()
    private var folderName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_folder_songs)

        Log.d("FolderSongsActivity", "=== FOLDER SONGS ACTIVITY CREATED ===")

        // Ottieni i dati passati dall'intent
        folderName = intent.getStringExtra("FOLDER_NAME") ?: "Cartella"
        folderSongs = intent.getParcelableArrayListExtra<Song>("FOLDER_SONGS") ?: emptyList()

        Log.d("FolderSongsActivity", "📁 Folder: $folderName with ${folderSongs.size} songs")

        musicPlayer = MusicPlayerManager.getInstance().getMusicPlayer(this)

        setupViews()
        setupRecyclerView()
    }

    private fun setupViews() {
        backButton = findViewById(R.id.btn_back_folder)
        folderNameText = findViewById(R.id.folder_name_title)
        songCountText = findViewById(R.id.folder_song_count_text)
        songsRecyclerView = findViewById(R.id.folder_songs_recycler)

        folderNameText.text = folderName
        songCountText.text = getString(R.string.songs_in_folder, folderSongs.size)

        backButton.setOnClickListener {
            Log.d("FolderSongsActivity", "🔙 Back button clicked")
            finish()
        }
    }

    private fun setupRecyclerView() {
        songsRecyclerView.layoutManager = LinearLayoutManager(this)

        songAdapter = SongAdapter(folderSongs) { song ->
            onSongClick(song)
        }
        songsRecyclerView.adapter = songAdapter

        Log.d("FolderSongsActivity", "📱 RecyclerView setup with ${folderSongs.size} songs")
    }

    private fun onSongClick(song: Song) {
        Log.d("FolderSongsActivity", "🎵 Song clicked: ${song.title}")

        // Imposta la playlist come le canzoni di questa cartella
        musicPlayer.setPlaylist(folderSongs, folderSongs.indexOf(song))
        musicPlayer.playSong(song)

        Log.d("FolderSongsActivity", "▶️ Playing song, returning to main")
        finish() // Torna indietro dopo aver avviato la riproduzione
    }
}