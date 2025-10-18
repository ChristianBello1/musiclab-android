package com.example.musiclab

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.core.net.toUri

class MusicPlayer(private val context: Context) {

    private var exoPlayer: ExoPlayer? = null
    private var currentSong: Song? = null

    private var currentSongIndex = 0

    // Coda separata dalla playlist originale
    private var originalPlaylist: List<Song> = emptyList()
    private var currentQueue: MutableList<Song> = mutableListOf()

    // Mantieni l'ordine originale per il de-shuffle
    private var originalQueueOrder: List<Song> = emptyList()

    private var isShuffleEnabled = false
    private var repeatMode = Player.REPEAT_MODE_OFF

    // Lista di callback invece di un singolo callback
    private val stateChangeListeners = mutableListOf<(isPlaying: Boolean, currentSong: Song?) -> Unit>()

    // Listener specifico per cambiamenti di coda (shuffle/reorder)
    private val queueChangeListeners = mutableListOf<() -> Unit>()

    // === AUTOMIX VARIABLES ===
    private var crossfadeManager: CrossfadeManager? = null
    private val crossfadeHandler = Handler(Looper.getMainLooper())
    private var crossfadeCheckRunnable: Runnable? = null
    private var isCrossfadeScheduled = false
    private var crossfadeTriggered = false
    // === FINE AUTOMIX VARIABLES ===

    // Player Listener che funziona per ENTRAMBI i player
    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    Log.d("MusicPlayer", "Player ready")
                }
                Player.STATE_ENDED -> {
                    Log.d("MusicPlayer", "🎵 Song ended - automix enabled: ${isAutomixEnabled()}")

                    // Se automix è attivo E il crossfade non è stato triggerato, significa che qualcosa è andato storto
                    // Quindi facciamo partire manualmente la prossima canzone
                    if (isAutomixEnabled()) {
                        if (!crossfadeTriggered) {
                            Log.w("MusicPlayer", "⚠️ Automix active but crossfade not triggered, playing next manually")
                            playNext()
                        }
                        // Se il crossfade è stato triggerato, non facciamo nulla perché sta già gestendo
                    } else {
                        // Automix non attivo, comportamento normale
                        playNext()
                    }
                }
                Player.STATE_BUFFERING -> {
                    Log.d("MusicPlayer", "Player buffering")
                }
                Player.STATE_IDLE -> {
                    Log.d("MusicPlayer", "Player idle")
                }
            }

            val isPlaying = isPlaying()
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
    }

    init {
        initializePlayer()
        initializeCrossfadeManager()
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

    // Gestione listener per cambiamenti coda
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

    // Notifica cambiamenti della coda
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

    // === AUTOMIX METHODS ===

    private fun initializeCrossfadeManager() {
        crossfadeManager = CrossfadeManager(context)

        // IMPORTANTE: Aggiungi il listener al crossfade manager
        crossfadeManager?.addPlayerListener(playerListener)

        crossfadeManager?.onCrossfadeComplete = {
            Log.d("MusicPlayer", "✅ Crossfade completed, moving to next song")

            // Il crossfade è completato, aggiorna l'indice
            moveToNextSongAfterCrossfade()
            crossfadeTriggered = false
            isCrossfadeScheduled = false
        }

        Log.d("MusicPlayer", "CrossfadeManager initialized with listener")
    }

    private fun moveToNextSongAfterCrossfade() {
        // Incrementa l'indice per riflettere che siamo passati alla prossima canzone
        if (repeatMode == Player.REPEAT_MODE_ONE) {
            // In modalità repeat one, rimaniamo sulla stessa canzone
            Log.d("MusicPlayer", "Repeat ONE - staying on same song")
        } else if (currentSongIndex < currentQueue.size - 1) {
            currentSongIndex++
            Log.d("MusicPlayer", "Moved to next song at index $currentSongIndex")
        } else if (repeatMode == Player.REPEAT_MODE_ALL) {
            currentSongIndex = 0
            Log.d("MusicPlayer", "Repeat ALL - back to start")
        } else {
            Log.d("MusicPlayer", "End of queue reached")
        }

        currentSong = getCurrentSong()
        notifyStateChanged(isPlaying(), currentSong)

        // Schedula il prossimo crossfade se necessario
        if (isAutomixEnabled() && currentSong != null) {
            scheduleCrossfadeCheck()
        }
    }

    private fun isAutomixEnabled(): Boolean {
        return SettingsActivity.isAutomixEnabled(context)
    }

    private fun getCrossfadeDuration(): Int {
        return SettingsActivity.getCrossfadeDuration(context)
    }

    private fun scheduleCrossfadeCheck() {
        if (!isAutomixEnabled() || crossfadeManager == null) {
            Log.d("MusicPlayer", "❌ Cannot schedule crossfade: automix=${isAutomixEnabled()}, manager=${crossfadeManager != null}")
            return
        }

        cancelScheduledCrossfade()

        crossfadeTriggered = false
        isCrossfadeScheduled = true

        Log.d("MusicPlayer", "📅 Scheduling crossfade check...")

        crossfadeCheckRunnable = object : Runnable {
            override fun run() {
                if (!isCrossfadeScheduled) {
                    Log.d("MusicPlayer", "Crossfade check cancelled")
                    return
                }

                val currentPos = crossfadeManager?.getCurrentPosition() ?: 0L
                val duration = crossfadeManager?.getDuration() ?: 0L

                if (duration > 0 && !crossfadeTriggered) {
                    val crossfadeDurationMs = getCrossfadeDuration() * 1000L
                    val timeUntilEnd = duration - currentPos

                    Log.d("MusicPlayer", "⏱️ Crossfade check: pos=${currentPos/1000}s, duration=${duration/1000}s, timeLeft=${timeUntilEnd/1000}s, trigger at ${crossfadeDurationMs/1000}s")

                    if (timeUntilEnd <= crossfadeDurationMs && timeUntilEnd > 0) {
                        // È il momento di iniziare il crossfade!
                        triggerCrossfade()
                    } else {
                        // Controlla di nuovo tra 500ms
                        crossfadeHandler.postDelayed(this, 500)
                    }
                } else {
                    // Controlla di nuovo
                    crossfadeHandler.postDelayed(this, 500)
                }
            }
        }

        crossfadeHandler.postDelayed(crossfadeCheckRunnable!!, 500)
        Log.d("MusicPlayer", "✅ Crossfade check scheduled")
    }

    private fun triggerCrossfade() {
        if (crossfadeTriggered || !isAutomixEnabled()) {
            Log.d("MusicPlayer", "❌ Cannot trigger: already triggered=$crossfadeTriggered, automix=${isAutomixEnabled()}")
            return
        }

        crossfadeTriggered = true

        Log.d("MusicPlayer", "🎵 ========== TRIGGERING CROSSFADE ==========")

        // Determina quale sarà la prossima canzone
        val nextSong = getNextSongForCrossfade()

        if (nextSong != null) {
            val nextUri = Uri.parse(nextSong.path)
            crossfadeManager?.prepareNextSong(nextUri)

            // Inizia il crossfade
            val duration = getCrossfadeDuration()
            crossfadeManager?.startCrossfade(duration)

            Log.d("MusicPlayer", "✅ Crossfade started: ${duration}s to '${nextSong.title}'")
        } else {
            Log.d("MusicPlayer", "❌ No next song for crossfade")
            crossfadeTriggered = false
        }
    }

    private fun getNextSongForCrossfade(): Song? {
        return when (repeatMode) {
            Player.REPEAT_MODE_ONE -> {
                // Ripeti la canzone corrente
                Log.d("MusicPlayer", "Next song for crossfade: REPEAT CURRENT")
                currentSong
            }
            Player.REPEAT_MODE_ALL -> {
                // Prossima canzone, o torna all'inizio
                val next = if (currentSongIndex < currentQueue.size - 1) {
                    currentQueue[currentSongIndex + 1]
                } else {
                    currentQueue.firstOrNull()
                }
                Log.d("MusicPlayer", "Next song for crossfade: REPEAT ALL - ${next?.title}")
                next
            }
            else -> {
                // Normale: prossima canzone se esiste
                val next = if (currentSongIndex < currentQueue.size - 1) {
                    currentQueue[currentSongIndex + 1]
                } else {
                    null
                }
                Log.d("MusicPlayer", "Next song for crossfade: NORMAL - ${next?.title}")
                next
            }
        }
    }

    private fun cancelScheduledCrossfade() {
        if (isCrossfadeScheduled || crossfadeTriggered) {
            Log.d("MusicPlayer", "🚫 Cancelling scheduled crossfade")
        }

        isCrossfadeScheduled = false
        crossfadeCheckRunnable?.let {
            crossfadeHandler.removeCallbacks(it)
        }
        crossfadeManager?.cancelCrossfade()
    }

    // === END AUTOMIX METHODS ===

    private fun initializePlayer() {
        exoPlayer = ExoPlayer.Builder(context).build()
        exoPlayer?.addListener(playerListener)

        Log.d("MusicPlayer", "ExoPlayer initialized with listener")
    }

    fun setPlaylist(songs: List<Song>, startIndex: Int = 0) {
        originalPlaylist = songs

        // Crea la coda iniziale
        currentQueue.clear()
        currentQueue.addAll(songs)

        // Salva l'ordine originale
        originalQueueOrder = songs.toList()

        currentSongIndex = startIndex.coerceIn(0, songs.size - 1)

        // Notifica il cambiamento della coda
        notifyQueueChanged()

        Log.d("MusicPlayer", "Playlist set: ${songs.size} songs, queue: ${currentQueue.size}, starting at $currentSongIndex")
    }

    fun playSong(song: Song) {
        Log.d("MusicPlayer", "▶️ Playing song: ${song.title}")
        currentSong = song

        // Cancella eventuali crossfade in corso
        cancelScheduledCrossfade()

        if (isAutomixEnabled() && crossfadeManager != null) {
            Log.d("MusicPlayer", "🎵 Using AUTOMIX mode")
            // Usa CrossfadeManager
            val uri = Uri.parse(song.path)
            crossfadeManager?.setMediaItem(uri)
            crossfadeManager?.play()

            // Schedula il controllo per il crossfade
            scheduleCrossfadeCheck()
        } else {
            Log.d("MusicPlayer", "🎵 Using NORMAL mode")
            // Usa ExoPlayer normale
            val mediaItem = MediaItem.fromUri(Uri.parse(song.path))
            exoPlayer?.setMediaItem(mediaItem)
            exoPlayer?.prepare()
            exoPlayer?.play()
        }

        notifyStateChanged(true, song)
    }

    private fun playCurrentSong() {
        if (currentQueue.isEmpty()) {
            Log.w("MusicPlayer", "Queue is empty, cannot play")
            return
        }

        val song = currentQueue[currentSongIndex]
        playSong(song)
    }

    fun play() {
        if (isAutomixEnabled() && crossfadeManager != null) {
            crossfadeManager?.play()

            // Riprendi il controllo crossfade se necessario
            if (!isCrossfadeScheduled && !crossfadeTriggered) {
                scheduleCrossfadeCheck()
            }
        } else {
            exoPlayer?.play()
        }
        notifyStateChanged(true, currentSong)
    }

    fun pause() {
        if (isAutomixEnabled() && crossfadeManager != null) {
            crossfadeManager?.pause()
        } else {
            exoPlayer?.pause()
        }

        // Ferma il controllo crossfade durante la pausa
        cancelScheduledCrossfade()

        notifyStateChanged(false, currentSong)
    }

    fun playPause() {
        if (isPlaying()) {
            pause()
        } else {
            if (currentQueue.isEmpty()) return
            if (currentSong == null) {
                playCurrentSong()
            } else {
                play()
            }
        }
    }

    fun playNext() {
        if (currentQueue.isEmpty()) return

        Log.d("MusicPlayer", "⏭️ Play next - current index: $currentSongIndex, queue size: ${currentQueue.size}")

        currentSongIndex = if (isShuffleEnabled) {
            // Con shuffle, prendi la prossima nella coda già mescolata
            (currentSongIndex + 1) % currentQueue.size
        } else {
            (currentSongIndex + 1) % currentQueue.size
        }

        Log.d("MusicPlayer", "⏭️ New index: $currentSongIndex")
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
        return currentQueue.toList()
    }

    fun addToQueue(song: Song) {
        currentQueue.add(song)
        notifyQueueChanged()
        Log.d("MusicPlayer", "Added '${song.title}' to queue. Queue size: ${currentQueue.size}")
    }

    fun addToQueue(songs: List<Song>) {
        currentQueue.addAll(songs)
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

        if (position < 0 || position >= currentQueue.size) {
            Log.w("MusicPlayer", "❌ Invalid position for removal: $position")
            return false
        }

        val removedSong = currentQueue.removeAt(position)
        Log.d("MusicPlayer", "🗑️ Removed: ${removedSong.title}")

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

                if (currentQueue.isNotEmpty() && isPlaying()) {
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

        notifyQueueChanged()

        Log.d("MusicPlayer", "Queue cleared. Remaining songs: ${currentQueue.size}")
    }

    fun getCurrentSong(): Song? {
        return if (currentQueue.isNotEmpty() && currentSongIndex >= 0 && currentSongIndex < currentQueue.size) {
            currentQueue[currentSongIndex]
        } else null
    }

    fun isPlaying(): Boolean {
        return if (isAutomixEnabled() && crossfadeManager != null) {
            crossfadeManager?.isPlaying() ?: false
        } else {
            exoPlayer?.isPlaying ?: false
        }
    }

    fun toggleShuffle(): Boolean {
        isShuffleEnabled = !isShuffleEnabled

        Log.d("MusicPlayer", "=== TOGGLE SHUFFLE START ===")
        Log.d("MusicPlayer", "Shuffle enabled: $isShuffleEnabled")

        val currentSong = getCurrentSong()

        if (isShuffleEnabled) {
            // ATTIVA SHUFFLE
            Log.d("MusicPlayer", "Attivando shuffle...")

            if (currentSong != null && currentQueue.size > 1) {
                val songsToShuffle = currentQueue.toMutableList()
                songsToShuffle.removeAt(currentSongIndex)
                songsToShuffle.shuffle()

                currentQueue.clear()
                currentQueue.add(currentSong)
                currentQueue.addAll(songsToShuffle)

                currentSongIndex = 0

                Log.d("MusicPlayer", "Shuffle applicato. Current song ora all'indice 0")
            }

        } else {
            // DISATTIVA SHUFFLE
            Log.d("MusicPlayer", "Disattivando shuffle...")

            if (originalQueueOrder.isNotEmpty() && currentSong != null) {
                val originalIndex = originalQueueOrder.indexOf(currentSong)

                currentQueue.clear()
                currentQueue.addAll(originalQueueOrder)

                currentSongIndex = if (originalIndex != -1) originalIndex else 0

                Log.d("MusicPlayer", "Ordine originale ripristinato. Current song all'indice $currentSongIndex")
            }
        }

        notifyQueueChanged()
        notifyStateChanged(isPlaying(), currentSong)

        Log.d("MusicPlayer", "=== TOGGLE SHUFFLE END ===")
        Log.d("MusicPlayer", "Final queue size: ${currentQueue.size}, current index: $currentSongIndex")

        return isShuffleEnabled
    }

    fun isShuffleEnabled(): Boolean = isShuffleEnabled

    fun toggleRepeat(): Int {
        repeatMode = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_OFF
            else -> Player.REPEAT_MODE_OFF
        }

        // Imposta repeatMode solo su exoPlayer, non su crossfadeManager
        // perché con automix gestiamo manualmente il repeat
        if (!isAutomixEnabled()) {
            exoPlayer?.repeatMode = repeatMode
        }

        notifyStateChanged(isPlaying(), getCurrentSong())

        Log.d("MusicPlayer", "Repeat mode: $repeatMode")
        return repeatMode
    }

    fun getRepeatMode(): Int = repeatMode

    fun getCurrentPosition(): Long {
        return if (isAutomixEnabled() && crossfadeManager != null) {
            crossfadeManager?.getCurrentPosition() ?: 0L
        } else {
            exoPlayer?.currentPosition ?: 0L
        }
    }

    fun getDuration(): Long {
        return if (isAutomixEnabled() && crossfadeManager != null) {
            crossfadeManager?.getDuration() ?: 0L
        } else {
            exoPlayer?.duration ?: 0L
        }
    }

    fun seekTo(positionMs: Long) {
        if (isAutomixEnabled() && crossfadeManager != null) {
            crossfadeManager?.seekTo(positionMs)
        } else {
            exoPlayer?.seekTo(positionMs)
        }

        // Reset del crossfade dopo il seek
        cancelScheduledCrossfade()
        crossfadeTriggered = false

        if (isPlaying() && isAutomixEnabled()) {
            scheduleCrossfadeCheck()
        }

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
        queueChangeListeners.clear()
        onPlayerStateChanged = null

        // Rilascia crossfade manager
        cancelScheduledCrossfade()
        crossfadeHandler.removeCallbacksAndMessages(null)
        crossfadeManager?.release()
        crossfadeManager = null

        exoPlayer?.release()
        exoPlayer = null
        Log.d("MusicPlayer", "Player released")
    }
}