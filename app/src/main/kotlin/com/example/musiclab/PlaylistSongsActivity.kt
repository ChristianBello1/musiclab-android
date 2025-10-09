package com.example.musiclab

import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore

class PlaylistSongsActivity : AppCompatActivity() {

    private lateinit var backButton: ImageButton
    private lateinit var playlistNameText: TextView
    private lateinit var songCountText: TextView
    private lateinit var songsRecyclerView: RecyclerView
    private lateinit var songAdapter: SongAdapter
    private lateinit var musicPlayer: MusicPlayer
    private lateinit var btnPlaylistMenu: ImageButton

    private var playlist: Playlist? = null
    private var playlistSongs: MutableList<Song> = mutableListOf()
    private val firestore = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_playlist_songs)

        Log.d("PlaylistSongsActivity", "=== PLAYLIST SONGS ACTIVITY CREATED ===")

        // Ottieni la playlist dall'intent
        val playlistId = intent.getStringExtra("PLAYLIST_ID") ?: ""
        val playlistName = intent.getStringExtra("PLAYLIST_NAME") ?: "Playlist"
        val playlistOwnerId = intent.getStringExtra("PLAYLIST_OWNER_ID") ?: ""

        playlist = Playlist(
            id = playlistId,
            name = playlistName,
            ownerId = playlistOwnerId
        )

        musicPlayer = MusicPlayerManager.getInstance().getMusicPlayer(this)

        setupViews()
        setupRecyclerView()
        loadPlaylistSongs()
    }

    private fun setupViews() {
        backButton = findViewById(R.id.btn_back_playlist)
        playlistNameText = findViewById(R.id.playlist_name_title)
        songCountText = findViewById(R.id.playlist_song_count_text)
        songsRecyclerView = findViewById(R.id.playlist_songs_recycler)
        btnPlaylistMenu = findViewById(R.id.btn_playlist_menu)

        playlistNameText.text = playlist?.name ?: "Playlist"

        backButton.setOnClickListener {
            Log.d("PlaylistSongsActivity", "Back button clicked")
            finish()
        }

        btnPlaylistMenu.setOnClickListener {
            showPlaylistMenu()
        }
    }

    private fun setupRecyclerView() {
        songsRecyclerView.layoutManager = LinearLayoutManager(this)

        songAdapter = SongAdapter(
            songs = playlistSongs,
            onSongClick = { song ->
                onSongClick(song)
            },
            onSongMenuClick = { song, action ->
                handleSongMenuAction(song, action)
            },
            contextType = SongAdapter.ContextType.PLAYLIST // NUOVO: Specifica che siamo in una playlist
        )
        songsRecyclerView.adapter = songAdapter

        Log.d("PlaylistSongsActivity", "RecyclerView setup")
    }

    private fun loadPlaylistSongs() {
        Log.d("PlaylistSongsActivity", "Loading songs for playlist: ${playlist?.id}")

        playlist?.let { pl ->
            firestore.collection("playlists")
                .document(pl.id)
                .collection("songs")
                .get()
                .addOnSuccessListener { documents ->
                    playlistSongs.clear()

                    Log.d("PlaylistSongsActivity", "Firestore returned ${documents.size()} songs")

                    for (doc in documents) {
                        try {
                            val song = Song(
                                id = doc.getLong("id") ?: 0L,
                                title = doc.getString("title") ?: "Unknown",
                                artist = doc.getString("artist") ?: "Unknown",
                                album = doc.getString("album") ?: "Unknown",
                                duration = doc.getLong("duration") ?: 0L,
                                path = doc.getString("path") ?: "",
                                size = doc.getLong("size") ?: 0L
                            )
                            playlistSongs.add(song)
                            Log.d("PlaylistSongsActivity", "Loaded song: ${song.title}")
                        } catch (e: Exception) {
                            Log.e("PlaylistSongsActivity", "Error parsing song: ${e.message}")
                        }
                    }

                    songAdapter.updateSongs(playlistSongs)
                    updateSongCount()

                    Log.d("PlaylistSongsActivity", "✅ Loaded ${playlistSongs.size} songs")
                }
                .addOnFailureListener { e ->
                    Log.e("PlaylistSongsActivity", "Error loading songs: ${e.message}")
                    Toast.makeText(this, "Errore caricamento canzoni", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun updateSongCount() {
        songCountText.text = getString(R.string.songs_in_playlist, playlistSongs.size)
    }

    private fun onSongClick(song: Song) {
        Log.d("PlaylistSongsActivity", "Song clicked: ${song.title}")

        // Riproduci la playlist partendo dalla canzone selezionata
        musicPlayer.setPlaylist(playlistSongs, playlistSongs.indexOf(song))
        musicPlayer.playSong(song)

        Log.d("PlaylistSongsActivity", "Playing song from playlist")
    }

    private fun handleSongMenuAction(song: Song, action: SongAdapter.MenuAction) {
        when (action) {
            SongAdapter.MenuAction.ADD_TO_PLAYLIST -> {
                Toast.makeText(this, "Canzone già in una playlist", Toast.LENGTH_SHORT).show()
            }
            SongAdapter.MenuAction.REMOVE_FROM_PLAYLIST -> {
                // NUOVO: Gestisci rimozione da playlist
                showRemoveFromPlaylistConfirmation(song)
            }
            SongAdapter.MenuAction.SONG_DETAILS -> {
                showSongDetails(song)
            }
            SongAdapter.MenuAction.DELETE_FROM_DEVICE -> {
                showRemoveFromPlaylistConfirmation(song)
            }
        }
    }

    private fun showSongDetails(song: Song) {
        val fileSize = formatFileSize(song.size)
        val message = """
        Titolo: ${song.title}
        Artista: ${song.artist}
        Album: ${song.album}
        Durata: ${song.getFormattedDuration()}
        Dimensione: $fileSize
    """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.song_details_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.close)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showRemoveFromPlaylistConfirmation(song: Song) {
        AlertDialog.Builder(this)
            .setTitle("Rimuovi da Playlist")
            .setMessage("Vuoi rimuovere '${song.title}' da questa playlist?")
            .setPositiveButton("Rimuovi") { dialog, _ ->
                removeSongFromPlaylist(song)
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun removeSongFromPlaylist(song: Song) {
        Log.d("PlaylistSongsActivity", "Removing song from playlist: ${song.title}")

        playlist?.let { pl ->
            firestore.collection("playlists")
                .document(pl.id)
                .collection("songs")
                .document(song.id.toString())
                .delete()
                .addOnSuccessListener {
                    playlistSongs.remove(song)
                    songAdapter.updateSongs(playlistSongs)
                    updateSongCount()

                    Toast.makeText(this, "Canzone rimossa dalla playlist", Toast.LENGTH_SHORT).show()
                    Log.d("PlaylistSongsActivity", "✅ Song removed successfully")

                    // Se non ci sono più canzoni, torna indietro
                    if (playlistSongs.isEmpty()) {
                        finish()
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("PlaylistSongsActivity", "Error removing song: ${e.message}")
                    Toast.makeText(this, "Errore rimozione canzone", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun showPlaylistMenu() {
        val options = arrayOf(
            "Rinomina Playlist",
            "Elimina Playlist"
        )

        AlertDialog.Builder(this)
            .setTitle("Gestisci Playlist")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenamePlaylistDialog()
                    1 -> showDeletePlaylistConfirmation()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showRenamePlaylistDialog() {
        val editText = EditText(this)
        editText.setText(playlist?.name ?: "")

        AlertDialog.Builder(this)
            .setTitle("Rinomina Playlist")
            .setView(editText)
            .setPositiveButton("Salva") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    renamePlaylist(newName)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun renamePlaylist(newName: String) {
        Log.d("PlaylistSongsActivity", "Renaming playlist to: $newName")

        playlist?.let { pl ->
            firestore.collection("playlists")
                .document(pl.id)
                .update("name", newName)
                .addOnSuccessListener {
                    playlist = pl.copy(name = newName)
                    playlistNameText.text = newName

                    Toast.makeText(this, "Playlist rinominata", Toast.LENGTH_SHORT).show()
                    Log.d("PlaylistSongsActivity", "✅ Playlist renamed successfully")
                }
                .addOnFailureListener { e ->
                    Log.e("PlaylistSongsActivity", "Error renaming playlist: ${e.message}")
                    Toast.makeText(this, "Errore rinomina", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun showDeletePlaylistConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Elimina Playlist")
            .setMessage("Sei sicuro di voler eliminare '${playlist?.name}'? Questa azione non può essere annullata.")
            .setPositiveButton("Elimina") { _, _ ->
                deletePlaylist()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun deletePlaylist() {
        Log.d("PlaylistSongsActivity", "Deleting playlist: ${playlist?.id}")

        playlist?.let { pl ->
            // Prima elimina tutte le canzoni
            firestore.collection("playlists")
                .document(pl.id)
                .collection("songs")
                .get()
                .addOnSuccessListener { documents ->
                    // Elimina ogni canzone
                    for (doc in documents) {
                        doc.reference.delete()
                    }

                    // Poi elimina la playlist
                    firestore.collection("playlists")
                        .document(pl.id)
                        .delete()
                        .addOnSuccessListener {
                            Toast.makeText(this, "Playlist eliminata", Toast.LENGTH_SHORT).show()
                            Log.d("PlaylistSongsActivity", "✅ Playlist deleted successfully")
                            setResult(RESULT_OK)
                            finish()
                        }
                        .addOnFailureListener { e ->
                            Log.e("PlaylistSongsActivity", "Error deleting playlist: ${e.message}")
                            Toast.makeText(this, "Errore eliminazione", Toast.LENGTH_SHORT).show()
                        }
                }
                .addOnFailureListener { e ->
                    Log.e("PlaylistSongsActivity", "Error deleting songs: ${e.message}")
                    Toast.makeText(this, "Errore eliminazione", Toast.LENGTH_SHORT).show()
                }
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