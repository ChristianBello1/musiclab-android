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
    private var playlist: List<Song> = emptyList()
    private var isShuffleEnabled = false
    private var repeatMode = Player.REPEAT_MODE_OFF

    // NUOVO: Lista di callback invece di un singolo callback
    private val stateChangeListeners = mutableListOf<(isPlaying: Boolean, currentSong: Song?) -> Unit>()

    init {
        initializePlayer()
    }

    // NUOVO: Aggiungi listener
    fun addStateChangeListener(listener: (isPlaying: Boolean, currentSong: Song?) -> Unit) {
        stateChangeListeners.add(listener)
        Log.d("MusicPlayer", "🔗 Listener aggiunto, totale: ${stateChangeListeners.size}")
    }

    // NUOVO: Rimuovi listener
    fun removeStateChangeListener(listener: (isPlaying: Boolean, currentSong: Song?) -> Unit) {
        stateChangeListeners.remove(listener)
        Log.d("MusicPlayer", "🔗 Listener rimosso, totale: ${stateChangeListeners.size}")
    }

    // NUOVO: Notifica tutti i listener
    private fun notifyStateChanged(isPlaying: Boolean, currentSong: Song?) {
        Log.d("MusicPlayer", "📢 Notifying ${stateChangeListeners.size} listeners")
        stateChangeListeners.forEach { listener ->
            try {
                listener.invoke(isPlaying, currentSong)
            } catch (e: Exception) {
                Log.e("MusicPlayer", "❌ Error notifying listener: $e")
            }
        }
    }

    // DEPRECATO ma mantenuto per compatibilità
    var onPlayerStateChanged: ((isPlaying: Boolean, currentSong: Song?) -> Unit)? = null
        set(value) {
            field = value
            // Aggiungi automaticamente alla lista se non c'è già
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

                // Notifica tutti i listener
                notifyStateChanged(isPlaying, currentSong)

                // DEPRECATO: mantieni per compatibilità
                onPlayerStateChanged?.invoke(isPlaying, currentSong)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val currentSong = getCurrentSong()

                // Notifica tutti i listener
                notifyStateChanged(isPlaying, currentSong)

                // DEPRECATO: mantieni per compatibilità
                onPlayerStateChanged?.invoke(isPlaying, currentSong)

                Log.d("MusicPlayer", "Playing changed: $isPlaying")
            }
        })
    }

    fun setPlaylist(songs: List<Song>, startIndex: Int = 0) {
        playlist = songs
        currentSongIndex = startIndex.coerceIn(0, songs.size - 1)
        Log.d("MusicPlayer", "Playlist set: ${songs.size} songs, starting at $currentSongIndex")
    }

    fun playSong(song: Song) {
        val songIndex = playlist.indexOf(song)
        if (songIndex != -1) {
            currentSongIndex = songIndex
            playCurrentSong()
        } else {
            Log.e("MusicPlayer", "Song not found in playlist: ${song.title}")
        }
    }

    private fun playCurrentSong() {
        if (playlist.isEmpty()) return

        val song = playlist[currentSongIndex]
        val mediaItem = MediaItem.fromUri(song.path.toUri())

        exoPlayer?.setMediaItem(mediaItem)
        exoPlayer?.prepare()
        exoPlayer?.play()

        Log.d("MusicPlayer", "Playing: ${song.title} - ${song.artist}")
    }

    fun playPause() {
        if (exoPlayer?.isPlaying == true) {
            exoPlayer?.pause()
        } else {
            if (playlist.isEmpty()) return
            if (exoPlayer?.currentMediaItem == null) {
                playCurrentSong()
            } else {
                exoPlayer?.play()
            }
        }
    }

    fun playNext() {
        if (playlist.isEmpty()) return

        currentSongIndex = if (isShuffleEnabled) {
            playlist.indices.random()
        } else {
            (currentSongIndex + 1) % playlist.size
        }

        playCurrentSong()
    }

    fun playPrevious() {
        if (playlist.isEmpty()) return

        currentSongIndex = if (currentSongIndex > 0) {
            currentSongIndex - 1
        } else {
            playlist.size - 1
        }

        playCurrentSong()
    }

    fun getCurrentSong(): Song? {
        return if (playlist.isNotEmpty() && currentSongIndex < playlist.size) {
            playlist[currentSongIndex]
        } else null
    }

    fun isPlaying(): Boolean {
        return exoPlayer?.isPlaying ?: false
    }

    fun toggleShuffle(): Boolean {
        isShuffleEnabled = !isShuffleEnabled
        Log.d("MusicPlayer", "Shuffle: $isShuffleEnabled")
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
        exoPlayer?.repeatMode = repeatMode
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

    fun release() {
        // Pulisci i listener prima di rilasciare
        stateChangeListeners.clear()
        onPlayerStateChanged = null

        exoPlayer?.release()
        exoPlayer = null
        Log.d("MusicPlayer", "Player released")
    }
}