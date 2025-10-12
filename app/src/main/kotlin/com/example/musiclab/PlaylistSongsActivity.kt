package com.example.musiclab

import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore
import android.view.View
import androidx.activity.OnBackPressedCallback

class PlaylistSongsActivity : AppCompatActivity() {

    private lateinit var backButton: ImageButton
    private lateinit var normalInfoContainer: LinearLayout
    private lateinit var playlistNameText: TextView
    private lateinit var songCountText: TextView
    private lateinit var songsRecyclerView: RecyclerView
    private lateinit var songAdapter: SongAdapter
    private lateinit var musicPlayer: MusicPlayer
    private lateinit var btnPlaylistMenu: ImageButton

    // NUOVO: Per selezione multipla
    private lateinit var btnAddSelectedToOtherPlaylist: ImageButton
    private lateinit var btnRemoveSelectedFromPlaylist: ImageButton
    private lateinit var selectionCountText: TextView
    private var isSelectionMode = false

    private var playlist: Playlist? = null
    private var playlistSongs: MutableList<Song> = mutableListOf()
    private val firestore = FirebaseFirestore.getInstance()

    // NUOVO: Per gestire playlist
    private var currentUserId: String = ""
    private var userPlaylists: MutableList<Playlist> = mutableListOf()

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

        // NUOVO: Ottieni userId
        currentUserId = GoogleAuthManager.getInstance().getUserId() ?: ""

        setupViews()
        setupRecyclerView()
        loadPlaylistSongs()

        // NUOVO: Carica playlist dell'utente
        if (currentUserId.isNotEmpty()) {
            loadUserPlaylists()
        }

        // Gestione back button per uscire dalla selezione
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isSelectionMode) {
                    exitSelectionMode()
                } else {
                    finish()
                }
            }
        })
    }

    private fun setupViews() {
        backButton = findViewById(R.id.btn_back_playlist)
        normalInfoContainer = findViewById(R.id.normal_info_container)
        playlistNameText = findViewById(R.id.playlist_name_title)
        songCountText = findViewById(R.id.playlist_song_count_text)
        songsRecyclerView = findViewById(R.id.playlist_songs_recycler)
        btnPlaylistMenu = findViewById(R.id.btn_playlist_menu)

        // NUEVO: Bottoni per selezione multipla
        btnAddSelectedToOtherPlaylist = findViewById(R.id.btn_add_selected_to_other_playlist)
        btnRemoveSelectedFromPlaylist = findViewById(R.id.btn_remove_selected_from_playlist)
        selectionCountText = findViewById(R.id.selection_count_text)

        playlistNameText.text = playlist?.name ?: "Playlist"

        backButton.setOnClickListener {
            if (isSelectionMode) {
                exitSelectionMode()
            } else {
                finish()
            }
        }

        btnPlaylistMenu.setOnClickListener {
            showPlaylistMenu()
        }

        // NUEVO: Click listeners per selezione multipla
        btnAddSelectedToOtherPlaylist.setOnClickListener {
            addSelectedSongsToOtherPlaylist()
        }

        btnRemoveSelectedFromPlaylist.setOnClickListener {
            removeSelectedSongsFromPlaylist()
        }
    }

    private fun setupRecyclerView() {
        songsRecyclerView.layoutManager = LinearLayoutManager(this)

        songAdapter = SongAdapter(
            songs = playlistSongs,
            onSongClick = { song ->
                if (!isSelectionMode) {
                    onSongClick(song)
                }
            },
            onSongMenuClick = { song, action ->
                handleSongMenuAction(song, action)
            },
            onLongPress = { song ->
                // NUEVO: Entra in modalità selezione
                enterSelectionMode()
            },
            onSelectionChanged = { count ->
                // NUEVO: Aggiorna contatore
                updateSelectionUI(count)
            },
            contextType = SongAdapter.ContextType.PLAYLIST
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

    private fun loadUserPlaylists() {
        if (currentUserId.isEmpty()) return

        Log.d("PlaylistSongsActivity", "Loading user playlists for: $currentUserId")

        firestore.collection("playlists")
            .whereEqualTo("ownerId", currentUserId)
            .get()
            .addOnSuccessListener { documents ->
                userPlaylists.clear()

                for (document in documents) {
                    val pl = Playlist(
                        id = document.id,
                        name = document.getString("name") ?: "Playlist",
                        ownerId = document.getString("ownerId") ?: "",
                        createdAt = document.getLong("createdAt") ?: System.currentTimeMillis(),
                        isLocal = false
                    )
                    userPlaylists.add(pl)
                }

                Log.d("PlaylistSongsActivity", "✅ Loaded ${userPlaylists.size} playlists")
            }
            .addOnFailureListener { e ->
                Log.e("PlaylistSongsActivity", "❌ Error loading playlists: ${e.message}")
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

    // NUEVO: Entra in modalità selezione
    private fun enterSelectionMode() {
        isSelectionMode = true

        // Nascondi info normale
        normalInfoContainer.visibility = View.GONE

        // Mostra contatore selezione
        selectionCountText.visibility = View.VISIBLE

        // Mostra bottoni selezione
        btnAddSelectedToOtherPlaylist.visibility = View.VISIBLE
        btnRemoveSelectedFromPlaylist.visibility = View.VISIBLE

        // Nascondi menu playlist
        btnPlaylistMenu.visibility = View.GONE

        // Cambia icona back button in X
        backButton.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)

        Log.d("PlaylistSongsActivity", "✅ Entered selection mode")
    }

    // NUEVO: Esci dalla modalità selezione
    private fun exitSelectionMode() {
        isSelectionMode = false
        songAdapter.exitSelectionMode()

        // Ripristina info normale
        normalInfoContainer.visibility = View.VISIBLE

        // Nascondi contatore selezione
        selectionCountText.visibility = View.GONE

        // Nascondi bottoni selezione
        btnAddSelectedToOtherPlaylist.visibility = View.GONE
        btnRemoveSelectedFromPlaylist.visibility = View.GONE

        // Mostra menu playlist
        btnPlaylistMenu.visibility = View.VISIBLE

        // Ripristina icona back button
        backButton.setImageResource(android.R.drawable.arrow_down_float)

        Log.d("PlaylistSongsActivity", "❌ Exited selection mode")
    }

    // NUEVO: Aggiorna UI contatore selezione
    private fun updateSelectionUI(count: Int) {
        selectionCountText.text = if (count == 1) {
            "$count selezionata"
        } else {
            "$count selezionate"
        }

        btnAddSelectedToOtherPlaylist.isEnabled = count > 0
        btnRemoveSelectedFromPlaylist.isEnabled = count > 0
    }

    // NUEVO: Aggiungi canzoni selezionate ad ALTRA playlist
    private fun addSelectedSongsToOtherPlaylist() {
        val selectedSongs = songAdapter.getSelectedSongs()

        if (selectedSongs.isEmpty()) {
            Toast.makeText(this, "Nessuna canzone selezionata", Toast.LENGTH_SHORT).show()
            return
        }

        if (currentUserId.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Login Richiesto")
                .setMessage("Devi effettuare l'accesso per usare le playlist.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        if (userPlaylists.size <= 1) {
            Toast.makeText(
                this,
                "Crea prima un'altra playlist!",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Mostra dialog per scegliere la playlist
        showAddMultipleSongsDialog(selectedSongs)
    }

    // NUEVO: Dialog per aggiungere più canzoni
    private fun showAddMultipleSongsDialog(songs: List<Song>) {
        // Filtra le playlist per escludere quella corrente
        val otherPlaylists = userPlaylists.filter { it.id != playlist?.id }

        if (otherPlaylists.isEmpty()) {
            Toast.makeText(
                this,
                "Non hai altre playlist disponibili!",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val playlistNames = otherPlaylists.map { it.name }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Aggiungi ${songs.size} canzoni a...")
            .setItems(playlistNames) { _, which ->
                val selectedPlaylist = otherPlaylists[which]
                addMultipleSongsToPlaylist(songs, selectedPlaylist.id, selectedPlaylist.name)
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    // NUEVO: Rimuovi canzoni selezionate dalla playlist corrente
    private fun removeSelectedSongsFromPlaylist() {
        val selectedSongs = songAdapter.getSelectedSongs()

        if (selectedSongs.isEmpty()) {
            Toast.makeText(this, "Nessuna canzone selezionata", Toast.LENGTH_SHORT).show()
            return
        }

        // Mostra conferma
        AlertDialog.Builder(this)
            .setTitle("Rimuovi ${selectedSongs.size} canzoni?")
            .setMessage("Vuoi rimuovere ${selectedSongs.size} canzoni da questa playlist?")
            .setPositiveButton("Rimuovi") { _, _ ->
                performRemoveMultipleSongs(selectedSongs)
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    // NUEVO: Esegui rimozione multipla
    private fun performRemoveMultipleSongs(songs: List<Song>) {
        Log.d("PlaylistSongsActivity", "Removing ${songs.size} songs from playlist")

        var removedCount = 0
        var errorCount = 0
        val totalSongs = songs.size

        // Mostra progress
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Rimozione in corso...")
            .setMessage("0 / $totalSongs")
            .setCancelable(false)
            .create()
        progressDialog.show()

        // Rimuovi ogni canzone
        songs.forEach { song ->
            playlist?.let { pl ->
                firestore.collection("playlists")
                    .document(pl.id)
                    .collection("songs")
                    .document(song.id.toString())
                    .delete()
                    .addOnSuccessListener {
                        removedCount++

                        // Aggiorna progress
                        val completed = removedCount + errorCount
                        progressDialog.setMessage("$completed / $totalSongs")

                        if (completed == totalSongs) {
                            progressDialog.dismiss()
                            showRemovalResult(removedCount, errorCount)

                            // Rimuovi dalle liste locali
                            playlistSongs.removeAll(songs)
                            songAdapter.updateSongs(playlistSongs)
                            updateSongCount()
                            exitSelectionMode()

                            // Segnala cambiamento
                            setResult(RESULT_OK)

                            // Se la playlist è vuota, torna indietro
                            if (playlistSongs.isEmpty()) {
                                Toast.makeText(this, "Playlist vuota", Toast.LENGTH_SHORT).show()
                                finish()
                            }
                        }
                    }
                    .addOnFailureListener {
                        errorCount++
                        val completed = removedCount + errorCount
                        progressDialog.setMessage("$completed / $totalSongs")

                        if (completed == totalSongs) {
                            progressDialog.dismiss()
                            showRemovalResult(removedCount, errorCount)
                            exitSelectionMode()
                        }
                    }
            }
        }
    }

    // NUEVO: Mostra risultato rimozione
    private fun showRemovalResult(removed: Int, errors: Int) {
        val message = buildString {
            append("✅ Rimozione completata!\n\n")
            if (removed > 0) append("Rimosse: $removed\n")
            if (errors > 0) append("Errori: $errors\n")
        }

        AlertDialog.Builder(this)
            .setTitle("Risultato")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()

        Log.d("PlaylistSongsActivity", "✅ Removal completed: removed=$removed, errors=$errors")
    }

    // Aggiungi multiple songs a playlist
    private fun addMultipleSongsToPlaylist(songs: List<Song>, playlistId: String, playlistName: String) {
        Log.d("PlaylistSongsActivity", "Adding ${songs.size} songs to playlist '$playlistName'")

        var addedCount = 0
        var skippedCount = 0
        var errorCount = 0
        val totalSongs = songs.size

        // Mostra progress
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Aggiunta in corso...")
            .setMessage("0 / $totalSongs")
            .setCancelable(false)
            .create()
        progressDialog.show()

        songs.forEach { song ->
            firestore.collection("playlists")
                .document(playlistId)
                .collection("songs")
                .document(song.id.toString())
                .get()
                .addOnSuccessListener { document ->
                    if (document.exists()) {
                        skippedCount++
                        val completed = addedCount + skippedCount + errorCount
                        progressDialog.setMessage("$completed / $totalSongs")

                        if (completed == totalSongs) {
                            progressDialog.dismiss()
                            showAdditionResult(addedCount, skippedCount, errorCount, playlistName)
                            exitSelectionMode()
                        }
                    } else {
                        val songData = hashMapOf(
                            "id" to song.id,
                            "title" to song.title,
                            "artist" to song.artist,
                            "album" to song.album,
                            "duration" to song.duration,
                            "path" to song.path,
                            "size" to song.size,
                            "addedAt" to System.currentTimeMillis()
                        )

                        firestore.collection("playlists")
                            .document(playlistId)
                            .collection("songs")
                            .document(song.id.toString())
                            .set(songData)
                            .addOnSuccessListener {
                                addedCount++
                                val completed = addedCount + skippedCount + errorCount
                                progressDialog.setMessage("$completed / $totalSongs")

                                if (completed == totalSongs) {
                                    progressDialog.dismiss()
                                    showAdditionResult(addedCount, skippedCount, errorCount, playlistName)
                                    exitSelectionMode()
                                }
                            }
                            .addOnFailureListener {
                                errorCount++
                                val completed = addedCount + skippedCount + errorCount
                                progressDialog.setMessage("$completed / $totalSongs")

                                if (completed == totalSongs) {
                                    progressDialog.dismiss()
                                    showAdditionResult(addedCount, skippedCount, errorCount, playlistName)
                                    exitSelectionMode()
                                }
                            }
                    }
                }
        }
    }

    // NUEVO: Mostra risultato aggiunta
    private fun showAdditionResult(added: Int, skipped: Int, errors: Int, playlistName: String) {
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

        Log.d("PlaylistSongsActivity", "✅ Addition completed: added=$added, skipped=$skipped, errors=$errors")
    }

    private fun handleSongMenuAction(song: Song, action: SongAdapter.MenuAction) {
        when (action) {
            SongAdapter.MenuAction.ADD_TO_PLAYLIST -> {
                // ✅ NUOVA FUNZIONALITÀ: Aggiungi ad ALTRA playlist
                if (currentUserId.isEmpty()) {
                    Toast.makeText(this, "Devi effettuare l'accesso", Toast.LENGTH_SHORT).show()
                } else if (userPlaylists.size <= 1) {
                    // Solo questa playlist esiste
                    Toast.makeText(
                        this,
                        "Crea prima un'altra playlist!",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    // Mostra dialog per altre playlist
                    showAddSingleSongToOtherPlaylistDialog(song)
                }
            }
            SongAdapter.MenuAction.REMOVE_FROM_PLAYLIST -> {
                showRemoveFromPlaylistConfirmation(song)
            }
            SongAdapter.MenuAction.SONG_DETAILS -> {
                showSongDetails(song)
            }
            SongAdapter.MenuAction.DELETE_FROM_DEVICE -> {
                // In una playlist, questo dovrebbe rimuovere dalla playlist
                showRemoveFromPlaylistConfirmation(song)
            }
        }
    }

    // ✅ NUEVO METODO per singola canzone
    private fun showAddSingleSongToOtherPlaylistDialog(song: Song) {
        // Filtra le playlist per escludere quella corrente
        val otherPlaylists = userPlaylists.filter { it.id != playlist?.id }

        if (otherPlaylists.isEmpty()) {
            Toast.makeText(
                this,
                "Non hai altre playlist disponibili!",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val playlistNames = otherPlaylists.map { it.name }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Aggiungi '${song.title}' a...")
            .setItems(playlistNames) { _, which ->
                val selectedPlaylist = otherPlaylists[which]
                addSingleSongToPlaylist(song, selectedPlaylist.id, selectedPlaylist.name)
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    // ✅ NUEVO METODO per singola canzone
    private fun addSingleSongToPlaylist(song: Song, playlistId: String, playlistName: String) {
        Log.d("PlaylistSongsActivity", "Adding '${song.title}' to playlist '$playlistName'")

        // Controlla se la canzone è già nella playlist
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
                    // Aggiungi la canzone
                    val songData = hashMapOf(
                        "id" to song.id,
                        "title" to song.title,
                        "artist" to song.artist,
                        "album" to song.album,
                        "duration" to song.duration,
                        "path" to song.path,
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
                                "✅ '${song.title}' aggiunta a '$playlistName'",
                                Toast.LENGTH_SHORT
                            ).show()
                            Log.d("PlaylistSongsActivity", "✅ Song added successfully")
                        }
                        .addOnFailureListener { e ->
                            Toast.makeText(
                                this,
                                "Errore aggiunta canzone",
                                Toast.LENGTH_SHORT
                            ).show()
                            Log.e("PlaylistSongsActivity", "❌ Error: ${e.message}")
                        }
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    this,
                    "Errore controllo canzone",
                    Toast.LENGTH_SHORT
                ).show()
                Log.e("PlaylistSongsActivity", "❌ Error checking: ${e.message}")
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
                    // Rimuovi dalla lista locale
                    playlistSongs.remove(song)

                    runOnUiThread {
                        songAdapter.updateSongs(playlistSongs)
                        updateSongCount()

                        Toast.makeText(this, "Canzone rimossa dalla playlist", Toast.LENGTH_SHORT).show()
                        Log.d("PlaylistSongsActivity", "✅ Song removed successfully")

                        setResult(RESULT_OK)

                        if (playlistSongs.isEmpty()) {
                            Toast.makeText(this, "Playlist vuota", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }
                }
                .addOnFailureListener { e ->
                    runOnUiThread {
                        Log.e("PlaylistSongsActivity", "Error removing song: ${e.message}")
                        Toast.makeText(this, "Errore rimozione canzone: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
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
                    setResult(RESULT_OK)
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