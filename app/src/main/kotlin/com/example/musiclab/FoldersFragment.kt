package com.example.musiclab

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

data class MusicFolder(
    val name: String,
    val path: String,
    val songCount: Int,
    val songs: List<Song>
)

class FoldersFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var folderAdapter: FolderAdapter
    private lateinit var emptyStateText: TextView
    private var songs: List<Song> = emptyList()
    private var folders: List<MusicFolder> = emptyList()

    companion object {
        fun newInstance(songs: List<Song>): FoldersFragment {
            Log.d("FoldersFragment", "=== CREAZIONE FRAGMENT ===")
            Log.d("FoldersFragment", "Songs ricevute alla creazione: ${songs.size}")
            val fragment = FoldersFragment()
            fragment.songs = songs
            return fragment
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        Log.d("FoldersFragment", "=== ON CREATE VIEW ===")
        Log.d("FoldersFragment", "Songs disponibili: ${songs.size}")
        return inflater.inflate(R.layout.fragment_folders, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d("FoldersFragment", "=== ON VIEW CREATED ===")
        Log.d("FoldersFragment", "Songs all'inizio: ${songs.size}")

        setupViews(view)
        setupRecyclerView()
        organizeSongsIntoFolders()
        updateUI()
    }

    private fun setupViews(view: View) {
        Log.d("FoldersFragment", "Setup views...")
        recyclerView = view.findViewById(R.id.folders_recycler_view)
        emptyStateText = view.findViewById(R.id.empty_state_folders)
        Log.d("FoldersFragment", "Views trovate: recycler=${recyclerView != null}, empty=${emptyStateText != null}")
    }

    private fun setupRecyclerView() {
        Log.d("FoldersFragment", "Setup RecyclerView...")
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        folderAdapter = FolderAdapter(folders) { folder ->
            onFolderClick(folder)
        }
        recyclerView.adapter = folderAdapter
        Log.d("FoldersFragment", "RecyclerView configurato con ${folders.size} cartelle")
    }

    private fun organizeSongsIntoFolders() {
        Log.d("FoldersFragment", "=== ORGANIZZAZIONE CARTELLE ===")
        Log.d("FoldersFragment", "Input songs: ${songs.size}")

        if (songs.isEmpty()) {
            Log.w("FoldersFragment", "⚠️ Nessuna canzone da organizzare!")
            folders = emptyList()
            return
        }

        songs.take(3).forEachIndexed { index, song ->
            Log.d("FoldersFragment", "Song $index: ${song.title} - Path: ${song.path}")
        }

        val songsByFolder = songs.groupBy { song ->
            val path = song.path
            val lastSlashIndex = path.lastIndexOf('/')
            if (lastSlashIndex > 0) {
                val folderPath = path.substring(0, lastSlashIndex)
                val folderName = folderPath.substring(folderPath.lastIndexOf('/') + 1)
                folderName.ifEmpty { "Root" }
            } else {
                "Unknown"
            }
        }

        Log.d("FoldersFragment", "Cartelle trovate: ${songsByFolder.keys}")

        folders = songsByFolder.map { (folderName, songsInFolder) ->
            val folderPath = if (songsInFolder.isNotEmpty()) {
                val path = songsInFolder.first().path
                val lastSlashIndex = path.lastIndexOf('/')
                if (lastSlashIndex > 0) path.substring(0, lastSlashIndex) else "/"
            } else "/"

            Log.d("FoldersFragment", "📁 Cartella: $folderName con ${songsInFolder.size} canzoni")

            MusicFolder(
                name = folderName,
                path = folderPath,
                songCount = songsInFolder.size,
                songs = songsInFolder.sortedBy { it.title }
            )
        }.sortedBy { it.name }

        Log.d("FoldersFragment", "✅ Cartelle create: ${folders.size}")
        folders.forEach { folder ->
            Log.d("FoldersFragment", "  - ${folder.name}: ${folder.songCount} canzoni")
        }
    }

    private fun updateUI() {
        Log.d("FoldersFragment", "=== UPDATE UI ===")
        Log.d("FoldersFragment", "Cartelle da mostrare: ${folders.size}")

        if (folders.isEmpty()) {
            Log.w("FoldersFragment", "❌ Nessuna cartella - mostra empty state")
            recyclerView.visibility = View.GONE
            emptyStateText.visibility = View.VISIBLE
        } else {
            Log.d("FoldersFragment", "✅ Mostro ${folders.size} cartelle")
            recyclerView.visibility = View.VISIBLE
            emptyStateText.visibility = View.GONE
            folderAdapter.updateFolders(folders)
        }
    }

    private fun onFolderClick(folder: MusicFolder) {
        Log.d("FoldersFragment", "🔊 Clicked folder: ${folder.name} with ${folder.songCount} songs")

        val intent = Intent(requireContext(), FolderSongsActivity::class.java)
        intent.putExtra("FOLDER_NAME", folder.name)
        intent.putParcelableArrayListExtra("FOLDER_SONGS", ArrayList(folder.songs))

        Log.d("FoldersFragment", "🚀 Starting FolderSongsActivity")
        startActivity(intent)
    }

    fun updateSongs(newSongs: List<Song>) {
        Log.d("FoldersFragment", "=== UPDATE SONGS ===")
        Log.d("FoldersFragment", "Nuove songs ricevute: ${newSongs.size}")

        songs = newSongs
        organizeSongsIntoFolders()
        updateUI()

        Log.d("FoldersFragment", "✅ Update completato")
    }

    fun filterSongs(query: String): List<Song> {
        Log.d("FoldersFragment", "🔍 Filter songs per: '$query'")

        return if (query.isBlank()) {
            songs
        } else {
            songs.filter { song ->
                song.title.contains(query, ignoreCase = true) ||
                        song.artist.contains(query, ignoreCase = true) ||
                        song.album.contains(query, ignoreCase = true)
            }
        }
    }
}