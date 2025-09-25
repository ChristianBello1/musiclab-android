package com.example.musiclab

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
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

        Log.d("FolderSongsActivity", "Folder: $folderName with ${folderSongs.size} songs")

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
            Log.d("FolderSongsActivity", "Back button clicked")
            finish()
        }
    }

    private fun setupRecyclerView() {
        songsRecyclerView.layoutManager = LinearLayoutManager(this)

        songAdapter = SongAdapter(
            songs = folderSongs,
            onSongClick = { song ->
                onSongClick(song)
            },
            onSongMenuClick = { song, action ->
                handleSongMenuAction(song, action)
            }
        )
        songsRecyclerView.adapter = songAdapter

        Log.d("FolderSongsActivity", "RecyclerView setup with ${folderSongs.size} songs")
    }

    private fun onSongClick(song: Song) {
        Log.d("FolderSongsActivity", "Song clicked: ${song.title}")

        // Imposta la playlist come le canzoni di questa cartella
        musicPlayer.setPlaylist(folderSongs, folderSongs.indexOf(song))
        musicPlayer.playSong(song)

        Log.d("FolderSongsActivity", "Playing song, returning to main")
        finish() // Torna indietro dopo aver avviato la riproduzione
    }

    // Gestisce le azioni del menu canzone
    private fun handleSongMenuAction(song: Song, action: SongAdapter.MenuAction) {
        when (action) {
            SongAdapter.MenuAction.ADD_TO_PLAYLIST -> {
                showAddToPlaylistDialog(song)
            }
            SongAdapter.MenuAction.SONG_DETAILS -> {
                showSongDetails(song)
            }
            SongAdapter.MenuAction.DELETE_FROM_DEVICE -> {
                showDeleteSongConfirmation(song)
            }
        }
    }

    private fun showAddToPlaylistDialog(song: Song) {
        // Per ora mostra un toast, dopo implementeremo le playlist
        Toast.makeText(
            this,
            "Aggiungi '${song.title}' a playlist (coming soon!)",
            Toast.LENGTH_SHORT
        ).show()
        Log.d("FolderSongsActivity", "Add to playlist requested for: ${song.title}")
    }

    private fun showSongDetails(song: Song) {
        val fileSize = formatFileSize(song.size)
        val message = """
        Titolo: ${song.title}
        Artista: ${song.artist}
        Album: ${song.album}
        Durata: ${song.getFormattedDuration()}
        Dimensione: $fileSize
        Percorso: ${song.path}
    """.trimIndent()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.song_details_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.close)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()

        Log.d("FolderSongsActivity", "Song details shown for: ${song.title}")
    }

    private fun showDeleteSongConfirmation(song: Song) {
        val message = getString(R.string.delete_song_message, song.title)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_song_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.delete_confirm)) { dialog, _ ->
                deleteSongFromDevice(song)
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()

        Log.d("FolderSongsActivity", "Delete confirmation shown for: ${song.title}")
    }

    private fun deleteSongFromDevice(song: Song) {
        try {
            val file = java.io.File(song.path)
            if (file.exists() && file.delete()) {
                // Rimuovi dalla lista locale e aggiorna adapter
                folderSongs = folderSongs.filter { it.id != song.id }
                songAdapter.updateSongs(folderSongs)

                // Aggiorna il contatore
                songCountText.text = getString(R.string.songs_in_folder, folderSongs.size)

                Toast.makeText(this, "Canzone eliminata", Toast.LENGTH_SHORT).show()
                Log.d("FolderSongsActivity", "Song deleted successfully: ${song.title}")

                // Se non ci sono più canzoni, chiudi l'activity
                if (folderSongs.isEmpty()) {
                    Toast.makeText(this, "Cartella vuota", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } else {
                Toast.makeText(this, "Errore durante l'eliminazione", Toast.LENGTH_SHORT).show()
                Log.e("FolderSongsActivity", "Failed to delete song: ${song.title}")
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Errore: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e("FolderSongsActivity", "Error deleting song: $e")
        }
    }

    private fun formatFileSize(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0

        return when {
            gb >= 1.0 -> String.format("%.1f GB", gb)
            mb >= 1.0 -> String.format("%.1f MB", mb)
            kb >= 1.0 -> String.format("%.1f KB", kb)
            else -> "$bytes bytes"
        }
    }
}