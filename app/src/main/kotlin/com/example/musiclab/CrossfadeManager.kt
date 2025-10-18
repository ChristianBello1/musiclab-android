package com.example.musiclab

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Gestisce il crossfade tra due canzoni usando due ExoPlayer simultanei
 */
class CrossfadeManager(private val context: Context) {

    private var primaryPlayer: ExoPlayer? = null
    private var secondaryPlayer: ExoPlayer? = null

    private var isCrossfading = false
    private val handler = Handler(Looper.getMainLooper())
    private var crossfadeRunnable: Runnable? = null

    // NUOVO: Lista di listener da mantenere sincronizzati
    private val listeners = mutableListOf<Player.Listener>()

    // Callback per notificare quando il crossfade è completato
    var onCrossfadeComplete: (() -> Unit)? = null

    init {
        initializePlayers()
    }

    private fun initializePlayers() {
        primaryPlayer = ExoPlayer.Builder(context).build()
        secondaryPlayer = ExoPlayer.Builder(context).build()

        Log.d("CrossfadeManager", "Two players initialized")
    }

    /**
     * Prepara la prossima canzone nel player secondario
     */
    fun prepareNextSong(songUri: Uri) {
        if (isCrossfading) {
            Log.w("CrossfadeManager", "Already crossfading, ignoring prepareNextSong")
            return
        }

        secondaryPlayer?.apply {
            stop()
            clearMediaItems()
            setMediaItem(MediaItem.fromUri(songUri))
            prepare()
            volume = 0f // Inizia a volume 0
            Log.d("CrossfadeManager", "Next song prepared: $songUri")
        }
    }

    /**
     * Inizia il crossfade tra le due canzoni
     * @param durationSeconds Durata del crossfade in secondi (5-10)
     */
    fun startCrossfade(durationSeconds: Int) {
        if (isCrossfading) {
            Log.w("CrossfadeManager", "Crossfade already in progress")
            return
        }

        isCrossfading = true
        val durationMs = durationSeconds * 1000
        val steps = 50 // 50 step per una transizione smooth
        val stepDuration = durationMs / steps
        val volumeStep = 1f / steps

        Log.d("CrossfadeManager", "🎵 Starting crossfade: ${durationSeconds}s, $steps steps")

        // Fai partire il secondary player
        secondaryPlayer?.play()

        var currentStep = 0

        crossfadeRunnable = object : Runnable {
            override fun run() {
                if (currentStep >= steps) {
                    // Crossfade completato
                    completeCrossfade()
                    return
                }

                val progress = currentStep.toFloat() / steps

                // Fade out primary player
                primaryPlayer?.volume = 1f - progress

                // Fade in secondary player
                secondaryPlayer?.volume = progress

                currentStep++
                handler.postDelayed(this, stepDuration.toLong())
            }
        }

        handler.post(crossfadeRunnable!!)
    }

    /**
     * Completa il crossfade e scambia i player
     */
    private fun completeCrossfade() {
        Log.d("CrossfadeManager", "✅ Crossfade completed - swapping players")

        // IMPORTANTE: Rimuovi i listener dal primary prima dello swap
        listeners.forEach { listener ->
            primaryPlayer?.removeListener(listener)
        }

        // Ferma il primary player (canzone finita)
        primaryPlayer?.stop()
        primaryPlayer?.volume = 1f

        // Scambia i player: secondary diventa primary
        val temp = primaryPlayer
        primaryPlayer = secondaryPlayer
        secondaryPlayer = temp

        // Assicurati che il nuovo primary sia a volume pieno
        primaryPlayer?.volume = 1f

        // IMPORTANTE: Riaggiungi i listener al nuovo primary
        listeners.forEach { listener ->
            primaryPlayer?.addListener(listener)
        }

        isCrossfading = false

        Log.d("CrossfadeManager", "🔄 Listeners re-attached to new primary player")

        // Notifica il completamento
        onCrossfadeComplete?.invoke()
    }

    /**
     * Cancella un crossfade in corso
     */
    fun cancelCrossfade() {
        if (isCrossfading) {
            crossfadeRunnable?.let { handler.removeCallbacks(it) }

            secondaryPlayer?.stop()
            secondaryPlayer?.volume = 0f
            primaryPlayer?.volume = 1f

            isCrossfading = false
            Log.d("CrossfadeManager", "Crossfade cancelled")
        }
    }

    /**
     * Ottieni il player attualmente attivo
     */
    fun getActivePlayer(): ExoPlayer? = primaryPlayer

    /**
     * Controlla se è in corso un crossfade
     */
    fun isCrossfading(): Boolean = isCrossfading

    /**
     * Play sul player primario
     */
    fun play() {
        primaryPlayer?.play()
    }

    /**
     * Pausa sul player primario
     */
    fun pause() {
        primaryPlayer?.pause()
        // Se è in corso crossfade, metti in pausa anche il secondary
        if (isCrossfading) {
            secondaryPlayer?.pause()
        }
    }

    /**
     * Seek nel player primario
     */
    fun seekTo(positionMs: Long) {
        primaryPlayer?.seekTo(positionMs)
    }

    /**
     * Ottieni posizione corrente
     */
    fun getCurrentPosition(): Long = primaryPlayer?.currentPosition ?: 0L

    /**
     * Ottieni durata
     */
    fun getDuration(): Long = primaryPlayer?.duration ?: 0L

    /**
     * Controlla se sta suonando
     */
    fun isPlaying(): Boolean = primaryPlayer?.isPlaying == true

    /**
     * Imposta una nuova canzone nel player primario (senza crossfade)
     */
    fun setMediaItem(songUri: Uri) {
        cancelCrossfade() // Cancella eventuali crossfade in corso

        primaryPlayer?.apply {
            stop()
            clearMediaItems()
            setMediaItem(MediaItem.fromUri(songUri))
            prepare()
            volume = 1f
        }

        Log.d("CrossfadeManager", "New media item set: $songUri")
    }

    /**
     * Aggiungi listener per eventi del player
     * IMPORTANTE: I listener vengono mantenuti sincronizzati tra i player dopo lo swap
     */
    fun addPlayerListener(listener: Player.Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
            primaryPlayer?.addListener(listener)
            Log.d("CrossfadeManager", "Listener added to primary player (total: ${listeners.size})")
        }
    }

    /**
     * Rimuovi listener
     */
    fun removePlayerListener(listener: Player.Listener) {
        listeners.remove(listener)
        primaryPlayer?.removeListener(listener)
        secondaryPlayer?.removeListener(listener)
        Log.d("CrossfadeManager", "Listener removed (remaining: ${listeners.size})")
    }

    /**
     * Rilascia le risorse
     */
    fun release() {
        handler.removeCallbacksAndMessages(null)

        listeners.clear()

        primaryPlayer?.release()
        secondaryPlayer?.release()
        primaryPlayer = null
        secondaryPlayer = null

        Log.d("CrossfadeManager", "Resources released")
    }
}