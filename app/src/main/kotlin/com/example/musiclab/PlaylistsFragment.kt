package com.example.musiclab

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
        Log.d("PlaylistsFragment", "Loading playlists for user: $currentUserId")

        playlists.clear()

        firestore.collection("playlists")
            .whereEqualTo("ownerId", currentUserId)
            .get()
            .addOnSuccessListener { documents ->
                Log.d("PlaylistsFragment", "Firestore returned ${documents.count()} documents")

                val totalPlaylists = documents.size()
                var loadedCount = 0

                if (totalPlaylists == 0) {
                    playlistAdapter.notifyDataSetChanged()
                    updateUI()
                    isLoading = false
                    return@addOnSuccessListener
                }

                for (document in documents) {
                    val playlistId = document.id
                    val playlistName = document.getString("name") ?: "Playlist"
                    val ownerId = document.getString("ownerId") ?: ""
                    val createdAt = document.getLong("createdAt") ?: System.currentTimeMillis()

                    firestore.collection("playlists")
                        .document(playlistId)
                        .collection("songs")
                        .get()
                        .addOnSuccessListener { songDocs ->
                            val songs = mutableListOf<Song>()

                            for (songDoc in songDocs) {
                                try {
                                    val song = Song(
                                        id = songDoc.getLong("id") ?: 0L,
                                        title = songDoc.getString("title") ?: "Unknown",
                                        artist = songDoc.getString("artist") ?: "Unknown",
                                        album = songDoc.getString("album") ?: "Unknown",
                                        duration = songDoc.getLong("duration") ?: 0L,
                                        path = songDoc.getString("path") ?: "",
                                        size = songDoc.getLong("size") ?: 0L
                                    )
                                    songs.add(song)
                                } catch (e: Exception) {
                                    Log.e("PlaylistsFragment", "Error parsing song: ${e.message}")
                                }
                            }

                            val playlist = Playlist(
                                id = playlistId,
                                name = playlistName,
                                ownerId = ownerId,
                                createdAt = createdAt,
                                songs = songs,
                                isLocal = false
                            )

                            playlists.add(playlist)
                            loadedCount++

                            Log.d("PlaylistsFragment", "Loaded playlist: $playlistName with ${songs.size} songs")

                            if (loadedCount == totalPlaylists) {
                                playlistAdapter.notifyDataSetChanged()
                                updateUI()
                                isLoading = false
                                Log.d("PlaylistsFragment", "All playlists loaded")
                            }
                        }
                        .addOnFailureListener { e ->
                            Log.e("PlaylistsFragment", "Error loading songs for $playlistName: ${e.message}")

                            val playlist = Playlist(
                                id = playlistId,
                                name = playlistName,
                                ownerId = ownerId,
                                createdAt = createdAt,
                                isLocal = false
                            )
                            playlists.add(playlist)
                            loadedCount++

                            if (loadedCount == totalPlaylists) {
                                playlistAdapter.notifyDataSetChanged()
                                updateUI()
                                isLoading = false
                            }
                        }
                }
            }
            .addOnFailureListener { e ->
                isLoading = false
                Log.e("PlaylistsFragment", "Error loading playlists: ${e.message}", e)
                Toast.makeText(requireContext(), "Errore caricamento playlist: ${e.message}", Toast.LENGTH_SHORT).show()
            }
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

        val progressDialog = AlertDialog.Builder(requireContext())
            .setTitle("Importazione in corso...")
            .setMessage("Analizzando playlist YouTube...\nPotrebbe richiedere qualche minuto.")
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

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = youtubeImporter.importPlaylist(playlistUrl, allSongs)

                progressDialog.dismiss()
                showImportResults(result)

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

        // NUOVO: Dialog con progress
        val totalSongs = result.matchedSongs.size
        val estimatedMinutes = (totalSongs * 0.15).toInt() // ~150ms per canzone

        val progressDialog = AlertDialog.Builder(requireContext())
            .setTitle("Aggiunta canzoni...")
            .setMessage("0 / $totalSongs\nTempo stimato: ~$estimatedMinutes min")
            .setCancelable(false)
            .create()
        progressDialog.show()

        val startTime = System.currentTimeMillis()

        addSongsInBatchFast(newPlaylist.id, result.matchedSongs, progressDialog) { addedCount, errorCount ->
            progressDialog.dismiss()

            val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000
            val minutes = elapsedSeconds / 60
            val seconds = elapsedSeconds % 60

            val message = buildString {
                append("Playlist '${playlistName}' creata!\n\n")
                append("Canzoni aggiunte: $addedCount / $totalSongs\n")
                if (errorCount > 0) {
                    append("Non aggiunte: $errorCount\n")
                }
                append("\nTempo impiegato: ${minutes}m ${seconds}s")
            }

            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            refreshPlaylists()

            Log.d("PlaylistsFragment", "Import completed: $addedCount added, $errorCount errors in ${elapsedSeconds}s")
        }
    }

    // NUOVO: Con aggiornamento progress
    private fun addSongsInBatchWithProgress(
        playlistId: String,
        songs: List<Song>,
        progressDialog: AlertDialog,
        onComplete: (addedCount: Int, errorCount: Int) -> Unit
    ) {
        var addedCount = 0
        var errorCount = 0
        val totalSongs = songs.size
        val startTime = System.currentTimeMillis()

        lifecycleScope.launch {
            songs.forEachIndexed { index, song ->
                var retries = 3
                var success = false

                while (retries > 0 && !success) {
                    try {
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

                        withContext(Dispatchers.IO) {
                            firestore.collection("playlists")
                                .document(playlistId)
                                .collection("songs")
                                .document(song.id.toString())
                                .set(songData)
                                .await()
                        }

                        addedCount++
                        success = true

                        // Aggiorna progress ogni 10 canzoni
                        if ((index + 1) % 10 == 0 || index == songs.size - 1) {
                            withContext(Dispatchers.Main) {
                                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                                val avgTimePerSong = if (index > 0) elapsed.toDouble() / (index + 1) else 0.15
                                val remaining = ((totalSongs - index - 1) * avgTimePerSong).toInt()
                                val remainingMin = remaining / 60
                                val remainingSec = remaining % 60

                                progressDialog.setMessage(
                                    "${addedCount} / $totalSongs aggiunte\n" +
                                            "Tempo rimanente: ~${remainingMin}m ${remainingSec}s"
                                )
                            }
                        }

                    } catch (e: Exception) {
                        retries--
                        if (retries > 0) {
                            kotlinx.coroutines.delay(500)
                        } else {
                            errorCount++
                            Log.e("PlaylistsFragment", "Failed: ${song.title}")
                        }
                    }
                }

                if ((index + 1) % 50 == 0 && index < songs.size - 1) {
                    kotlinx.coroutines.delay(2000)
                } else if (index < songs.size - 1) {
                    kotlinx.coroutines.delay(100)
                }
            }

            withContext(Dispatchers.Main) {
                onComplete(addedCount, errorCount)
            }
        }
    }

    // NUOVO: Versione ULTRA VELOCE con batch writes
    private fun addSongsInBatchFast(
        playlistId: String,
        songs: List<Song>,
        progressDialog: AlertDialog,
        onComplete: (addedCount: Int, errorCount: Int) -> Unit
    ) {
        var addedCount = 0
        var errorCount = 0
        val totalSongs = songs.size
        val startTime = System.currentTimeMillis()

        lifecycleScope.launch {
            // Dividi in gruppi da 450 (sotto il limite di 500)
            val batches = songs.chunked(450)

            batches.forEachIndexed { batchIndex, batchSongs ->
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

                        batch.commit().await()
                    }

                    addedCount += batchSongs.size

                    // Aggiorna progress
                    withContext(Dispatchers.Main) {
                        val elapsed = (System.currentTimeMillis() - startTime) / 1000
                        val progress = ((batchIndex + 1) * 450).coerceAtMost(totalSongs)
                        val remaining = totalSongs - progress
                        val avgTimePerBatch = elapsed.toDouble() / (batchIndex + 1)
                        val remainingBatches = batches.size - batchIndex - 1
                        val remainingTime = (remainingBatches * avgTimePerBatch).toInt()
                        val remainingMin = remainingTime / 60
                        val remainingSec = remainingTime % 60

                        progressDialog.setMessage(
                            "${progress} / $totalSongs aggiunte\n" +
                                    "Tempo rimanente: ~${remainingMin}m ${remainingSec}s"
                        )
                    }

                    Log.d("PlaylistsFragment", "Batch ${batchIndex + 1}/${batches.size} completed")

                    // Pausa tra batch
                    if (batchIndex < batches.size - 1) {
                        kotlinx.coroutines.delay(1000)
                    }

                } catch (e: Exception) {
                    errorCount += batchSongs.size
                    Log.e("PlaylistsFragment", "Batch ${batchIndex + 1} failed: ${e.message}")
                }
            }

            withContext(Dispatchers.Main) {
                onComplete(addedCount, errorCount)
            }
        }
    }

    // NUOVO METODO: Aggiungi canzoni in batch con rate limiting
    private fun addSongsInBatch(
        playlistId: String,
        songs: List<Song>,
        onComplete: (addedCount: Int, errorCount: Int) -> Unit
    ) {
        var addedCount = 0
        var errorCount = 0
        val totalSongs = songs.size

        lifecycleScope.launch {
            songs.forEachIndexed { index, song ->
                var retries = 3
                var success = false

                while (retries > 0 && !success) {
                    try {
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

                        withContext(Dispatchers.IO) {
                            firestore.collection("playlists")
                                .document(playlistId)
                                .collection("songs")
                                .document(song.id.toString())
                                .set(songData)
                                .await()
                        }

                        addedCount++
                        success = true
                        Log.d("PlaylistsFragment", "Added ${index + 1}/$totalSongs: ${song.title}")

                    } catch (e: Exception) {
                        retries--
                        if (retries > 0) {
                            Log.w("PlaylistsFragment", "Retry ${3 - retries} for: ${song.title}")
                            kotlinx.coroutines.delay(500)
                        } else {
                            errorCount++
                            Log.e("PlaylistsFragment", "Failed after 3 retries: ${song.title} - ${e.message}")
                        }
                    }
                }

                // Delay più lungo ogni 50 canzoni per evitare rate limit
                if ((index + 1) % 50 == 0 && index < songs.size - 1) {
                    Log.d("PlaylistsFragment", "Pausa dopo ${index + 1} canzoni...")
                    kotlinx.coroutines.delay(2000)
                } else if (index < songs.size - 1) {
                    kotlinx.coroutines.delay(100)
                }
            }

            withContext(Dispatchers.Main) {
                onComplete(addedCount, errorCount)
            }
        }
    }
}