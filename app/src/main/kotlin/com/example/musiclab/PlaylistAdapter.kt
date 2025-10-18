package com.example.musiclab

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import android.util.Log

class PlaylistAdapter(
    private var playlists: List<Playlist>,
    private val onPlaylistClick: (Playlist) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.PlaylistViewHolder>() {

    class PlaylistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val playlistIcon: ImageView = itemView.findViewById(R.id.playlist_icon)
        val playlistName: TextView = itemView.findViewById(R.id.playlist_name)
        val songCount: TextView = itemView.findViewById(R.id.playlist_song_count)
        val menuButton: ImageButton = itemView.findViewById(R.id.playlist_menu)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.playlist_item, parent, false)
        return PlaylistViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        val playlist = playlists[position]

        holder.playlistName.text = playlist.name

        // SEMPLIFICATO: Solo conteggio canzoni
        val songCount = playlist.songs.size
        holder.songCount.text = holder.itemView.context.getString(
            R.string.songs_in_playlist,
            songCount
        )

        Log.d("PlaylistAdapter", "Playlist '${playlist.name}': $songCount songs")
        holder.songCount.visibility = View.GONE

        // Click listener per aprire PlaylistSongsActivity
        holder.itemView.setOnClickListener {
            onPlaylistClick(playlist)
        }

        // Nascondi il menu button (gestito ora in PlaylistSongsActivity)
        holder.menuButton.visibility = View.GONE

    }

    override fun getItemCount(): Int = playlists.size

    fun updatePlaylists(newPlaylists: List<Playlist>) {
        playlists = newPlaylists
        notifyDataSetChanged()
    }
}