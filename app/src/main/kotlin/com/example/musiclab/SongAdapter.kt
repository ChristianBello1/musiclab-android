package com.example.musiclab

import android.graphics.Color
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class SongAdapter(
    private var songs: List<Song>,
    private val onSongClick: (Song) -> Unit,
    private val onSongMenuClick: (Song, MenuAction) -> Unit = { _, _ -> },
    private val onLongPress: ((Song) -> Unit)? = null,
    private val onSelectionChanged: ((Int) -> Unit)? = null,
    private val contextType: ContextType = ContextType.FOLDER
) : RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

    enum class ContextType {
        FOLDER,
        PLAYLIST,
        MAIN
    }

    enum class MenuAction {
        ADD_TO_PLAYLIST,
        SONG_DETAILS,
        REMOVE_FROM_PLAYLIST,
        DELETE_FROM_DEVICE
    }

    // Gestione selezione multipla
    private var isSelectionMode = false
    private val selectedSongs = mutableSetOf<Long>()

    class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.song_title)
        val artist: TextView = itemView.findViewById(R.id.song_artist)
        val album: TextView = itemView.findViewById(R.id.song_album)
        val duration: TextView = itemView.findViewById(R.id.song_duration)
        val menuButton: ImageButton = itemView.findViewById(R.id.btn_song_menu)
        val checkBox: CheckBox = itemView.findViewById(R.id.song_checkbox)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.song_item, parent, false)
        return SongViewHolder(view)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        val song = songs[position]
        val isSelected = selectedSongs.contains(song.id)

        holder.title.text = song.title
        holder.artist.text = song.artist
        holder.album.text = song.album
        holder.duration.text = song.getFormattedDuration()

        // Gestione checkbox e selezione visiva
        if (isSelectionMode) {
            holder.checkBox.visibility = View.VISIBLE
            holder.menuButton.visibility = View.GONE
            holder.checkBox.isChecked = isSelected

            // Cambia background se selezionato
            if (isSelected) {
                holder.itemView.setBackgroundColor(
                    ContextCompat.getColor(holder.itemView.context, R.color.selection_dark)
                )
            } else {
                holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            }
        } else {
            holder.checkBox.visibility = View.GONE
            holder.menuButton.visibility = View.VISIBLE
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
        }

        // Click normale
        holder.itemView.setOnClickListener {
            if (isSelectionMode) {
                toggleSelection(song.id)
                notifyItemChanged(position)
            } else {
                onSongClick(song)
            }
        }

        // Long press per entrare in modalità selezione
        holder.itemView.setOnLongClickListener {
            if (!isSelectionMode) {
                enterSelectionMode(song.id)
                onLongPress?.invoke(song)
                notifyDataSetChanged()
            }
            true
        }

        // Checkbox click
        holder.checkBox.setOnClickListener {
            toggleSelection(song.id)
            notifyItemChanged(position)
        }

        // Menu button
        holder.menuButton.setOnClickListener { view ->
            if (!isSelectionMode) {
                showSongMenu(view, song)
            }
        }
    }

    override fun getItemCount(): Int = songs.size

    fun updateSongs(newSongs: List<Song>) {
        try {
            Log.d("SongAdapter", "Updating songs: ${songs.size} -> ${newSongs.size}")

            // ✅ SOLUZIONE: Usa notifyDataSetChanged() che è più sicuro
            // invece di notifyItemRangeChanged()
            songs = newSongs
            notifyDataSetChanged()

            Log.d("SongAdapter", "✅ Songs updated successfully")
        } catch (e: Exception) {
            Log.e("SongAdapter", "❌ Error updating songs: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun enterSelectionMode(firstSongId: Long) {
        isSelectionMode = true
        selectedSongs.clear()
        selectedSongs.add(firstSongId)
        onSelectionChanged?.invoke(selectedSongs.size)
    }

    fun exitSelectionMode() {
        isSelectionMode = false
        selectedSongs.clear()
        onSelectionChanged?.invoke(0)
        notifyDataSetChanged()
    }

    fun toggleSelection(songId: Long) {
        if (selectedSongs.contains(songId)) {
            selectedSongs.remove(songId)
        } else {
            selectedSongs.add(songId)
        }

        onSelectionChanged?.invoke(selectedSongs.size)

        if (selectedSongs.isEmpty()) {
            exitSelectionMode()
        }
    }

    fun getSelectedSongs(): List<Song> {
        return songs.filter { selectedSongs.contains(it.id) }
    }

    // fun isInSelectionMode(): Boolean = isSelectionMode

    // fun getSelectedCount(): Int = selectedSongs.size

    private fun showSongMenu(view: View, song: Song) {
        val popupMenu = PopupMenu(view.context, view)

        // ✅ Menu diversi in base al contesto
        when (contextType) {
            ContextType.PLAYLIST -> {
                // ✅ IN PLAYLIST: Aggiungi ad ALTRA + Rimuovi da QUESTA + Dettagli
                popupMenu.menu.add(0, R.id.menu_add_to_playlist, 0, "Aggiungi ad altra playlist")
                popupMenu.menu.add(0, R.id.menu_remove_from_playlist, 1, "Rimuovi da questa playlist")
                popupMenu.menu.add(0, R.id.menu_song_details, 2, view.context.getString(R.string.song_details))
            }
            ContextType.FOLDER, ContextType.MAIN -> {
                // IN CARTELLE/MAIN: Aggiungi a playlist + Dettagli + Elimina
                popupMenu.menu.add(0, R.id.menu_add_to_playlist, 0, view.context.getString(R.string.add_to_playlist))
                popupMenu.menu.add(0, R.id.menu_song_details, 1, view.context.getString(R.string.song_details))
                popupMenu.menu.add(0, R.id.menu_delete_from_device, 2, view.context.getString(R.string.delete_from_device))
            }
        }

        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_add_to_playlist -> {
                    onSongMenuClick(song, MenuAction.ADD_TO_PLAYLIST)
                    true
                }
                R.id.menu_remove_from_playlist -> {
                    onSongMenuClick(song, MenuAction.REMOVE_FROM_PLAYLIST)
                    true
                }
                R.id.menu_song_details -> {
                    onSongMenuClick(song, MenuAction.SONG_DETAILS)
                    true
                }
                R.id.menu_delete_from_device -> {
                    onSongMenuClick(song, MenuAction.DELETE_FROM_DEVICE)
                    true
                }
                else -> false
            }
        }

        popupMenu.show()
    }
}