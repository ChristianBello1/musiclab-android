package com.example.musiclab

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

data class Playlist(
    val id: String,
    val name: String,
    val songs: MutableList<Song> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis(),
    val ownerId: String = "",
    val isLocal: Boolean = false
) {
    fun getSongCount(): Int = songs.size

    fun getDuration(): Long = songs.sumOf { it.duration }

    fun getFormattedDuration(): String {
        val totalSeconds = (getDuration() / 1000).toInt()
        val minutes = totalSeconds / 60
        val hours = minutes / 60

        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes % 60, totalSeconds % 60)
        } else {
            String.format("%d:%02d", minutes, totalSeconds % 60)
        }
    }
}

class PlaylistsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var emptyStateText: TextView
    private lateinit var fabCreatePlaylist: FloatingActionButton

    private var playlists: MutableList<Playlist> = mutableListOf()
    private var isLoggedIn = false
    private var currentUserId: String = ""
    private var isLoading = false

    private lateinit var firestore: FirebaseFirestore
    private lateinit var youtubeImporter: YouTubeImporter

    private val playlistActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.d("PlaylistsFragment", "Activity returned RESULT_OK, refreshing")
            refreshPlaylists()
        }
    }

    companion object {
        fun newInstance(): PlaylistsFragment {
            return PlaylistsFragment()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_playlists, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        firestore = Firebase.firestore
        youtubeImporter = YouTubeImporter(requireContext())

        setupViews(view)
        setupRecyclerView()
        updateUI()

        loadSavedLoginState()
    }

    // NUOVO: Carica lo stato di login salvato
    private fun loadSavedLoginState() {
        val context = context ?: return
        val prefs = context.getSharedPreferences("MusicLabPrefs", android.content.Context.MODE_PRIVATE)
        isLoggedIn = prefs.getBoolean("is_logged_in", false)
        currentUserId = prefs.getString("user_id", "") ?: ""

        Log.d("PlaylistsFragment", "Loaded login state: logged=$isLoggedIn, userId=$currentUserId")

        // ✅ MODIFICA: Carica SUBITO le playlist se loggato
        if (isLoggedIn && currentUserId.isNotEmpty()) {
            // Mostra subito il FAB
            fabCreatePlaylist.visibility = View.VISIBLE
            // Carica le playlist in background
            loadPlaylistsFromCloud()
        }

        updateUI()
    }

    private fun setupViews(view: View) {
        recyclerView = view.findViewById(R.id.playlists_recycler_view)
        emptyStateText = view.findViewById(R.id.empty_state_text)
        fabCreatePlaylist = view.findViewById(R.id.fab_create_playlist)

        fabCreatePlaylist.setOnClickListener {
            if (isLoggedIn) {
                showCreatePlaylistMenu()
            } else {
                showLoginRequiredDialog()
            }
        }
    }

    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        playlistAdapter = PlaylistAdapter(playlists) { playlist ->
            onPlaylistClick(playlist)
        }
        recyclerView.adapter = playlistAdapter
    }

    private fun onPlaylistClick(playlist: Playlist) {
        val intent = Intent(requireContext(), PlaylistSongsActivity::class.java).apply {
            putExtra("PLAYLIST_ID", playlist.id)
            putExtra("PLAYLIST_NAME", playlist.name)
            putExtra("PLAYLIST_OWNER_ID", playlist.ownerId)
        }
        playlistActivityLauncher.launch(intent)
    }

    private fun updateUI() {
        if (playlists.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyStateText.visibility = View.VISIBLE

            emptyStateText.text = if (isLoggedIn) {
                getString(R.string.no_playlists_create)
            } else {
                getString(R.string.login_to_sync_playlists)
            }
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyStateText.visibility = View.GONE
        }

        fabCreatePlaylist.visibility = if (isLoggedIn) View.VISIBLE else View.GONE
    }

    private fun showCreatePlaylistDialog() {
        val editText = EditText(requireContext())
        editText.hint = "Nome della playlist"

        AlertDialog.Builder(requireContext())
            .setTitle("Crea Nuova Playlist")
            .setMessage("Inserisci il nome della playlist:")
            .setView(editText)
            .setPositiveButton("Crea") { _, _ ->
                val playlistName = editText.text.toString().trim()
                if (playlistName.isNotEmpty()) {
                    createNewPlaylist(playlistName)
                } else {
                    Toast.makeText(requireContext(), "Nome playlist non puo essere vuoto", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun showLoginRequiredDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.login_required_title))
            .setMessage(getString(R.string.login_required_message))
            .setPositiveButton(getString(R.string.sign_in)) { _, _ ->
                Toast.makeText(requireContext(), "Usa il pulsante login nell'header", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun createNewPlaylist(name: String) {
        if (!isLoggedIn) {
            showLoginRequiredDialog()
            return
        }

        val newPlaylist = Playlist(
            id = "playlist_${System.currentTimeMillis()}",
            name = name,
            ownerId = currentUserId,
            isLocal = false
        )

        playlists.add(newPlaylist)
        playlistAdapter.notifyItemInserted(playlists.size - 1)
        updateUI()

        savePlaylistToCloud(newPlaylist)

        Toast.makeText(requireContext(), "Playlist '$name' creata!", Toast.LENGTH_SHORT).show()
        Log.d("PlaylistsFragment", "Created playlist: $name")
    }

    fun setLoginState(loggedIn: Boolean, userId: String = "") {
        isLoggedIn = loggedIn
        currentUserId = userId

        // ← AGGIUNGI QUESTE RIGHE
        val context = context ?: return
        val prefs = context.getSharedPreferences("MusicLabPrefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("is_logged_in", loggedIn)
            putString("user_id", userId)
            apply()
        }
        Log.d("PlaylistsFragment", "Saved login state: logged=$loggedIn, userId=$userId")
        // FINE AGGIUNTA

        updateUI()

        if (loggedIn) {
            loadPlaylistsFromCloud()
        } else {
            playlists.removeAll { !it.isLocal }
            playlistAdapter.notifyDataSetChanged()
            updateUI()
        }
    }

    private fun loadPlaylistsFromCloud() {
        if (isLoading) {
            Log.d("PlaylistsFragment", "Already loading, skipping")
            return
        }

        isLoading = true
        Log.d("PlaylistsFragment", "⚡ FAST LOAD: Loading playlists for user: $currentUserId")

        // ✅ FIX: Mostra "caricamento" solo se la lista è vuota
        if (playlists.isEmpty()) {
            emptyStateText.text = "Caricamento playlist..."
            emptyStateText.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        }

        playlists.clear()

        firestore.collection("playlists")
            .whereEqualTo("ownerId", currentUserId)
            .get()
            .addOnSuccessListener { documents ->
                Log.d("PlaylistsFragment", "✅ Firestore returned ${documents.size()} playlists")

                if (documents.isEmpty) {
                    playlistAdapter.notifyDataSetChanged()
                    updateUI()
                    isLoading = false
                    return@addOnSuccessListener
                }

                // ✅ FIX PRINCIPALE: Mostra SUBITO le playlist senza aspettare le canzoni!
                for (document in documents) {
                    val playlistId = document.id
                    val playlistName = document.getString("name") ?: "Playlist"
                    val ownerId = document.getString("ownerId") ?: ""
                    val createdAt = document.getLong("createdAt") ?: System.currentTimeMillis()

                    // Crea playlist VUOTA temporaneamente
                    val playlist = Playlist(
                        id = playlistId,
                        name = playlistName,
                        ownerId = ownerId,
                        createdAt = createdAt,
                        songs = mutableListOf(), // ← Lista vuota per ora
                        isLocal = false
                    )

                    playlists.add(playlist)
                }

                // ✅ MOSTRA SUBITO le playlist (senza canzoni)
                playlistAdapter.notifyDataSetChanged()
                updateUI()
                isLoading = false

                Log.d("PlaylistsFragment", "⚡ FAST: Showed ${playlists.size} playlists IMMEDIATELY")

                // ✅ OPZIONALE: Carica il conteggio canzoni in background (leggero e veloce)
                loadSongCountsInBackground()
            }
            .addOnFailureListener { e ->
                isLoading = false
                Log.e("PlaylistsFragment", "❌ Error loading playlists: ${e.message}", e)
                Toast.makeText(
                    requireContext(),
                    "Errore caricamento playlist: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    private fun loadSongCountsInBackground() {
        Log.d("PlaylistsFragment", "📊 Loading song counts in background...")

        playlists.forEach { playlist ->
            firestore.collection("playlists")
                .document(playlist.id)
                .collection("songs")
                .get()
                .addOnSuccessListener { songDocs ->
                    // Aggiorna solo il conteggio, non le canzoni complete
                    val songCount = songDocs.size()
                    Log.d("PlaylistsFragment", "Playlist '${playlist.name}': $songCount songs")

                    // Le canzoni verranno caricate quando l'utente apre la playlist
                    // in PlaylistSongsActivity
                }
                .addOnFailureListener { e ->
                    Log.e("PlaylistsFragment", "Error loading song count for ${playlist.name}: ${e.message}")
                }
        }
    }

    private fun loadAllSongsForPlaylist(
        playlistId: String,
        playlistName: String,
        onComplete: (MutableList<Song>) -> Unit
    ) {
        val allSongs = mutableListOf<Song>()
        var batchCount = 0
        var processedIds = mutableSetOf<String>()

        Log.d("PlaylistsFragment", "Starting to load all songs for: $playlistName")

        fun loadBatch(lastDocId: String? = null) {
            batchCount++
            Log.d("PlaylistsFragment", "Loading batch $batchCount for: $playlistName")

            var query = firestore.collection("playlists")
                .document(playlistId)
                .collection("songs")
                .orderBy(com.google.firebase.firestore.FieldPath.documentId())
                .limit(500)

            if (lastDocId != null) {
                query = query.startAfter(lastDocId)
            }

            query.get()
                .addOnSuccessListener { songDocs ->
                    if (songDocs.isEmpty) {
                        Log.d("PlaylistsFragment", "No more songs. Total loaded: ${allSongs.size} in $batchCount batches")
                        onComplete(allSongs)
                        return@addOnSuccessListener
                    }

                    Log.d("PlaylistsFragment", "Batch $batchCount: loaded ${songDocs.size()} songs")

                    var newSongsCount = 0
                    for (songDoc in songDocs) {
                        if (processedIds.contains(songDoc.id)) {
                            continue
                        }
                        processedIds.add(songDoc.id)

                        try {
                            // ✅ MODIFICATO: Usa mediaStoreId per trovare la canzone
                            val mediaStoreId = songDoc.getLong("mediaStoreId") ?: songDoc.getLong("id") ?: 0L

                            // ✅ NUOVO: Cerca la canzone reale nel MediaStore
                            val song = findSongByMediaStoreId(mediaStoreId)

                            if (song != null) {
                                allSongs.add(song)
                                newSongsCount++
                            } else {
                                Log.w("PlaylistsFragment", "Song with ID $mediaStoreId not found on device")
                            }
                        } catch (e: Exception) {
                            Log.e("PlaylistsFragment", "Error parsing song: ${e.message}")
                        }
                    }

                    Log.d("PlaylistsFragment", "Added $newSongsCount new songs. Total: ${allSongs.size}")

                    if (songDocs.size() >= 500) {
                        val lastDoc = songDocs.documents[songDocs.size() - 1]
                        Log.d("PlaylistsFragment", "Batch full (${songDocs.size()} docs), loading next batch...")
                        loadBatch(lastDoc.id)
                    } else {
                        Log.d("PlaylistsFragment", "Last batch loaded. Total: ${allSongs.size} songs in $batchCount batches")
                        onComplete(allSongs)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("PlaylistsFragment", "Error loading batch $batchCount: ${e.message}", e)
                    Log.d("PlaylistsFragment", "Returning ${allSongs.size} songs due to error")
                    onComplete(allSongs)
                }
        }

        loadBatch()
    }

    private fun findSongByMediaStoreId(mediaStoreId: Long): Song? {
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

        requireContext().contentResolver.query(
            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return Song(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media._ID)),
                    title = cursor.getString(cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.TITLE)) ?: "Unknown",
                    artist = cursor.getString(cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.ARTIST)) ?: "Unknown",
                    album = cursor.getString(cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.ALBUM)) ?: "Unknown",
                    duration = cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DURATION)),
                    path = cursor.getString(cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DATA)) ?: "",
                    size = cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.SIZE))
                )
            }
        }

        return null
    }

    private fun savePlaylistToCloud(playlist: Playlist) {
        Log.d("PlaylistsFragment", "Saving playlist: ${playlist.name}")

        val playlistData = hashMapOf(
            "name" to playlist.name,
            "ownerId" to playlist.ownerId,
            "createdAt" to playlist.createdAt,
            "isLocal" to playlist.isLocal
        )

        firestore.collection("playlists")
            .document(playlist.id)
            .set(playlistData)
            .addOnSuccessListener {
                Log.d("PlaylistsFragment", "Playlist saved successfully to Firestore")
            }
            .addOnFailureListener { e ->
                Log.e("PlaylistsFragment", "Error saving playlist: ${e.message}", e)
                Toast.makeText(requireContext(), "Errore salvataggio playlist: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    fun addSongToPlaylist(song: Song, playlistId: String) {
        val playlist = playlists.find { it.id == playlistId }
        if (playlist != null) {
            Log.d("PlaylistsFragment", "Adding song ${song.title} to playlist ${playlist.name}")

            firestore.collection("playlists")
                .document(playlistId)
                .collection("songs")
                .document(song.id.toString())
                .get()
                .addOnSuccessListener { document ->
                    if (document.exists()) {
                        Toast.makeText(
                            requireContext(),
                            "'${song.title}' e gia in '${playlist.name}'",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        // ✅ MODIFICATO: Salva mediaStoreId invece di path
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
                                loadPlaylistsFromCloud()

                                Toast.makeText(
                                    requireContext(),
                                    "'${song.title}' aggiunta a '${playlist.name}'",
                                    Toast.LENGTH_SHORT
                                ).show()

                                Log.d("PlaylistsFragment", "Song added, reloading playlists")
                            }
                            .addOnFailureListener { e ->
                                Log.e("PlaylistsFragment", "Error adding song: ${e.message}")
                                Toast.makeText(
                                    requireContext(),
                                    "Errore aggiunta canzone",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("PlaylistsFragment", "Error checking song: ${e.message}")
                    Toast.makeText(
                        requireContext(),
                        "Errore controllo canzone",
                        Toast.LENGTH_SHORT
                    ).show()
                }
        }
    }

    fun showAddToPlaylistDialog(song: Song) {
        if (!isLoggedIn) {
            showLoginRequiredDialog()
            return
        }

        if (playlists.isEmpty()) {
            Toast.makeText(requireContext(), "Crea prima una playlist!", Toast.LENGTH_SHORT).show()
            return
        }

        val playlistNames = playlists.map { it.name }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("Aggiungi a Playlist")
            .setItems(playlistNames) { _, which ->
                val selectedPlaylist = playlists[which]
                addSongToPlaylist(song, selectedPlaylist.id)
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    fun getPlaylists(): List<Playlist> = playlists

    fun deletePlaylist(playlistId: String) {
        val playlist = playlists.find { it.id == playlistId }
        if (playlist != null && playlist.ownerId == currentUserId) {
            playlists.remove(playlist)
            playlistAdapter.notifyDataSetChanged()
            updateUI()

            Toast.makeText(requireContext(), "Playlist '${playlist.name}' eliminata", Toast.LENGTH_SHORT).show()
        }
    }

    fun refreshPlaylists() {
        Log.d("PlaylistsFragment", "Refreshing playlists manually")

        if (isLoggedIn) {
            loadPlaylistsFromCloud()
        }
    }

    override fun onResume() {
        super.onResume()

        if (isLoggedIn && currentUserId.isNotEmpty()) {
            Log.d("PlaylistsFragment", "onResume - reloading playlists")
            loadPlaylistsFromCloud()
        }
    }

    private fun showCreatePlaylistMenu() {
        val options = arrayOf(
            "Crea Playlist Vuota",
            "Importa da YouTube"
        )

        AlertDialog.Builder(requireContext())
            .setTitle("Crea Nuova Playlist")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showCreatePlaylistDialog()
                    1 -> showImportYouTubeDialog()
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun showImportYouTubeDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_import_youtube, null)

        val inputUrl = dialogView.findViewById<TextInputEditText>(R.id.input_youtube_url)
        val btnCancel = dialogView.findViewById<android.widget.Button>(R.id.btn_cancel_import)
        val btnImport = dialogView.findViewById<android.widget.Button>(R.id.btn_start_import)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnImport.setOnClickListener {
            val url = inputUrl.text.toString().trim()

            if (url.isEmpty()) {
                Toast.makeText(requireContext(), "Inserisci un URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!url.contains("youtube.com") && !url.contains("youtu.be")) {
                Toast.makeText(requireContext(), "URL YouTube non valido", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            dialog.dismiss()
            startYouTubeImport(url)
        }

        dialog.show()
    }

    private fun startYouTubeImport(playlistUrl: String) {
        Log.d("PlaylistsFragment", "Starting YouTube import: $playlistUrl")

        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_import_progress, null)

        val progressTitle = dialogView.findViewById<TextView>(R.id.progress_title)
        val progressMessage = dialogView.findViewById<TextView>(R.id.progress_message)
        val progressBar = dialogView.findViewById<android.widget.ProgressBar>(R.id.progress_bar)
        val progressPercentage = dialogView.findViewById<TextView>(R.id.progress_percentage)
        val progressDetails = dialogView.findViewById<TextView>(R.id.progress_details)

        val progressDialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()
        progressDialog.show()

        val mainActivity = activity as? MainActivity
        val allSongs = mainActivity?.getAllSongs() ?: emptyList()

        if (allSongs.isEmpty()) {
            progressDialog.dismiss()
            Toast.makeText(requireContext(), "Nessuna canzone trovata sul dispositivo", Toast.LENGTH_LONG).show()
            return
        }

        Log.d("PlaylistsFragment", "Starting import with ${allSongs.size} local songs")

        lifecycleScope.launch {
            try {
                val result = youtubeImporter.importPlaylist(playlistUrl, allSongs) { progress ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        when (progress.phase) {
                            "loading" -> {
                                progressTitle.text = "Caricamento da YouTube..."
                                progressMessage.text = "Scaricamento informazioni playlist..."

                                val percentage = if (progress.total > 0) {
                                    (progress.current * 100) / progress.total
                                } else 0

                                progressBar.progress = percentage
                                progressPercentage.text = "$percentage%"
                                progressDetails.text = "${progress.current} / ${progress.total} video caricati"
                            }
                            "matching" -> {
                                progressTitle.text = "Ricerca canzoni..."
                                progressMessage.text = "Confronto con la tua libreria musicale..."

                                val percentage = (progress.current * 100) / progress.total
                                progressBar.progress = percentage
                                progressPercentage.text = "$percentage%"
                                progressDetails.text = buildString {
                                    append("${progress.current} / ${progress.total} video analizzati\n")
                                    append("✅ Trovate: ${progress.matchedCount}")
                                }
                            }
                        }
                    }
                }

                progressDialog.dismiss()
                showImportResultsWithDebug(result)

            } catch (e: Exception) {
                progressDialog.dismiss()
                Log.e("PlaylistsFragment", "Import error: ${e.message}", e)

                AlertDialog.Builder(requireContext())
                    .setTitle("Errore Importazione")
                    .setMessage("Impossibile importare la playlist:\n${e.message}")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun showImportResultsWithDebug(result: YouTubeImporter.ImportResult) {
        val message = buildString {
            append("Playlist: ${result.playlistTitle}\n\n")
            append("Risultati:\n")
            append("Trovate: ${result.matchedSongs.size}\n")
            append("Non trovate: ${result.unmatchedTitles.size}\n")
            append("Totale video: ${result.totalVideos}\n\n")

            if (result.unmatchedTitles.isNotEmpty()) {
                append("Non trovate (primi 5):\n")
                result.unmatchedTitles.take(5).forEach {
                    append("- $it\n")
                }
                if (result.unmatchedTitles.size > 5) {
                    append("... e altre ${result.unmatchedTitles.size - 5}\n")
                }
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Importazione Completata")
            .setMessage(message)
            .setPositiveButton("Crea Playlist") { _, _ ->
                if (result.matchedSongs.isNotEmpty()) {
                    createPlaylistFromImport(result)
                } else {
                    Toast.makeText(requireContext(), "Nessuna canzone da aggiungere", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Vedi Log Debug") { _, _ ->
                Toast.makeText(requireContext(),
                    "Controlla Logcat in Android Studio\nFiltra per tag: YouTubeImporter",
                    Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun showImportResults(result: YouTubeImporter.ImportResult) {
        val message = buildString {
            append("Playlist: ${result.playlistTitle}\n\n")
            append("Risultati:\n")
            append("Trovate: ${result.matchedSongs.size}\n")
            append("Non trovate: ${result.unmatchedTitles.size}\n")
            append("Totale video: ${result.totalVideos}\n\n")

            if (result.unmatchedTitles.isNotEmpty()) {
                append("Non trovate:\n")
                result.unmatchedTitles.take(5).forEach {
                    append("- $it\n")
                }
                if (result.unmatchedTitles.size > 5) {
                    append("... e altre ${result.unmatchedTitles.size - 5}\n")
                }
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Importazione Completata")
            .setMessage(message)
            .setPositiveButton("Crea Playlist") { _, _ ->
                if (result.matchedSongs.isNotEmpty()) {
                    createPlaylistFromImport(result)
                } else {
                    Toast.makeText(requireContext(), "Nessuna canzone da aggiungere", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun createPlaylistFromImport(result: YouTubeImporter.ImportResult) {
        val playlistName = result.playlistTitle

        val newPlaylist = Playlist(
            id = "playlist_${System.currentTimeMillis()}",
            name = playlistName,
            ownerId = currentUserId,
            isLocal = false
        )

        playlists.add(newPlaylist)
        playlistAdapter.notifyItemInserted(playlists.size - 1)
        updateUI()

        savePlaylistToCloud(newPlaylist)

        var addedCount = 0
        result.matchedSongs.forEach { song ->
            // ✅ MODIFICATO: Salva mediaStoreId invece di path
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
                .document(newPlaylist.id)
                .collection("songs")
                .document(song.id.toString())
                .set(songData)
                .addOnSuccessListener {
                    addedCount++
                    if (addedCount == result.matchedSongs.size) {
                        Toast.makeText(requireContext(), "Playlist creata con ${result.matchedSongs.size} canzoni!", Toast.LENGTH_LONG).show()
                        loadPlaylistsFromCloud()
                    }
                }
        }
    }

    private fun addSongsInBatchFast(
        playlistId: String,
        songs: List<Song>,
        progressDialog: AlertDialog,
        onComplete: (addedCount: Int, errorCount: Int) -> Unit
    ) {
        Log.d("PlaylistsFragment", "=== START BATCH IMPORT ===")
        Log.d("PlaylistsFragment", "Playlist ID: $playlistId")
        Log.d("PlaylistsFragment", "Total songs to add: ${songs.size}")

        var addedCount = 0
        var errorCount = 0
        val totalSongs = songs.size
        val startTime = System.currentTimeMillis()

        lifecycleScope.launch {
            try {
                // ✅ Riduci batch size a 100 (più sicuro per Firestore)
                val batches = songs.chunked(100)
                Log.d("PlaylistsFragment", "Created ${batches.size} batches")

                batches.forEachIndexed { batchIndex, batchSongs ->
                    Log.d("PlaylistsFragment", "Processing batch ${batchIndex + 1}/${batches.size} with ${batchSongs.size} songs")

                    try {
                        withContext(Dispatchers.IO) {
                            val batch = firestore.batch()

                            batchSongs.forEach { song ->
                                val docRef = firestore.collection("playlists")
                                    .document(playlistId)
                                    .collection("songs")
                                    .document(song.id.toString())

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

                                batch.set(docRef, songData)
                            }

                            Log.d("PlaylistsFragment", "Committing batch ${batchIndex + 1}...")
                            batch.commit().await()
                            Log.d("PlaylistsFragment", "Batch ${batchIndex + 1} committed successfully!")
                        }

                        addedCount += batchSongs.size
                        Log.d("PlaylistsFragment", "Total added so far: $addedCount")

                        withContext(Dispatchers.Main) {
                            val elapsed = (System.currentTimeMillis() - startTime) / 1000
                            val progress = addedCount
                            val avgTimePerSong = if (progress > 0) elapsed.toDouble() / progress else 0.15
                            val remainingSongs = totalSongs - progress
                            val remainingTime = (remainingSongs * avgTimePerSong).toInt()
                            val remainingMin = remainingTime / 60
                            val remainingSec = remainingTime % 60

                            progressDialog.setMessage(
                                "$progress / $totalSongs aggiunte\n" +
                                        "Batch ${batchIndex + 1}/${batches.size}\n" +
                                        "Tempo rimanente: ~${remainingMin}m ${remainingSec}s"
                            )
                        }

                        // Pausa tra batch
                        if (batchIndex < batches.size - 1) {
                            Log.d("PlaylistsFragment", "Pausing 1s before next batch...")
                            kotlinx.coroutines.delay(1000)
                        }

                    } catch (e: Exception) {
                        errorCount += batchSongs.size
                        Log.e("PlaylistsFragment", "ERROR in batch ${batchIndex + 1}: ${e.message}", e)
                    }
                }

                Log.d("PlaylistsFragment", "=== BATCH IMPORT COMPLETE ===")
                Log.d("PlaylistsFragment", "Added: $addedCount, Errors: $errorCount")

                withContext(Dispatchers.Main) {
                    onComplete(addedCount, errorCount)
                }

            } catch (e: Exception) {
                Log.e("PlaylistsFragment", "FATAL ERROR: ${e.message}", e)
                errorCount = totalSongs

                withContext(Dispatchers.Main) {
                    onComplete(0, errorCount)
                }
            }
        }
    }
}