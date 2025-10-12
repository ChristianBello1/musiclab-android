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

    // NUOVO: Mantieni l'ordine originale per il de-shuffle
    private var originalQueueOrder: List<Song> = emptyList()

    private var isShuffleEnabled = false
    private var repeatMode = Player.REPEAT_MODE_OFF

    // Lista di callback invece di un singolo callback
    private val stateChangeListeners = mutableListOf<(isPlaying: Boolean, currentSong: Song?) -> Unit>()

    // NUOVO: Listener specifico per cambiamenti di coda (shuffle/reorder)
    private val queueChangeListeners = mutableListOf<() -> Unit>()

    init {
        initializePlayer()
    }

    // Aggiungi listener per stato
    fun addStateChangeListener(listener: (isPlaying: Boolean, currentSong: Song?) -> Unit) {
        stateChangeListeners.add(listener)
        Log.d("MusicPlayer", "State listener aggiunto, totale: ${stateChangeListeners.size}")
    }

    // Rimuovi listener per stato
    fun removeStateChangeListener(listener: (isPlaying: Boolean, currentSong: Song?) -> Unit) {
        stateChangeListeners.remove(listener)
        Log.d("MusicPlayer", "State listener rimosso, totale: ${stateChangeListeners.size}")
    }

    // NUOVO: Gestione listener per cambiamenti coda
    fun addQueueChangeListener(listener: () -> Unit) {
        queueChangeListeners.add(listener)
        Log.d("MusicPlayer", "Queue listener aggiunto, totale: ${queueChangeListeners.size}")
    }

    fun removeQueueChangeListener(listener: () -> Unit) {
        queueChangeListeners.remove(listener)
        Log.d("MusicPlayer", "Queue listener rimosso, totale: ${queueChangeListeners.size}")
    }

    // Notifica tutti i listener di stato
    private fun notifyStateChanged(isPlaying: Boolean, currentSong: Song?) {
        Log.d("MusicPlayer", "Notifying ${stateChangeListeners.size} state listeners")
        stateChangeListeners.forEach { listener ->
            try {
                listener.invoke(isPlaying, currentSong)
            } catch (e: Exception) {
                Log.e("MusicPlayer", "Error notifying state listener: $e")
            }
        }
    }

    // NUOVO: Notifica cambiamenti della coda
    private fun notifyQueueChanged() {
        Log.d("MusicPlayer", "Notifying ${queueChangeListeners.size} queue listeners")
        queueChangeListeners.forEach { listener ->
            try {
                listener.invoke()
            } catch (e: Exception) {
                Log.e("MusicPlayer", "Error notifying queue listener: $e")
            }
        }
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

        // NUOVO: Salva l'ordine originale
        originalQueueOrder = songs.toList()

        currentSongIndex = startIndex.coerceIn(0, songs.size - 1)

        // IMPORTANTE: Notifica il cambiamento della coda
        notifyQueueChanged()

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
            // Con shuffle, prendi la prossima nella coda già mescolata
            (currentSongIndex + 1) % currentQueue.size
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
        // IMPORTANTE: Notifica il cambiamento
        notifyQueueChanged()
        Log.d("MusicPlayer", "Added '${song.title}' to queue. Queue size: ${currentQueue.size}")
    }

    fun addToQueue(songs: List<Song>) {
        currentQueue.addAll(songs)
        // IMPORTANTE: Notifica il cambiamento
        notifyQueueChanged()
        Log.d("MusicPlayer", "Added ${songs.size} songs to queue. Queue size: ${currentQueue.size}")
    }

    fun removeFromQueue(position: Int): Boolean {
        Log.d("MusicPlayer", "=== REMOVE FROM QUEUE START ===")
        Log.d("MusicPlayer", "🎯 Requested position: $position")
        Log.d("MusicPlayer", "📊 Before removal:")
        Log.d("MusicPlayer", "   Queue size: ${currentQueue.size}")
        Log.d("MusicPlayer", "   Current index: $currentSongIndex")
        Log.d("MusicPlayer", "   Current song: ${getCurrentSong()?.title}")

        // Mostra le canzoni intorno alla posizione
        if (position > 0 && position < currentQueue.size) {
            Log.d("MusicPlayer", "   Position ${position-1}: ${currentQueue[position-1].title}")
        }
        if (position < currentQueue.size) {
            Log.d("MusicPlayer", "   Position $position (TO REMOVE): ${currentQueue[position].title}")
        }
        if (position + 1 < currentQueue.size) {
            Log.d("MusicPlayer", "   Position ${position+1}: ${currentQueue[position+1].title}")
        }

        if (position < 0 || position >= currentQueue.size) {
            Log.w("MusicPlayer", "❌ Invalid position for removal: $position")
            return false
        }

        val removedSong = currentQueue.removeAt(position)
        Log.d("MusicPlayer", "🗑️ Removed: ${removedSong.title}")

        // Aggiorna l'indice
        val oldIndex = currentSongIndex

        when {
            position < currentSongIndex -> {
                currentSongIndex--
                Log.d("MusicPlayer", "📍 Removed song BEFORE current, index: $oldIndex → $currentSongIndex")
            }
            position == currentSongIndex -> {
                Log.d("MusicPlayer", "📍 Removed CURRENT song")
                if (currentSongIndex >= currentQueue.size) {
                    currentSongIndex = maxOf(0, currentQueue.size - 1)
                }

                if (currentQueue.isNotEmpty() && exoPlayer?.isPlaying == true) {
                    Log.d("MusicPlayer", "▶️ Playing next song at index: $currentSongIndex")
                    playCurrentSong()
                }
            }
            else -> {
                Log.d("MusicPlayer", "📍 Removed song AFTER current, index stays: $currentSongIndex")
            }
        }

        Log.d("MusicPlayer", "📊 After removal:")
        Log.d("MusicPlayer", "   Queue size: ${currentQueue.size}")
        Log.d("MusicPlayer", "   Current index: $currentSongIndex")
        Log.d("MusicPlayer", "   Current song: ${getCurrentSong()?.title}")

        // Mostra le nuove canzoni intorno all'indice corrente
        if (currentSongIndex > 0 && currentSongIndex <= currentQueue.size) {
            Log.d("MusicPlayer", "   Position ${currentSongIndex-1}: ${currentQueue[currentSongIndex-1].title}")
        }
        if (currentSongIndex < currentQueue.size) {
            Log.d("MusicPlayer", "   Position $currentSongIndex (CURRENT): ${currentQueue[currentSongIndex].title}")
        }
        if (currentSongIndex + 1 < currentQueue.size) {
            Log.d("MusicPlayer", "   Position ${currentSongIndex+1} (NEXT): ${currentQueue[currentSongIndex+1].title}")
        }

        // Notifica DOPO aver stampato tutto
        Log.d("MusicPlayer", "🔔 Notifying ${queueChangeListeners.size} listeners")
        notifyQueueChanged()

        Log.d("MusicPlayer", "=== REMOVE FROM QUEUE END ===")

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

        // IMPORTANTE: Notifica il cambiamento
        notifyQueueChanged()

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

        // IMPORTANTE: Notifica il cambiamento
        notifyQueueChanged()

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

    // COMPLETAMENTE RISCRITTO: Toggle shuffle con sincronizzazione perfetta
    fun toggleShuffle(): Boolean {
        isShuffleEnabled = !isShuffleEnabled

        Log.d("MusicPlayer", "=== TOGGLE SHUFFLE START ===")
        Log.d("MusicPlayer", "Shuffle enabled: $isShuffleEnabled")

        val currentSong = getCurrentSong()

        if (isShuffleEnabled) {
            // ATTIVA SHUFFLE: Mescola tutto tranne la canzone corrente
            Log.d("MusicPlayer", "Attivando shuffle...")

            if (currentSong != null && currentQueue.size > 1) {
                // Rimuovi la canzone corrente temporaneamente
                val songsToShuffle = currentQueue.toMutableList()
                songsToShuffle.removeAt(currentSongIndex)

                // Mescola le altre canzoni
                songsToShuffle.shuffle()

                // Ricostruisci la coda: canzone corrente + canzoni mescolate
                currentQueue.clear()
                currentQueue.add(currentSong)
                currentQueue.addAll(songsToShuffle)

                // La canzone corrente è ora all'indice 0
                currentSongIndex = 0

                Log.d("MusicPlayer", "Shuffle applicato. Current song ora all'indice 0")
            }

        } else {
            // DISATTIVA SHUFFLE: Ripristina ordine originale
            Log.d("MusicPlayer", "Disattivando shuffle...")

            if (originalQueueOrder.isNotEmpty() && currentSong != null) {
                // Trova la posizione della canzone corrente nell'ordine originale
                val originalIndex = originalQueueOrder.indexOf(currentSong)

                // Ripristina l'ordine originale
                currentQueue.clear()
                currentQueue.addAll(originalQueueOrder)

                // Aggiorna l'indice corrente
                currentSongIndex = if (originalIndex != -1) originalIndex else 0

                Log.d("MusicPlayer", "Ordine originale ripristinato. Current song all'indice $currentSongIndex")
            }
        }

        // FONDAMENTALE: Notifica TUTTI i listener che la coda è cambiata
        notifyQueueChanged()
        notifyStateChanged(isPlaying(), currentSong)

        Log.d("MusicPlayer", "=== TOGGLE SHUFFLE END ===")
        Log.d("MusicPlayer", "Final queue size: ${currentQueue.size}, current index: $currentSongIndex")

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
        notifyStateChanged(isPlaying(), getCurrentSong())

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
        queueChangeListeners.clear() // NUOVO: Pulisci anche i queue listeners
        onPlayerStateChanged = null

        exoPlayer?.release()
        exoPlayer = null
        Log.d("MusicPlayer", "Player released")
    }
}