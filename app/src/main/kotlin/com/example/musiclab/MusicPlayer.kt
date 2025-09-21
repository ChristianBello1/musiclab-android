package com.example.musiclab

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.core.net.toUri

class MusicPlayer(private val context: Context) {

    private var exoPlayer: ExoPlayer? = null
    private var currentSongIndex = 0

    // Coda separata dalla playlist originale
    private var originalPlaylist: List<Song> = emptyList()
    private var currentQueue: MutableList<Song> = mutableListOf()

    private var isShuffleEnabled = false
    private var repeatMode = Player.REPEAT_MODE_OFF

    // Lista di callback invece di un singolo callback
    private val stateChangeListeners = mutableListOf<(isPlaying: Boolean, currentSong: Song?) -> Unit>()

    init {
        initializePlayer()
    }

    // Aggiungi listener
    fun addStateChangeListener(listener: (isPlaying: Boolean, currentSong: Song?) -> Unit) {
        stateChangeListeners.add(listener)
        Log.d("MusicPlayer", "Listener aggiunto, totale: ${stateChangeListeners.size}")
    }

    // Rimuovi listener
    fun removeStateChangeListener(listener: (isPlaying: Boolean, currentSong: Song?) -> Unit) {
        stateChangeListeners.remove(listener)
        Log.d("MusicPlayer", "Listener rimosso, totale: ${stateChangeListeners.size}")
    }

    // Notifica tutti i listener
    private fun notifyStateChanged(isPlaying: Boolean, currentSong: Song?) {
        Log.d("MusicPlayer", "Notifying ${stateChangeListeners.size} listeners")
        stateChangeListeners.forEach { listener ->
            try {
                listener.invoke(isPlaying, currentSong)
            } catch (e: Exception) {
                Log.e("MusicPlayer", "Error notifying listener: $e")
            }
        }
    }

    // NUOVO: Notifica per cambiamenti di stato shuffle/repeat
    private fun notifyShuffleRepeatChanged() {
        val isPlaying = exoPlayer?.isPlaying ?: false
        val currentSong = getCurrentSong()
        notifyStateChanged(isPlaying, currentSong)
        Log.d("MusicPlayer", "Shuffle/Repeat state change notified")
    }

    // DEPRECATO ma mantenuto per compatibilità
    var onPlayerStateChanged: ((isPlaying: Boolean, currentSong: Song?) -> Unit)? = null
        set(value) {
            field = value
            value?.let { listener ->
                if (!stateChangeListeners.contains(listener)) {
                    addStateChangeListener(listener)
                }
            }
        }

    private fun initializePlayer() {
        exoPlayer = ExoPlayer.Builder(context).build()

        exoPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        Log.d("MusicPlayer", "Player ready")
                    }
                    Player.STATE_ENDED -> {
                        Log.d("MusicPlayer", "Song ended")
                        playNext()
                    }
                    Player.STATE_BUFFERING -> {
                        Log.d("MusicPlayer", "Player buffering")
                    }
                    Player.STATE_IDLE -> {
                        Log.d("MusicPlayer", "Player idle")
                    }
                }

                val isPlaying = exoPlayer?.isPlaying ?: false
                val currentSong = getCurrentSong()

                notifyStateChanged(isPlaying, currentSong)
                onPlayerStateChanged?.invoke(isPlaying, currentSong)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val currentSong = getCurrentSong()

                notifyStateChanged(isPlaying, currentSong)
                onPlayerStateChanged?.invoke(isPlaying, currentSong)

                Log.d("MusicPlayer", "Playing changed: $isPlaying")
            }
        })
    }

    fun setPlaylist(songs: List<Song>, startIndex: Int = 0) {
        originalPlaylist = songs

        // Crea la coda iniziale
        currentQueue.clear()
        currentQueue.addAll(songs)

        currentSongIndex = startIndex.coerceIn(0, songs.size - 1)
        Log.d("MusicPlayer", "Playlist set: ${songs.size} songs, queue: ${currentQueue.size}, starting at $currentSongIndex")
    }

    fun playSong(song: Song) {
        val songIndex = currentQueue.indexOf(song)
        if (songIndex != -1) {
            currentSongIndex = songIndex
            playCurrentSong()
        } else {
            Log.e("MusicPlayer", "Song not found in queue: ${song.title}")
        }
    }

    private fun playCurrentSong() {
        if (currentQueue.isEmpty()) {
            Log.w("MusicPlayer", "Queue is empty, cannot play")
            return
        }

        val song = currentQueue[currentSongIndex]
        val mediaItem = MediaItem.fromUri(song.path.toUri())

        exoPlayer?.setMediaItem(mediaItem)
        exoPlayer?.prepare()
        exoPlayer?.play()

        Log.d("MusicPlayer", "Playing: ${song.title} - ${song.artist} (index $currentSongIndex)")
    }

    fun playPause() {
        if (exoPlayer?.isPlaying == true) {
            exoPlayer?.pause()
        } else {
            if (currentQueue.isEmpty()) return
            if (exoPlayer?.currentMediaItem == null) {
                playCurrentSong()
            } else {
                exoPlayer?.play()
            }
        }
    }

    fun playNext() {
        if (currentQueue.isEmpty()) return

        currentSongIndex = if (isShuffleEnabled) {
            currentQueue.indices.random()
        } else {
            (currentSongIndex + 1) % currentQueue.size
        }

        playCurrentSong()
    }

    fun playPrevious() {
        if (currentQueue.isEmpty()) return

        currentSongIndex = if (currentSongIndex > 0) {
            currentSongIndex - 1
        } else {
            currentQueue.size - 1
        }

        playCurrentSong()
    }

    // Metodi per gestire la coda
    fun getCurrentQueue(): List<Song> {
        return currentQueue.toList() // Ritorna una copia per sicurezza
    }

    fun addToQueue(song: Song) {
        currentQueue.add(song)
        Log.d("MusicPlayer", "Added '${song.title}' to queue. Queue size: ${currentQueue.size}")
    }

    fun addToQueue(songs: List<Song>) {
        currentQueue.addAll(songs)
        Log.d("MusicPlayer", "Added ${songs.size} songs to queue. Queue size: ${currentQueue.size}")
    }

    fun removeFromQueue(position: Int): Boolean {
        if (position < 0 || position >= currentQueue.size) {
            Log.w("MusicPlayer", "Invalid position for removal: $position")
            return false
        }

        val removedSong = currentQueue.removeAt(position)

        // Aggiusta l'indice corrente se necessario
        when {
            position < currentSongIndex -> {
                currentSongIndex--
            }
            position == currentSongIndex -> {
                // Se rimuoviamo la canzone corrente
                if (currentSongIndex >= currentQueue.size) {
                    currentSongIndex = maxOf(0, currentQueue.size - 1)
                }
                // Se la coda non è vuota, riproduci la prossima canzone
                if (currentQueue.isNotEmpty() && exoPlayer?.isPlaying == true) {
                    playCurrentSong()
                }
            }
        }

        Log.d("MusicPlayer", "Removed '${removedSong.title}' from position $position. Queue size: ${currentQueue.size}")
        return true
    }

    fun moveInQueue(fromPosition: Int, toPosition: Int): Boolean {
        if (fromPosition < 0 || fromPosition >= currentQueue.size ||
            toPosition < 0 || toPosition >= currentQueue.size) {
            Log.w("MusicPlayer", "Invalid positions for move: $fromPosition -> $toPosition")
            return false
        }

        val movedSong = currentQueue.removeAt(fromPosition)
        currentQueue.add(toPosition, movedSong)

        // Aggiorna l'indice corrente se necessario
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

        Log.d("MusicPlayer", "Moved '${movedSong.title}' from $fromPosition to $toPosition. Current index: $currentSongIndex")
        return true
    }

    fun jumpToIndex(index: Int): Boolean {
        if (index < 0 || index >= currentQueue.size) {
            Log.w("MusicPlayer", "Invalid index for jump: $index")
            return false
        }

        currentSongIndex = index
        playCurrentSong()

        Log.d("MusicPlayer", "Jumped to index $index")
        return true
    }

    fun clearQueue() {
        val currentSong = getCurrentSong()
        currentQueue.clear()

        // Mantieni solo la canzone corrente se esiste
        if (currentSong != null) {
            currentQueue.add(currentSong)
            currentSongIndex = 0
        } else {
            currentSongIndex = -1
        }

        Log.d("MusicPlayer", "Queue cleared. Remaining songs: ${currentQueue.size}")
    }

    fun getCurrentSong(): Song? {
        return if (currentQueue.isNotEmpty() && currentSongIndex >= 0 && currentSongIndex < currentQueue.size) {
            currentQueue[currentSongIndex]
        } else null
    }

    fun isPlaying(): Boolean {
        return exoPlayer?.isPlaying ?: false
    }

    // AGGIORNATO: Toggle shuffle con sincronizzazione
    fun toggleShuffle(): Boolean {
        isShuffleEnabled = !isShuffleEnabled

        // Se shuffle è attivato, rimescola solo le canzoni DOPO quella corrente
        if (isShuffleEnabled && currentQueue.size > currentSongIndex + 1) {
            val beforeCurrent = currentQueue.take(currentSongIndex + 1) // Include la corrente
            val afterCurrent = currentQueue.drop(currentSongIndex + 1).shuffled()

            currentQueue.clear()
            currentQueue.addAll(beforeCurrent)
            currentQueue.addAll(afterCurrent)

            Log.d("MusicPlayer", "Queue shuffled - new order applied")
        }

        // IMPORTANTE: Notifica il cambiamento
        notifyShuffleRepeatChanged()
        Log.d("MusicPlayer", "Shuffle: $isShuffleEnabled")
        return isShuffleEnabled
    }

    fun isShuffleEnabled(): Boolean = isShuffleEnabled

    // AGGIORNATO: Toggle repeat con sincronizzazione
    fun toggleRepeat(): Int {
        repeatMode = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_OFF
            else -> Player.REPEAT_MODE_OFF
        }
        exoPlayer?.repeatMode = repeatMode

        // IMPORTANTE: Notifica il cambiamento
        notifyShuffleRepeatChanged()
        Log.d("MusicPlayer", "Repeat mode: $repeatMode")
        return repeatMode
    }

    fun getRepeatMode(): Int = repeatMode

    fun getCurrentPosition(): Long {
        return exoPlayer?.currentPosition ?: 0L
    }

    fun getDuration(): Long {
        return exoPlayer?.duration ?: 0L
    }

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
        Log.d("MusicPlayer", "Seeked to: ${positionMs}ms")
    }

    fun seekTo(positionSeconds: Int) {
        seekTo(positionSeconds * 1000L)
    }

    fun skipForward(milliseconds: Long) {
        val currentPos = getCurrentPosition()
        val newPos = (currentPos + milliseconds).coerceAtMost(getDuration())
        seekTo(newPos)
        Log.d("MusicPlayer", "Skipped forward ${milliseconds}ms")
    }

    fun skipBackward(milliseconds: Long) {
        val currentPos = getCurrentPosition()
        val newPos = (currentPos - milliseconds).coerceAtLeast(0L)
        seekTo(newPos)
        Log.d("MusicPlayer", "Skipped backward ${milliseconds}ms")
    }

    fun getCurrentIndex(): Int = currentSongIndex

    fun getQueueSize(): Int = currentQueue.size

    fun release() {
        stateChangeListeners.clear()
        onPlayerStateChanged = null

        exoPlayer?.release()
        exoPlayer = null
        Log.d("MusicPlayer", "Player released")
    }
}