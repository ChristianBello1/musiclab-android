package com.example.musiclab

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class FolderAdapter(
    private var folders: List<MusicFolder>,
    private val onFolderClick: (MusicFolder) -> Unit
) : RecyclerView.Adapter<FolderAdapter.FolderViewHolder>() {

    class FolderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val folderIcon: ImageView = itemView.findViewById(R.id.folder_icon)
        val folderName: TextView = itemView.findViewById(R.id.folder_name)
        val songCount: TextView = itemView.findViewById(R.id.folder_song_count)
        val menuButton: ImageButton = itemView.findViewById(R.id.folder_menu)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.folder_item, parent, false)
        return FolderViewHolder(view)
    }

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        val folder = folders[position]

        holder.folderName.text = folder.name
        holder.songCount.text = holder.itemView.context.getString(
            R.string.songs_in_folder,
            folder.songCount
        )

        // Click listener per aprire la cartella
        holder.itemView.setOnClickListener {
            onFolderClick(folder)
        }

        // Menu button per opzioni (future)
        holder.menuButton.setOnClickListener {
            // TODO: Implementare menu contestuale
        }
    }

    override fun getItemCount(): Int = folders.size

    fun updateFolders(newFolders: List<MusicFolder>) {
        folders = newFolders
        notifyDataSetChanged()
    }
}