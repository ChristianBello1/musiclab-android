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

    // Lista di listener da mantenere sincronizzati
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
     * Inizia il crossfade tra le due canzoni con durata DOPPIA
     *
     * CROSSFADE A DUE FASI (se selezioni N secondi, la durata totale è 2N):
     *
     * Se selezioni 8 secondi:
     * - FASE 1 (8s): Canzone 2 fa fade-in da 0% → 100%, Canzone 1 resta a 100%
     * - FASE 2 (8s): Canzone 1 fa fade-out da 100% → 0%, Canzone 2 resta a 100%
     * - TOTALE: 16 secondi di sovrapposizione
     *
     * @param durationSeconds Durata PER FASE (se 8, totale sarà 16 secondi)
     */
    fun startCrossfade(durationSeconds: Int) {
        if (isCrossfading) {
            Log.w("CrossfadeManager", "Crossfade already in progress")
            return
        }

        isCrossfading = true

        // FASE 1: Fade-in della canzone 2 (canzone 1 resta a 100%)
        val fadeInDurationMs = durationSeconds * 1000
        val steps = 50
        val stepDuration = fadeInDurationMs / steps

        Log.d("CrossfadeManager", "🎵 Starting 2-PHASE crossfade (${durationSeconds}s × 2 = ${durationSeconds * 2}s total)")
        Log.d("CrossfadeManager", "    PHASE 1 (${durationSeconds}s): Song 2 fade-in 0%→100%, Song 1 stays 100%")
        Log.d("CrossfadeManager", "    PHASE 2 (${durationSeconds}s): Song 1 fade-out 100%→0%, Song 2 stays 100%")

        // Fai partire il secondary player
        secondaryPlayer?.play()

        var currentStep = 0

        crossfadeRunnable = object : Runnable {
            override fun run() {
                if (currentStep >= steps) {
                    // FASE 1 completata, inizia FASE 2
                    Log.d("CrossfadeManager", "✅ Phase 1 complete, starting Phase 2 (fade-out)")
                    startFadeOutPhase(durationSeconds)
                    return
                }

                val progress = currentStep.toFloat() / steps

                // FASE 1: Canzone 1 resta a volume PIENO
                primaryPlayer?.volume = 1f

                // FASE 1: Canzone 2 fa fade-in da 0 a 1
                secondaryPlayer?.volume = progress

                currentStep++
                handler.postDelayed(this, stepDuration.toLong())
            }
        }

        handler.post(crossfadeRunnable!!)
    }

    /**
     * FASE 2: Fade-out della canzone 1 mentre canzone 2 resta a volume pieno
     */
    private fun startFadeOutPhase(durationSeconds: Int) {
        val fadeOutDurationMs = durationSeconds * 1000
        val steps = 50
        val stepDuration = fadeOutDurationMs / steps

        var currentStep = 0

        crossfadeRunnable = object : Runnable {
            override fun run() {
                if (currentStep >= steps) {
                    // FASE 2 completata, crossfade finito
                    Log.d("CrossfadeManager", "✅ Phase 2 complete, crossfade finished")
                    completeCrossfade()
                    return
                }

                val progress = currentStep.toFloat() / steps

                // FASE 2: Canzone 1 fa fade-out da 1 a 0
                primaryPlayer?.volume = 1f - progress

                // FASE 2: Canzone 2 resta a volume PIENO
                secondaryPlayer?.volume = 1f

                currentStep++
                handler.postDelayed(this, stepDuration.toLong())
            }
        }

        handler.post(crossfadeRunnable!!)
    }

    /**
     * Completa il crossfade e scambia i player
     * 🔧 FIX: Assicuriamoci che il nuovo primary player sia effettivamente in play
     */
    private fun completeCrossfade() {
        Log.d("CrossfadeManager", "✅ Crossfade completed - swapping players")

        // Verifica lo stato del secondary player PRIMA dello swap
        val wasSecondaryPlaying = secondaryPlayer?.isPlaying == true
        Log.d("CrossfadeManager", "Secondary player was playing: $wasSecondaryPlaying")

        // Rimuovi i listener dal primary prima dello swap
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

        // 🔧 FIX CRITICO: Assicurati che il nuovo primary sia a volume pieno E IN PLAY
        primaryPlayer?.volume = 1f

        // Se il player non era in play, fallo ripartire
        if (!wasSecondaryPlaying) {
            Log.w("CrossfadeManager", "⚠️ New primary player was not playing - starting it now")
            primaryPlayer?.play()
        } else {
            // Verifica che sia effettivamente in play
            val isNowPlaying = primaryPlayer?.isPlaying == true
            if (!isNowPlaying) {
                Log.w("CrossfadeManager", "⚠️ New primary player not playing despite swap - forcing play")
                primaryPlayer?.play()
            }
        }

        // Riaggiungi i listener al nuovo primary
        listeners.forEach { listener ->
            primaryPlayer?.addListener(listener)
        }

        isCrossfading = false

        Log.d("CrossfadeManager", "🔄 Listeners re-attached to new primary player")
        Log.d("CrossfadeManager", "✅ New primary player state: ${primaryPlayer?.playbackState}, isPlaying: ${primaryPlayer?.isPlaying}")

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
    // fun getActivePlayer(): ExoPlayer? = primaryPlayer

    /**
     * Controlla se è in corso un crossfade
     */
    // fun isCrossfading(): Boolean = isCrossfading

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
     * Seek nel player appropriato
     *
     * ✅ IMPORTANTE: Se l'utente fa seek DURANTE il crossfade,
     * completiamo subito il crossfade e passiamo alla nuova canzone
     */
    fun seekTo(positionMs: Long) {
        if (isCrossfading) {
            Log.d("CrossfadeManager", "⚠️ User seeked during crossfade - completing crossfade immediately")

            // L'utente vuole controllare la riproduzione, quindi:
            // 1. Cancella il crossfade in corso
            crossfadeRunnable?.let { handler.removeCallbacks(it) }

            // 2. Completa lo swap dei player immediatamente
            completeSwapImmediately()

            // 3. Ora fai seek sulla nuova canzone (primaryPlayer dopo lo swap)
            primaryPlayer?.seekTo(positionMs)

            Log.d("CrossfadeManager", "✅ Crossfade completed early, seeked to ${positionMs}ms on new song")
        } else {
            // Nessun crossfade in corso, seek normale
            primaryPlayer?.seekTo(positionMs)
        }
    }

    /**
     * Completa lo swap dei player senza animazioni
     * 🔧 FIX: Assicuriamoci che il player sia in play anche qui
     */
    private fun completeSwapImmediately() {
        Log.d("CrossfadeManager", "🔄 Completing swap immediately (no fade)")

        // Verifica lo stato del secondary player PRIMA dello swap
        val wasSecondaryPlaying = secondaryPlayer?.isPlaying == true

        // Rimuovi i listener dal primary prima dello swap
        listeners.forEach { listener ->
            primaryPlayer?.removeListener(listener)
        }

        // Ferma il primary player (canzone vecchia)
        primaryPlayer?.stop()
        primaryPlayer?.volume = 1f

        // Scambia i player: secondary diventa primary
        val temp = primaryPlayer
        primaryPlayer = secondaryPlayer
        secondaryPlayer = temp

        // 🔧 FIX: Assicurati che il nuovo primary sia a volume pieno E IN PLAY
        primaryPlayer?.volume = 1f

        if (!wasSecondaryPlaying) {
            Log.w("CrossfadeManager", "⚠️ New primary player was not playing - starting it now")
            primaryPlayer?.play()
        } else {
            val isNowPlaying = primaryPlayer?.isPlaying == true
            if (!isNowPlaying) {
                Log.w("CrossfadeManager", "⚠️ New primary player not playing - forcing play")
                primaryPlayer?.play()
            }
        }

        // Riaggiungi i listener al nuovo primary
        listeners.forEach { listener ->
            primaryPlayer?.addListener(listener)
        }

        isCrossfading = false

        Log.d("CrossfadeManager", "🔄 Swap completed")

        // Notifica il completamento
        onCrossfadeComplete?.invoke()
    }

    /**
     * ✅ Ottieni posizione corrente PER L'UI
     * Durante il crossfade, legge dal secondaryPlayer (nuova canzone)
     * Altrimenti dal primaryPlayer (canzone corrente)
     */
    fun getCurrentPosition(): Long {
        return if (isCrossfading) {
            // Durante il crossfade, mostra la posizione della NUOVA canzone (secondary)
            secondaryPlayer?.currentPosition ?: 0L
        } else {
            // Normalmente, mostra la posizione della canzone corrente (primary)
            primaryPlayer?.currentPosition ?: 0L
        }
    }

    /**
     * ✅ Ottieni durata PER L'UI
     * Durante il crossfade, legge dal secondaryPlayer (nuova canzone)
     * Altrimenti dal primaryPlayer (canzone corrente)
     */
    fun getDuration(): Long {
        return if (isCrossfading) {
            // Durante il crossfade, mostra la durata della NUOVA canzone (secondary)
            secondaryPlayer?.duration ?: 0L
        } else {
            // Normalmente, mostra la durata della canzone corrente (primary)
            primaryPlayer?.duration ?: 0L
        }
    }

    /**
     * ✅ NUOVO: Ottieni posizione dal PRIMARY player (per lo scheduler)
     * Lo scheduler deve sempre monitorare il primaryPlayer per sapere
     * quando triggerare il prossimo crossfade
     */
    fun getPrimaryPosition(): Long = primaryPlayer?.currentPosition ?: 0L

    /**
     * ✅ NUOVO: Ottieni durata dal PRIMARY player (per lo scheduler)
     */
    fun getPrimaryDuration(): Long = primaryPlayer?.duration ?: 0L

    /**
     * Controlla se sta suonando
     */
    fun isPlaying(): Boolean = primaryPlayer?.isPlaying == true || secondaryPlayer?.isPlaying == true

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
    // fun removePlayerListener(listener: Player.Listener) {
    //  listeners.remove(listener)
    // primaryPlayer?.removeListener(listener)
    //    secondaryPlayer?.removeListener(listener)
    //    Log.d("CrossfadeManager", "Listener removed (remaining: ${listeners.size})")
    // }

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