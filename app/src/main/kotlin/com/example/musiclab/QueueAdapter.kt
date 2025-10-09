package com.example.musiclab

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class QueueAdapter(
    private var songs: MutableList<Song>,
    private var currentSongIndex: Int,
    private val onSongClick: (Song, Int) -> Unit,
    private val onRemoveFromQueue: (Int) -> Unit
) : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {

    private var currentSong: Song? = null

    class QueueViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val dragHandle: ImageView = itemView.findViewById(R.id.drag_handle)
        val songTitle: TextView = itemView.findViewById(R.id.queue_song_title)
        val songArtist: TextView = itemView.findViewById(R.id.queue_song_artist)
        val songDuration: TextView = itemView.findViewById(R.id.queue_song_duration)
        val removeButton: ImageButton = itemView.findViewById(R.id.btn_remove_from_queue)
        val nowPlayingIndicator: ImageView = itemView.findViewById(R.id.now_playing_indicator)
        val statusText: TextView = itemView.findViewById(R.id.queue_status_text)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.queue_item, parent, false)
        return QueueViewHolder(view)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        val song = songs[position]
        val isCurrentSong = position == currentSongIndex

        // Setup song info
        holder.songTitle.text = song.title
        holder.songArtist.text = song.artist
        holder.songDuration.text = song.getFormattedDuration()

        // Setup status (Currently Playing vs Up Next)
        if (isCurrentSong) {
            holder.statusText.text = holder.itemView.context.getString(R.string.currently_playing)
            holder.statusText.visibility = View.VISIBLE
            holder.nowPlayingIndicator.visibility = View.VISIBLE

            // Evidenzia la canzone corrente
            holder.itemView.setBackgroundColor(
                ContextCompat.getColor(holder.itemView.context, R.color.purple_200)
            )
            holder.songTitle.setTextColor(
                ContextCompat.getColor(holder.itemView.context, R.color.black)
            )
        } else {
            val queuePosition = if (position > currentSongIndex) position - currentSongIndex else position + 1
            holder.statusText.text = holder.itemView.context.getString(R.string.up_next) + " #$queuePosition"
            holder.statusText.visibility = View.VISIBLE
            holder.nowPlayingIndicator.visibility = View.GONE

            // Sfondo normale
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            holder.songTitle.setTextColor(
                ContextCompat.getColor(holder.itemView.context, android.R.color.primary_text_light)
            )
        }

        // Setup click listeners
        holder.itemView.setOnClickListener {
            onSongClick(song, position)
        }

        holder.removeButton.setOnClickListener {
            onRemoveFromQueue(position)
        }

        // Il drag handle è automaticamente gestito dal ItemTouchHelper
        // Non serve click listener specifico
    }

    override fun getItemCount(): Int = songs.size

    fun updateQueue(newSongs: MutableList<Song>, newCurrentIndex: Int) {
        songs = newSongs
        currentSongIndex = newCurrentIndex
        notifyDataSetChanged()
    }

    fun updateCurrentIndex(newIndex: Int) {
        val oldIndex = currentSongIndex
        currentSongIndex = newIndex

        // Aggiorna solo gli item che sono cambiati
        if (oldIndex >= 0 && oldIndex < songs.size) {
            notifyItemChanged(oldIndex)
        }
        if (newIndex >= 0 && newIndex < songs.size) {
            notifyItemChanged(newIndex)
        }
    }

    fun updateCurrentSong(song: Song?) {
        currentSong = song
        // Trova l'indice della canzone corrente
        if (song != null) {
            val index = songs.indexOfFirst { it.id == song.id }
            if (index != -1 && index != currentSongIndex) {
                updateCurrentIndex(index)
            }
        }
    }

    // Metodi per il drag & drop
    fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                songs[i] = songs.set(i + 1, songs[i])
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                songs[i] = songs.set(i - 1, songs[i])
            }
        }

        // Aggiorna l'indice della canzone corrente
        when {
            fromPosition == currentSongIndex -> {
                currentSongIndex = toPosition
            }
            fromPosition < currentSongIndex && toPosition >= currentSongIndex -> {
                currentSongIndex--
            }
            fromPosition > currentSongIndex && toPosition <= currentSongIndex -> {
                currentSongIndex++
            }
        }

        notifyItemMoved(fromPosition, toPosition)

        // Aggiorna gli indicatori di stato
        val start = minOf(fromPosition, toPosition)
        val end = maxOf(fromPosition, toPosition)
        notifyItemRangeChanged(start, end - start + 1)

        return true
    }

    fun onItemDismiss(position: Int) {
        if (position < 0 || position >= songs.size) {
            return
        }

        // IMPORTANTE: Chiama il callback PRIMA di rimuovere
        onRemoveFromQueue(position)

        songs.removeAt(position)
        notifyItemRemoved(position)

        // Aggiorna l'indice corrente se necessario
        if (position < currentSongIndex) {
            currentSongIndex--
        } else if (position == currentSongIndex) {
            if (currentSongIndex >= songs.size) {
                currentSongIndex = maxOf(0, songs.size - 1)
            }
        }
    }
}