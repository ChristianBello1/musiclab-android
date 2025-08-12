package com.example.musiclab

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
        return inflater.inflate(R.layout.fragment_folders, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViews(view)
        setupRecyclerView()
        organizeSongsIntoFolders()
        updateUI()
    }

    private fun setupViews(view: View) {
        recyclerView = view.findViewById(R.id.folders_recycler_view)
        emptyStateText = view.findViewById(R.id.empty_state_folders)
    }

    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        folderAdapter = FolderAdapter(folders) { folder ->
            onFolderClick(folder)
        }
        recyclerView.adapter = folderAdapter
    }

    private fun organizeSongsIntoFolders() {
        if (songs.isEmpty()) {
            folders = emptyList()
            return
        }

        // Raggruppa le canzoni per cartella (usando il path)
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

        // Crea oggetti MusicFolder
        folders = songsByFolder.map { (folderName, songsInFolder) ->
            val folderPath = if (songsInFolder.isNotEmpty()) {
                val path = songsInFolder.first().path
                val lastSlashIndex = path.lastIndexOf('/')
                if (lastSlashIndex > 0) path.substring(0, lastSlashIndex) else "/"
            } else "/"

            MusicFolder(
                name = folderName,
                path = folderPath,
                songCount = songsInFolder.size,
                songs = songsInFolder.sortedBy { it.title }
            )
        }.sortedBy { it.name }

        Log.d("FoldersFragment", "Created ${folders.size} folders from ${songs.size} songs")
    }

    private fun updateUI() {
        if (folders.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyStateText.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyStateText.visibility = View.GONE
            folderAdapter.updateFolders(folders)
        }
    }

    private fun onFolderClick(folder: MusicFolder) {
        // Mostra le canzoni nella cartella
        Log.d("FoldersFragment", "Clicked folder: ${folder.name} with ${folder.songCount} songs")

        // Riproduci la prima canzone della cartella
        if (folder.songs.isNotEmpty()) {
            (activity as? MainActivity)?.onSongClickFromFragment(folder.songs.first())
        }
    }

    fun updateSongs(newSongs: List<Song>) {
        songs = newSongs
        organizeSongsIntoFolders()
        updateUI()
        Log.d("FoldersFragment", "Updated songs: ${newSongs.size}")
    }

    fun filterSongs(query: String): List<Song> {
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