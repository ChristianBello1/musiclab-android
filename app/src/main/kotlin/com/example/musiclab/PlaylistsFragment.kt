package com.example.musiclab

import android.app.Activity
import android.app.AlertDialog
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

    private lateinit var firestore: FirebaseFirestore

    // Launcher per aspettare il risultato da PlaylistSongsActivity
    private val playlistActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // La playlist è stata modificata/eliminata, ricarica
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
                showCreatePlaylistDialog()
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
                    Toast.makeText(requireContext(), "Nome playlist non può essere vuoto", Toast.LENGTH_SHORT).show()
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
        Log.d("PlaylistsFragment", "Loading playlists for user: $currentUserId")

        firestore.collection("playlists")
            .whereEqualTo("ownerId", currentUserId)
            .get()
            .addOnSuccessListener { documents ->
                playlists.clear()

                Log.d("PlaylistsFragment", "Firestore returned ${documents.count()} documents")

                for (document in documents) {
                    val playlist = Playlist(
                        id = document.id,
                        name = document.getString("name") ?: "Playlist",
                        ownerId = document.getString("ownerId") ?: "",
                        createdAt = document.getLong("createdAt") ?: System.currentTimeMillis(),
                        isLocal = false
                    )
                    playlists.add(playlist)
                    Log.d("PlaylistsFragment", "Loaded playlist: ${playlist.name}")
                }

                playlistAdapter.notifyDataSetChanged()
                updateUI()

                Log.d("PlaylistsFragment", "Successfully loaded ${playlists.size} playlists from cloud")
            }
            .addOnFailureListener { e ->
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

            // Controlla se la canzone è già nella playlist
            firestore.collection("playlists")
                .document(playlistId)
                .collection("songs")
                .document(song.id.toString())
                .get()
                .addOnSuccessListener { document ->
                    if (document.exists()) {
                        Toast.makeText(
                            requireContext(),
                            "'${song.title}' è già in '${playlist.name}'",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        // Aggiungi la canzone a Firestore
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
                                playlist.songs.add(song)
                                playlistAdapter.notifyDataSetChanged()

                                Toast.makeText(
                                    requireContext(),
                                    "✅ '${song.title}' aggiunta a '${playlist.name}'",
                                    Toast.LENGTH_SHORT
                                ).show()

                                Log.d("PlaylistsFragment", "✅ Song added successfully to Firestore")
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

    // NUOVO: Funzione per ricaricare le playlist
    fun refreshPlaylists() {
        if (isLoggedIn) {
            loadPlaylistsFromCloud()
            Log.d("PlaylistsFragment", "🔄 Playlists refreshed")
        }
    }
}