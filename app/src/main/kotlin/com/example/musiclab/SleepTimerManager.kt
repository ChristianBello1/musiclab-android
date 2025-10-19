package com.example.musiclab

import android.content.Context
import android.os.CountDownTimer
import android.util.Log
import android.widget.Toast

/**
 * Gestisce il timer di spegnimento automatico della musica
 */
class SleepTimerManager(private val context: Context) {

    private var countDownTimer: CountDownTimer? = null
    private var isActive = false
    private var remainingTimeMs: Long = 0
    private val listeners = mutableListOf<(Long) -> Unit>()

    companion object {
        private const val TAG = "SleepTimerManager"

        @Volatile
        private var instance: SleepTimerManager? = null

        fun getInstance(context: Context): SleepTimerManager {
            return instance ?: synchronized(this) {
                instance ?: SleepTimerManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Avvia il timer di spegnimento
     * @param durationMinutes durata in minuti (5, 10, 15, 30, 60)
     */
    fun startTimer(durationMinutes: Int, musicPlayer: MusicPlayer) {
        stopTimer() // Ferma eventuali timer precedenti

        val durationMs = durationMinutes * 60 * 1000L
        remainingTimeMs = durationMs

        countDownTimer = object : CountDownTimer(durationMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                remainingTimeMs = millisUntilFinished
                notifyListeners(millisUntilFinished)

                // Log ogni minuto
                if (millisUntilFinished % 60000 < 1000) {
                    val minutesLeft = millisUntilFinished / 60000
                    Log.d(TAG, "⏰ Sleep timer: $minutesLeft minuti rimanenti")
                }
            }

            override fun onFinish() {
                Log.d(TAG, "⏰ Sleep timer finito - stoppo la musica")

                // Ferma la musica
                musicPlayer.pause()

                // Resetta il timer
                isActive = false
                remainingTimeMs = 0
                notifyListeners(0)

                Toast.makeText(
                    context,
                    "Timer di spegnimento terminato",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }.start()

        isActive = true
        Log.d(TAG, "✅ Sleep timer avviato: $durationMinutes minuti")

        Toast.makeText(
            context,
            "Timer di spegnimento: $durationMinutes minuti",
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * Ferma il timer
     */
    fun stopTimer() {
        countDownTimer?.cancel()
        countDownTimer = null
        isActive = false
        remainingTimeMs = 0
        notifyListeners(0)
        Log.d(TAG, "⏹️ Sleep timer fermato")
    }

    /**
     * Controlla se il timer è attivo
     */
    fun isTimerActive(): Boolean = isActive

    /**
     * Ottieni il tempo rimanente in millisecondi
     */
    fun getRemainingTime(): Long = remainingTimeMs

    /**
     * Ottieni il tempo rimanente formattato (mm:ss)
     */
    fun getFormattedRemainingTime(): String {
        val totalSeconds = (remainingTimeMs / 1000).toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    /**
     * Aggiungi un listener per ricevere aggiornamenti sul tempo rimanente
     */
    fun addListener(listener: (Long) -> Unit) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    /**
     * Rimuovi un listener
     */
    fun removeListener(listener: (Long) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners(timeRemaining: Long) {
        listeners.forEach { it(timeRemaining) }
    }
}