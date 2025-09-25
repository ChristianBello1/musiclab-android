package com.example.musiclab

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SongAdapter(
    private var songs: List<Song>,
    private val onSongClick: (Song) -> Unit,
    private val onSongMenuClick: (Song, MenuAction) -> Unit = { _, _ -> } // Callback per menu
) : RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

    // Enum per le azioni del menu
    enum class MenuAction {
        ADD_TO_PLAYLIST,
        SONG_DETAILS,
        DELETE_FROM_DEVICE
    }

    class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.song_title)
        val artist: TextView = itemView.findViewById(R.id.song_artist)
        val album: TextView = itemView.findViewById(R.id.song_album)
        val duration: TextView = itemView.findViewById(R.id.song_duration)
        val menuButton: ImageButton = itemView.findViewById(R.id.btn_song_menu)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.song_item, parent, false)
        return SongViewHolder(view)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        val song = songs[position]

        holder.title.text = song.title
        holder.artist.text = song.artist
        holder.album.text = song.album
        holder.duration.text = song.getFormattedDuration()

        // Click listener per riprodurre la canzone
        holder.itemView.setOnClickListener {
            onSongClick(song)
        }

        // NUOVO: Click listener per il menu 3 punti
        holder.menuButton.setOnClickListener { view ->
            showSongMenu(view, song)
        }
    }

    override fun getItemCount(): Int = songs.size

    fun updateSongs(newSongs: List<Song>) {
        songs = newSongs
        notifyItemRangeChanged(0, songs.size)
    }

    // NUOVO: Mostra il PopupMenu con opzioni
    private fun showSongMenu(view: View, song: Song) {
        val popupMenu = PopupMenu(view.context, view)

        // Inflaziona il menu
        popupMenu.menuInflater.inflate(R.menu.song_popup_menu, popupMenu.menu)

        // Gestisci i click del menu
        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_add_to_playlist -> {
                    onSongMenuClick(song, MenuAction.ADD_TO_PLAYLIST)
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

        // Mostra il menu
        popupMenu.show()
    }
}