package com.example.musiclab

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
// import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class FolderAdapter(
    private var folders: List<MusicFolder>,
    private val onFolderClick: (MusicFolder) -> Unit
) : RecyclerView.Adapter<FolderAdapter.FolderViewHolder>() {

    class FolderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // val folderIcon: ImageView = itemView.findViewById(R.id.folder_icon)
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

        // Nascondi il menu button
        holder.menuButton.visibility = View.GONE
    }

    override fun getItemCount(): Int = folders.size

    fun updateFolders(newFolders: List<MusicFolder>) {
        val oldSize = folders.size
        folders = newFolders
        val newSize = folders.size

        when {
            newSize > oldSize -> {
                // Aggiunte cartelle
                notifyItemRangeChanged(0, oldSize)
                notifyItemRangeInserted(oldSize, newSize - oldSize)
            }
            newSize < oldSize -> {
                // Rimosse cartelle
                notifyItemRangeChanged(0, newSize)
                notifyItemRangeRemoved(newSize, oldSize - newSize)
            }
            else -> {
                // Stesso numero di elementi, solo aggiornati
                notifyItemRangeChanged(0, newSize)
            }
        }
    }
}