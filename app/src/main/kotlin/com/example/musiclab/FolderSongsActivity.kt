package com.example.musiclab

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore
import androidx.activity.OnBackPressedCallback
import java.util.Locale

class FolderSongsActivity : AppCompatActivity() {

    private lateinit var backButton: ImageButton
    private lateinit var folderNameText: TextView
    private lateinit var songCountText: TextView
    private lateinit var songsRecyclerView: RecyclerView
    private lateinit var songAdapter: SongAdapter
    private lateinit var musicPlayer: MusicPlayer

    // Per selezione multipla
    private lateinit var btnAddSelectedToPlaylist: ImageButton
    private lateinit var btnCancelSelection: ImageButton
    private lateinit var selectionCountText: TextView
    private var isSelectionMode = false

    private var folderSongs: List<Song> = emptyList()
    private var folderName: String = ""

    // Per gestire playlist
    private lateinit var firestore: FirebaseFirestore
    private var userPlaylists: MutableList<Playlist> = mutableListOf()
    private var currentUserId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_folder_songs)

        Log.d("FolderSongsActivity", "=== FOLDER SONGS ACTIVITY CREATED ===")

        // ✅ FIX: Ottieni i dati passati dall'intent con supporto per API 33+
        folderName = intent.getStringExtra("FOLDER_NAME") ?: "Cartella"

        // ✅ FIX: Gestione corretta per getParcelableArrayListExtra deprecato
        folderSongs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra("FOLDER_SONGS", Song::class.java) ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra("FOLDER_SONGS") ?: emptyList()
        }

        currentUserId = intent.getStringExtra("USER_ID") ?: ""

        Log.d("FolderSongsActivity", "Folder: $folderName with ${folderSongs.size} songs")
        Log.d("FolderSongsActivity", "UserID: $currentUserId")

        musicPlayer = MusicPlayerManager.getInstance().getMusicPlayer(this)
        firestore = FirebaseFirestore.getInstance()

        setupViews()
        setupRecyclerView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isSelectionMode) {
                    exitSelectionMode()
                } else {
                    finish()
                }
            }
        })

        // Carica playlist se l'utente è loggato
        if (currentUserId.isNotEmpty()) {
            loadUserPlaylists()
        }
    }

    private fun setupViews() {
        backButton = findViewById(R.id.btn_back_folder)
        folderNameText = findViewById(R.id.folder_name_title)
        songCountText = findViewById(R.id.folder_song_count_text)
        songsRecyclerView = findViewById(R.id.folder_songs_recycler)

        // Bottoni per selezione multipla
        btnAddSelectedToPlaylist = findViewById(R.id.btn_add_selected_to_playlist)
        btnCancelSelection = findViewById(R.id.btn_cancel_selection)
        selectionCountText = findViewById(R.id.selection_count_text)

        folderNameText.text = folderName
        songCountText.text = getString(R.string.songs_in_folder, folderSongs.size)

        backButton.setOnClickListener {
            if (isSelectionMode) {
                exitSelectionMode()
            } else {
                finish()
            }
        }

        // Click listeners per selezione multipla
        btnAddSelectedToPlaylist.setOnClickListener {
            addSelectedSongsToPlaylist()
        }

        btnCancelSelection.setOnClickListener {
            exitSelectionMode()
        }
    }

    private fun setupRecyclerView() {
        songsRecyclerView.layoutManager = LinearLayoutManager(this)

        songAdapter = SongAdapter(
            songs = folderSongs,
            onSongClick = { song ->
                if (!isSelectionMode) {
                    onSongClick(song)
                }
            },
            onSongMenuClick = { song, action ->
                handleSongMenuAction(song, action)
            },
            onLongPress = { song ->
                // Entra in modalità selezione
                enterSelectionMode()
            },
            onSelectionChanged = { count ->
                updateSelectionUI(count)
            },
            contextType = SongAdapter.ContextType.FOLDER
        )

        songsRecyclerView.adapter = songAdapter
        Log.d("FolderSongsActivity", "RecyclerView setup with ${folderSongs.size} songs")
    }

    private fun onSongClick(song: Song) {
        Log.d("FolderSongsActivity", "Song clicked: ${song.title}")

        musicPlayer.setPlaylist(folderSongs, folderSongs.indexOf(song))
        musicPlayer.playSong(song)

        Toast.makeText(this, "Riproduzione: ${song.title}", Toast.LENGTH_SHORT).show()
    }

    private fun handleSongMenuAction(song: Song, action: SongAdapter.MenuAction) {
        when (action) {
            SongAdapter.MenuAction.SONG_DETAILS -> showSongDetails(song)
            SongAdapter.MenuAction.DELETE_FROM_DEVICE -> showDeleteSongConfirmation(song)
            SongAdapter.MenuAction.ADD_TO_PLAYLIST -> {
                if (currentUserId.isNotEmpty()) {
                    showAddToPlaylistDialog(song)
                } else {
                    Toast.makeText(this, "Devi effettuare il login per questa funzione", Toast.LENGTH_SHORT).show()
                }
            }
            SongAdapter.MenuAction.REMOVE_FROM_PLAYLIST -> {
                // Non applicabile in FolderSongsActivity
            }
        }
    }

    // Carica le playlist dell'utente
    private fun loadUserPlaylists() {
        Log.d("FolderSongsActivity", "Loading playlists for user: $currentUserId")

        firestore.collection("playlists")
            .whereEqualTo("ownerId", currentUserId)
            .get()
            .addOnSuccessListener { documents ->
                userPlaylists.clear()

                for (document in documents) {
                    val playlist = Playlist(
                        id = document.id,
                        name = document.getString("name") ?: "Playlist",
                        ownerId = document.getString("ownerId") ?: "",
                        createdAt = document.getLong("createdAt") ?: System.currentTimeMillis(),
                        isLocal = false
                    )
                    userPlaylists.add(playlist)
                }

                Log.d("FolderSongsActivity", "✅ Loaded ${userPlaylists.size} playlists")
            }
            .addOnFailureListener { e ->
                Log.e("FolderSongsActivity", "❌ Error loading playlists: ${e.message}")
            }
    }

    private fun showAddToPlaylistDialog(song: Song) {
        val playlistNames = userPlaylists.map { it.name }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Aggiungi a Playlist")
            .setItems(playlistNames) { _, which ->
                val selectedPlaylist = userPlaylists[which]
                addSongToPlaylist(song, selectedPlaylist.id, selectedPlaylist.name)
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun addSongToPlaylist(song: Song, playlistId: String, playlistName: String) {
        Log.d("FolderSongsActivity", "Adding '${song.title}' to playlist '$playlistName'")

        firestore.collection("playlists")
            .document(playlistId)
            .collection("songs")
            .document(song.id.toString())
            .get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    Toast.makeText(
                        this,
                        "'${song.title}' è già in '$playlistName'",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    // Salva mediaStoreId invece di path
                    val songData = hashMapOf(
                        "mediaStoreId" to song.id,
                        "title" to song.title,
                        "artist" to song.artist,
                        "album" to song.album,
                        "duration" to song.duration,
                        "size" to song.size,
                        "addedAt" to System.currentTimeMillis()
                    )

                    firestore.collection("playlists")
                        .document(playlistId)
                        .collection("songs")
                        .document(song.id.toString())
                        .set(songData)
                        .addOnSuccessListener {
                            Toast.makeText(
                                this,
                                "'${song.title}' aggiunta a '$playlistName'",
                                Toast.LENGTH_SHORT
                            ).show()
                            Log.d("FolderSongsActivity", "✅ Song added successfully")
                        }
                        .addOnFailureListener { e ->
                            Log.e("FolderSongsActivity", "❌ Error adding song: ${e.message}")
                            Toast.makeText(
                                this,
                                "Errore aggiunta: ${e.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                }
            }
            .addOnFailureListener { e ->
                Log.e("FolderSongsActivity", "❌ Error checking song: ${e.message}")
                Toast.makeText(
                    this,
                    "Errore: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    // Entra in modalità selezione
    private fun enterSelectionMode() {
        isSelectionMode = true

        // Nascondi elementi normali
        folderNameText.visibility = View.GONE
        songCountText.visibility = View.GONE

        // Mostra elementi di selezione
        btnAddSelectedToPlaylist.visibility = View.VISIBLE
        btnCancelSelection.visibility = View.VISIBLE
        selectionCountText.visibility = View.VISIBLE

        Log.d("FolderSongsActivity", "Entered selection mode")
    }

    // Esci da modalità selezione
    private fun exitSelectionMode() {
        isSelectionMode = false

        // Ripristina elementi normali
        folderNameText.visibility = View.VISIBLE
        songCountText.visibility = View.VISIBLE

        // Nascondi elementi di selezione
        btnAddSelectedToPlaylist.visibility = View.GONE
        btnCancelSelection.visibility = View.GONE
        selectionCountText.visibility = View.GONE

        songAdapter.exitSelectionMode()

        Log.d("FolderSongsActivity", "Exited selection mode")
    }

    // Aggiorna il conteggio delle canzoni selezionate
    private fun updateSelectionUI(count: Int) {
        selectionCountText.text = if (count == 1) {
            "$count canzone selezionata"
        } else {
            "$count canzoni selezionate"
        }
    }

    // Aggiungi canzoni selezionate a playlist
    private fun addSelectedSongsToPlaylist() {
        val selectedSongs = songAdapter.getSelectedSongs()

        if (selectedSongs.isEmpty()) {
            Toast.makeText(this, "Nessuna canzone selezionata", Toast.LENGTH_SHORT).show()
            return
        }

        if (currentUserId.isEmpty()) {
            Toast.makeText(this, "Devi effettuare il login per questa funzione", Toast.LENGTH_SHORT).show()
            return
        }

        // Mostra dialog per scegliere la playlist
        val playlistNames = userPlaylists.map { it.name }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Aggiungi ${selectedSongs.size} canzoni a")
            .setItems(playlistNames) { _, which ->
                val selectedPlaylist = userPlaylists[which]
                addMultipleSongsToPlaylist(selectedSongs, selectedPlaylist.id, selectedPlaylist.name)
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    // Aggiungi più canzoni a una playlist
    private fun addMultipleSongsToPlaylist(songs: List<Song>, playlistId: String, playlistName: String) {
        var added = 0
        var skipped = 0
        var errors = 0
        val total = songs.size

        songs.forEach { song ->
            firestore.collection("playlists")
                .document(playlistId)
                .collection("songs")
                .document(song.id.toString())
                .get()
                .addOnSuccessListener { document ->
                    if (document.exists()) {
                        skipped++
                    } else {
                        val songData = hashMapOf(
                            "mediaStoreId" to song.id,
                            "title" to song.title,
                            "artist" to song.artist,
                            "album" to song.album,
                            "duration" to song.duration,
                            "size" to song.size,
                            "addedAt" to System.currentTimeMillis()
                        )

                        firestore.collection("playlists")
                            .document(playlistId)
                            .collection("songs")
                            .document(song.id.toString())
                            .set(songData)
                            .addOnSuccessListener {
                                added++
                                if (added + skipped + errors == total) {
                                    showCompletionMessage(added, skipped, errors, playlistName)
                                }
                            }
                            .addOnFailureListener {
                                errors++
                                if (added + skipped + errors == total) {
                                    showCompletionMessage(added, skipped, errors, playlistName)
                                }
                            }
                    }

                    if (added + skipped + errors == total) {
                        showCompletionMessage(added, skipped, errors, playlistName)
                    }
                }
                .addOnFailureListener {
                    errors++
                    if (added + skipped + errors == total) {
                        showCompletionMessage(added, skipped, errors, playlistName)
                    }
                }
        }

        exitSelectionMode()
    }

    // Mostra messaggio di completamento
    private fun showCompletionMessage(added: Int, skipped: Int, errors: Int, playlistName: String) {
        val message = buildString {
            append("✅ Operazione completata!\n\n")
            if (added > 0) append("Aggiunte: $added\n")
            if (skipped > 0) append("Già presenti: $skipped\n")
            if (errors > 0) append("Errori: $errors\n")
            append("\nPlaylist: $playlistName")
        }

        AlertDialog.Builder(this)
            .setTitle("Risultato")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()

        Log.d("FolderSongsActivity", "✅ Completed: added=$added, skipped=$skipped, errors=$errors")

        // Se sono state aggiunte canzoni, segnala il cambiamento
        if (added > 0) {
            setResult(RESULT_OK)
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
        Percorso: ${song.path}
    """.trimIndent()

        AlertDialog.Builder(this)
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

        AlertDialog.Builder(this)
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
                folderSongs = folderSongs.filter { it.id != song.id }
                songAdapter.updateSongs(folderSongs)

                songCountText.text = getString(R.string.songs_in_folder, folderSongs.size)

                Toast.makeText(this, "Canzone eliminata", Toast.LENGTH_SHORT).show()
                Log.d("FolderSongsActivity", "Song deleted successfully: ${song.title}")

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

    // ✅ FIX: Aggiungi Locale.getDefault() a tutti i String.format
    private fun formatFileSize(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0

        return when {
            gb >= 1.0 -> String.format(Locale.getDefault(), "%.1f GB", gb)
            mb >= 1.0 -> String.format(Locale.getDefault(), "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.getDefault(), "%.1f KB", kb)
            else -> "$bytes bytes"
        }
    }
}