package com.example.musiclab

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import kotlinx.coroutines.*

class PlaylistSongsActivity : AppCompatActivity() {

    private lateinit var backButton: ImageButton
    private lateinit var normalInfoContainer: LinearLayout
    private lateinit var playlistNameText: TextView
    private lateinit var songCountText: TextView
    private lateinit var songsRecyclerView: RecyclerView
    private lateinit var songAdapter: SongAdapter
    private lateinit var musicPlayer: MusicPlayer
    private lateinit var btnPlaylistMenu: ImageButton
    private lateinit var btnSortPlaylist: ImageButton

    private lateinit var btnAddSelectedToOtherPlaylist: ImageButton
    private lateinit var btnRemoveSelectedFromPlaylist: ImageButton
    private lateinit var selectionCountText: TextView
    private var isSelectionMode = false

    // ✅ NUOVO: Views per loading
    private lateinit var loadingContainer: LinearLayout
    private lateinit var loadingProgress: ProgressBar
    private lateinit var loadingText: TextView

    private var playlist: Playlist? = null
    private var playlistSongs: MutableList<Song> = mutableListOf()
    private val firestore = FirebaseFirestore.getInstance()

    // ✅ NUOVO: CoroutineScope per caricamento asincrono
    private val loadingScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var currentUserId: String = ""
    private var userPlaylists: MutableList<Playlist> = mutableListOf()

    enum class PlaylistSortType {
        TITLE_ASC,
        DURATION_ASC,
        ADDED_RECENTLY,
        ADDED_OLDEST_FIRST
    }

    private var currentSortType = PlaylistSortType.TITLE_ASC

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_playlist_songs)

        Log.d("PlaylistSongsActivity", "=== PLAYLIST SONGS ACTIVITY CREATED ===")

        val playlistId = intent.getStringExtra("PLAYLIST_ID") ?: ""
        val playlistName = intent.getStringExtra("PLAYLIST_NAME") ?: "Playlist"
        val playlistOwnerId = intent.getStringExtra("PLAYLIST_OWNER_ID") ?: ""

        playlist = Playlist(
            id = playlistId,
            name = playlistName,
            ownerId = playlistOwnerId
        )

        musicPlayer = MusicPlayerManager.getInstance().getMusicPlayer(this)
        currentUserId = GoogleAuthManager.getInstance().getUserId() ?: ""

        setupViews()
        setupRecyclerView()

        // ✅ MODIFICATO: Mostra loading PRIMA di caricare
        showLoading()
        loadPlaylistSongs()

        if (currentUserId.isNotEmpty()) {
            loadUserPlaylists()
        }

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
        btnSortPlaylist = findViewById(R.id.btn_sort_playlist)

        btnAddSelectedToOtherPlaylist = findViewById(R.id.btn_add_selected_to_other_playlist)
        btnRemoveSelectedFromPlaylist = findViewById(R.id.btn_remove_selected_from_playlist)
        selectionCountText = findViewById(R.id.selection_count_text)

        // ✅ NUOVO: Loading views
        loadingContainer = findViewById(R.id.loading_container)
        loadingProgress = findViewById(R.id.loading_progress)
        loadingText = findViewById(R.id.loading_text)

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

        btnSortPlaylist.setOnClickListener {
            showSortOptions()
        }

        btnAddSelectedToOtherPlaylist.setOnClickListener {
            addSelectedSongsToOtherPlaylist()
        }

        btnRemoveSelectedFromPlaylist.setOnClickListener {
            removeSelectedSongsFromPlaylist()
        }
    }

    // ✅ NUOVA FUNZIONE: Mostra loading
    private fun showLoading() {
        loadingContainer.visibility = View.VISIBLE
        songsRecyclerView.visibility = View.GONE
        Log.d("PlaylistSongsActivity", "📥 Showing loading indicator")
    }

    // ✅ NUOVA FUNZIONE: Nascondi loading
    private fun hideLoading() {
        loadingContainer.visibility = View.GONE
        songsRecyclerView.visibility = View.VISIBLE
        Log.d("PlaylistSongsActivity", "✅ Hiding loading indicator")
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
                enterSelectionMode()
            },
            onSelectionChanged = { count ->
                updateSelectionUI(count)
            },
            contextType = SongAdapter.ContextType.PLAYLIST
        )
        songsRecyclerView.adapter = songAdapter

        Log.d("PlaylistSongsActivity", "RecyclerView setup")
    }

    // ✅ MODIFICATA: Con coroutines per non bloccare l'UI
    private fun loadPlaylistSongs() {
        Log.d("PlaylistSongsActivity", "🔄 Loading songs for playlist: ${playlist?.id}")

        playlist?.let { pl ->
            firestore.collection("playlists")
                .document(pl.id)
                .collection("songs")
                .get()
                .addOnSuccessListener { documents ->
                    val totalSongs = documents.size()
                    Log.d("PlaylistSongsActivity", "📊 Firestore returned $totalSongs songs")

                    // ✅ NUOVO: Lancia coroutine per caricamento in background
                    loadingScope.launch {
                        // Carica le canzoni in background (thread IO)
                        val loadedSongs = withContext(Dispatchers.IO) {
                            val songs = mutableListOf<Song>()

                            for (doc in documents) {
                                try {
                                    val mediaStoreId = doc.getLong("mediaStoreId") ?: doc.getLong("id") ?: continue

                                    val song = getSongByMediaStoreId(mediaStoreId)
                                    if (song != null) {
                                        val addedAt = doc.getLong("addedAt") ?: System.currentTimeMillis()
                                        song.addedAt = addedAt
                                        songs.add(song)
                                    } else {
                                        Log.w("PlaylistSongsActivity", "⚠️ Song not found: $mediaStoreId")
                                    }
                                } catch (e: Exception) {
                                    Log.e("PlaylistSongsActivity", "❌ Error loading song: ${e.message}")
                                }
                            }

                            songs // Ritorna la lista
                        }

                        // ✅ Aggiorna l'UI sul main thread
                        playlistSongs.clear()
                        playlistSongs.addAll(loadedSongs)

                        hideLoading()
                        applySorting()
                        songAdapter.notifyDataSetChanged()
                        updateSongCount()

                        Log.d("PlaylistSongsActivity", "✅ Loaded ${playlistSongs.size} songs successfully")
                    }
                }
                .addOnFailureListener { e ->
                    hideLoading()

                    Log.e("PlaylistSongsActivity", "❌ Error loading playlist: ${e.message}")
                    Toast.makeText(
                        this,
                        "Errore caricamento playlist: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    private fun getSongByMediaStoreId(mediaStoreId: Long): Song? {
        val projection = arrayOf(
            android.provider.MediaStore.Audio.Media._ID,
            android.provider.MediaStore.Audio.Media.TITLE,
            android.provider.MediaStore.Audio.Media.ARTIST,
            android.provider.MediaStore.Audio.Media.ALBUM,
            android.provider.MediaStore.Audio.Media.DURATION,
            android.provider.MediaStore.Audio.Media.DATA,
            android.provider.MediaStore.Audio.Media.SIZE
        )

        val selection = "${android.provider.MediaStore.Audio.Media._ID} = ?"
        val selectionArgs = arrayOf(mediaStoreId.toString())

        val cursor = contentResolver.query(
            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )

        cursor?.use {
            if (it.moveToFirst()) {
                return Song(
                    id = it.getLong(it.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media._ID)),
                    title = it.getString(it.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.TITLE)) ?: "Unknown",
                    artist = it.getString(it.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.ARTIST)) ?: "Unknown",
                    album = it.getString(it.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.ALBUM)) ?: "Unknown",
                    duration = it.getLong(it.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DURATION)),
                    path = it.getString(it.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DATA)) ?: "",
                    size = it.getLong(it.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.SIZE))
                )
            }
        }

        return null
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
        Log.d("PlaylistSongsActivity", "🎵 Song clicked: ${song.title}")

        musicPlayer.setPlaylist(playlistSongs, playlistSongs.indexOf(song))
        musicPlayer.playSong(song)

        finish()

        Log.d("PlaylistSongsActivity", "✅ Playing song, returning to MainActivity")
    }

    private fun handleSongMenuAction(song: Song, action: SongAdapter.MenuAction) {
        when (action) {
            SongAdapter.MenuAction.ADD_TO_PLAYLIST -> {
                if (currentUserId.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("Login Richiesto")
                        .setMessage("Devi effettuare l'accesso per usare le playlist.")
                        .setPositiveButton("OK", null)
                        .show()
                } else if (userPlaylists.size <= 1) {
                    Toast.makeText(this, "Crea prima un'altra playlist!", Toast.LENGTH_SHORT).show()
                } else {
                    showAddToPlaylistDialog(song)
                }
            }
            SongAdapter.MenuAction.SONG_DETAILS -> {
                showSongDetails(song)
            }
            SongAdapter.MenuAction.REMOVE_FROM_PLAYLIST -> {
                removeSongFromPlaylist(song)
            }
            else -> {}
        }
    }

    private fun showPlaylistMenu() {
        val options = arrayOf(
            "Rinomina playlist",
            "Elimina playlist"
        )

        AlertDialog.Builder(this)
            .setTitle(playlist?.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenamePlaylistDialog()
                    1 -> confirmDeletePlaylist()
                }
            }
            .show()
    }

    private fun showSortOptions() {
        val options = arrayOf(
            "Titolo (A-Z)",
            "Durata (breve → lunga)",
            "Aggiunte per prime",
            "Aggiunte di recente"
        )

        val currentSelection = when (currentSortType) {
            PlaylistSortType.TITLE_ASC -> 0
            PlaylistSortType.DURATION_ASC -> 1
            PlaylistSortType.ADDED_RECENTLY -> 2
            PlaylistSortType.ADDED_OLDEST_FIRST -> 3
        }

        AlertDialog.Builder(this)
            .setTitle("Ordina canzoni per")
            .setSingleChoiceItems(options, currentSelection) { dialog, which ->
                val newSortType = when (which) {
                    0 -> PlaylistSortType.TITLE_ASC
                    1 -> PlaylistSortType.DURATION_ASC
                    2 -> PlaylistSortType.ADDED_RECENTLY
                    3 -> PlaylistSortType.ADDED_OLDEST_FIRST
                    else -> PlaylistSortType.TITLE_ASC
                }

                if (newSortType != currentSortType) {
                    currentSortType = newSortType
                    applySorting()
                }

                dialog.dismiss()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun applySorting() {
        Log.d("PlaylistSongsActivity", "🔄 Applying sort: $currentSortType")

        val sortedSongs = when (currentSortType) {
            PlaylistSortType.TITLE_ASC -> {
                playlistSongs.sortedBy { it.title.lowercase() }
            }
            PlaylistSortType.DURATION_ASC -> {
                playlistSongs.sortedBy { it.duration }
            }
            PlaylistSortType.ADDED_RECENTLY -> {
                playlistSongs.sortedByDescending { it.addedAt ?: 0L }
            }
            PlaylistSortType.ADDED_OLDEST_FIRST -> {
                playlistSongs.sortedBy { it.addedAt ?: Long.MAX_VALUE }
            }
        }

        playlistSongs.clear()
        playlistSongs.addAll(sortedSongs)
        songAdapter.updateSongs(playlistSongs)

        val sortName = when (currentSortType) {
            PlaylistSortType.TITLE_ASC -> "Titolo (A-Z)"
            PlaylistSortType.DURATION_ASC -> "Durata"
            PlaylistSortType.ADDED_RECENTLY -> "Aggiunte per prime"
            PlaylistSortType.ADDED_OLDEST_FIRST -> "Aggiunte di recente"
        }

        Toast.makeText(this, "Ordinato per: $sortName", Toast.LENGTH_SHORT).show()
        Log.d("PlaylistSongsActivity", "✅ Sorting applied: $sortName")
    }

    private fun showRenamePlaylistDialog() {
        val input = android.widget.EditText(this)
        input.setText(playlist?.name)

        AlertDialog.Builder(this)
            .setTitle("Rinomina Playlist")
            .setView(input)
            .setPositiveButton("Rinomina") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    renamePlaylist(newName)
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun renamePlaylist(newName: String) {
        playlist?.let { pl ->
            firestore.collection("playlists")
                .document(pl.id)
                .update("name", newName)
                .addOnSuccessListener {
                    playlistNameText.text = newName
                    Toast.makeText(this, "Playlist rinominata", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener { e ->
                    Log.e("PlaylistSongsActivity", "Error renaming: ${e.message}")
                    Toast.makeText(this, "Errore rinomina", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun confirmDeletePlaylist() {
        AlertDialog.Builder(this)
            .setTitle("Elimina Playlist")
            .setMessage("Vuoi eliminare '${playlist?.name}'? Le canzoni rimarranno sul dispositivo.")
            .setPositiveButton("Elimina") { _, _ ->
                deletePlaylist()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun deletePlaylist() {
        playlist?.let { pl ->
            firestore.collection("playlists")
                .document(pl.id)
                .delete()
                .addOnSuccessListener {
                    Toast.makeText(this, "Playlist eliminata", Toast.LENGTH_SHORT).show()
                    finish()
                }
                .addOnFailureListener { e ->
                    Log.e("PlaylistSongsActivity", "Error deleting: ${e.message}")
                    Toast.makeText(this, "Errore eliminazione", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun enterSelectionMode() {
        isSelectionMode = true
        normalInfoContainer.visibility = View.GONE
        selectionCountText.visibility = View.VISIBLE
        btnAddSelectedToOtherPlaylist.visibility = View.VISIBLE
        btnRemoveSelectedFromPlaylist.visibility = View.VISIBLE
        btnPlaylistMenu.visibility = View.GONE
        btnSortPlaylist.visibility = View.GONE
        backButton.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)

        Log.d("PlaylistSongsActivity", "✅ Entered selection mode")
    }

    private fun exitSelectionMode() {
        isSelectionMode = false
        songAdapter.exitSelectionMode()

        normalInfoContainer.visibility = View.VISIBLE
        selectionCountText.visibility = View.GONE
        btnAddSelectedToOtherPlaylist.visibility = View.GONE
        btnRemoveSelectedFromPlaylist.visibility = View.GONE
        btnPlaylistMenu.visibility = View.VISIBLE
        btnSortPlaylist.visibility = View.VISIBLE
        backButton.setImageResource(android.R.drawable.arrow_down_float)

        Log.d("PlaylistSongsActivity", "❌ Exited selection mode")
    }

    private fun updateSelectionUI(count: Int) {
        selectionCountText.text = if (count == 1) {
            "$count selezionata"
        } else {
            "$count selezionate"
        }

        btnAddSelectedToOtherPlaylist.isEnabled = count > 0
        btnRemoveSelectedFromPlaylist.isEnabled = count > 0
    }

    private fun addSelectedSongsToOtherPlaylist() {
        val selectedSongs = songAdapter.getSelectedSongs()

        if (selectedSongs.isEmpty()) {
            Toast.makeText(this, "Nessuna canzone selezionata", Toast.LENGTH_SHORT).show()
            return
        }

        val otherPlaylists = userPlaylists.filter { it.id != playlist?.id }

        if (otherPlaylists.isEmpty()) {
            Toast.makeText(this, "Crea prima un'altra playlist!", Toast.LENGTH_SHORT).show()
            return
        }

        val playlistNames = otherPlaylists.map { it.name }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Aggiungi ${selectedSongs.size} canzoni a:")
            .setItems(playlistNames) { _, which ->
                val targetPlaylist = otherPlaylists[which]

                selectedSongs.forEach { song ->
                    addSongToOtherPlaylist(song, targetPlaylist.id)
                }

                exitSelectionMode()
                Toast.makeText(this, "${selectedSongs.size} canzoni aggiunte", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun removeSelectedSongsFromPlaylist() {
        val selectedSongs = songAdapter.getSelectedSongs()

        if (selectedSongs.isEmpty()) {
            Toast.makeText(this, "Nessuna canzone selezionata", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Rimuovi canzoni")
            .setMessage("Rimuovere ${selectedSongs.size} canzoni da questa playlist?")
            .setPositiveButton("Rimuovi") { _, _ ->
                selectedSongs.forEach { song ->
                    removeSongFromPlaylist(song, showToast = false)
                }
                exitSelectionMode()
                Toast.makeText(this, "${selectedSongs.size} canzoni rimosse", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun showAddToPlaylistDialog(song: Song) {
        val otherPlaylists = userPlaylists.filter { it.id != playlist?.id }
        val playlistNames = otherPlaylists.map { it.name }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Aggiungi a playlist")
            .setItems(playlistNames) { _, which ->
                val targetPlaylist = otherPlaylists[which]
                addSongToOtherPlaylist(song, targetPlaylist.id)
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun addSongToOtherPlaylist(song: Song, targetPlaylistId: String) {
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
            .document(targetPlaylistId)
            .collection("songs")
            .document(song.id.toString())
            .set(songData)
            .addOnSuccessListener {
                Log.d("PlaylistSongsActivity", "Song added to other playlist")
            }
            .addOnFailureListener { e ->
                Log.e("PlaylistSongsActivity", "Error adding song: ${e.message}")
            }
    }

    private fun removeSongFromPlaylist(song: Song, showToast: Boolean = true) {
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

                    if (showToast) {
                        Toast.makeText(this, "Canzone rimossa dalla playlist", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("PlaylistSongsActivity", "Error removing song: ${e.message}")
                    Toast.makeText(this, "Errore rimozione canzone", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun showSongDetails(song: Song) {
        val info = """
            Titolo: ${song.title}
            Artista: ${song.artist}
            Album: ${song.album}
            Durata: ${song.getFormattedDuration()}
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Info Canzone")
            .setMessage(info)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // ✅ NUOVO: Cancella tutte le coroutines quando l'activity viene distrutta
        loadingScope.cancel()
        Log.d("PlaylistSongsActivity", "🧹 CoroutineScope cancelled")
    }
}