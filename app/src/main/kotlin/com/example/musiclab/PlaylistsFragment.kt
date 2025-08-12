package com.example.musiclab

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton

data class Playlist(
    val id: String,
    val name: String,
    val songs: MutableList<Song> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis()
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

        setupViews(view)
        setupRecyclerView()
        updateUI()
    }

    private fun setupViews(view: View) {
        recyclerView = view.findViewById(R.id.playlists_recycler_view)
        emptyStateText = view.findViewById(R.id.empty_state_text)
        fabCreatePlaylist = view.findViewById(R.id.fab_create_playlist)

        fabCreatePlaylist.setOnClickListener {
            createNewPlaylist()
        }
    }

    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        playlistAdapter = PlaylistAdapter(playlists) { playlist ->
            onPlaylistClick(playlist)
        }
        recyclerView.adapter = playlistAdapter
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

    private fun onPlaylistClick(playlist: Playlist) {
        // Apri la playlist per visualizzare/riprodurre le canzoni
        (activity as? MainActivity)?.openPlaylist(playlist)
        Log.d("PlaylistsFragment", "Opened playlist: ${playlist.name}")
    }

    private fun createNewPlaylist() {
        if (!isLoggedIn) {
            Toast.makeText(requireContext(), getString(R.string.login_required), Toast.LENGTH_SHORT).show()
            return
        }

        // TODO: Implementare dialog per creare nuova playlist
        val newPlaylist = Playlist(
            id = "playlist_${System.currentTimeMillis()}",
            name = "Nuova Playlist ${playlists.size + 1}"
        )

        playlists.add(newPlaylist)
        playlistAdapter.notifyItemInserted(playlists.size - 1)
        updateUI()

        Toast.makeText(requireContext(), "Playlist '${newPlaylist.name}' creata!", Toast.LENGTH_SHORT).show()
        Log.d("PlaylistsFragment", "Created new playlist: ${newPlaylist.name}")
    }

    fun setLoginState(loggedIn: Boolean) {
        isLoggedIn = loggedIn
        updateUI()

        if (loggedIn) {
            // TODO: Caricare playlist dal cloud
            loadPlaylistsFromCloud()
        } else {
            // Rimuovi playlist quando logout
            playlists.clear()
            playlistAdapter.notifyDataSetChanged()
            updateUI()
        }
    }

    private fun loadPlaylistsFromCloud() {
        // TODO: Implementare caricamento dal cloud
        Log.d("PlaylistsFragment", "Loading playlists from cloud...")

        // Esempio playlist di test quando loggato
        val testPlaylist = Playlist(
            id = "test_playlist_1",
            name = "I miei preferiti"
        )
        playlists.add(testPlaylist)
        playlistAdapter.notifyItemInserted(playlists.size - 1)
        updateUI()
    }

    fun addSongToPlaylist(song: Song, playlistId: String) {
        val playlist = playlists.find { it.id == playlistId }
        if (playlist != null) {
            playlist.songs.add(song)
            playlistAdapter.notifyDataSetChanged()
            Toast.makeText(requireContext(),
                "Aggiunta '${song.title}' a '${playlist.name}'",
                Toast.LENGTH_SHORT).show()
            Log.d("PlaylistsFragment", "Added song '${song.title}' to playlist '${playlist.name}'")
        }
    }

    fun getPlaylists(): List<Playlist> = playlists
}