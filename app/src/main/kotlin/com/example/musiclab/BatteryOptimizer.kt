package com.example.musiclab

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log

/**
 * Gestisce l'ottimizzazione del consumo della batteria
 * - Riduce la frequenza degli aggiornamenti quando in background
 * - Gestisce il WakeLock in modo efficiente
 * - Ottimizza le notifiche
 */
class BatteryOptimizer(private val context: Context) {

    private var wakeLock: PowerManager.WakeLock? = null
    private var isInForeground = false
    private val updateHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "BatteryOptimizer"
        private const val WAKELOCK_TAG = "MusicLab::PlaybackWakeLock"

        // Intervalli di aggiornamento
        const val UPDATE_INTERVAL_FOREGROUND = 1000L // 1 secondo quando app in primo piano
        const val UPDATE_INTERVAL_BACKGROUND = 5000L // 5 secondi quando in background

        @Volatile
        private var instance: BatteryOptimizer? = null

        fun getInstance(context: Context): BatteryOptimizer {
            return instance ?: synchronized(this) {
                instance ?: BatteryOptimizer(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Ottieni l'intervallo di aggiornamento appropriato
     */
    fun getUpdateInterval(): Long {
        return if (isInForeground) {
            UPDATE_INTERVAL_FOREGROUND
        } else {
            UPDATE_INTERVAL_BACKGROUND
        }
    }

    /**
     * Notifica che l'app è in primo piano
     */
    fun setForeground(inForeground: Boolean) {
        isInForeground = inForeground
        Log.d(TAG, "App state changed: ${if (inForeground) "FOREGROUND" else "BACKGROUND"}")
    }

    /**
     * Acquisisci WakeLock quando si riproduce musica
     * (solo se il risparmio batteria non è attivo)
     */
    fun acquireWakeLock(batterySaverEnabled: Boolean) {
        if (batterySaverEnabled) {
            Log.d(TAG, "⚡ Battery saver enabled - skipping WakeLock")
            return
        }

        if (wakeLock == null) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKELOCK_TAG
            ).apply {
                setReferenceCounted(false)
            }
            Log.d(TAG, "🔌 WakeLock created")
        }

        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(10 * 60 * 1000L) // Max 10 minuti, poi si rinnova
            Log.d(TAG, "✅ WakeLock acquired")
        }
    }

    /**
     * Rilascia WakeLock quando la musica è in pausa
     */
    fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.d(TAG, "🔓 WakeLock released")
        }
    }

    /**
     * Cleanup completo
     */
    fun cleanup() {
        releaseWakeLock()
        wakeLock = null
        updateHandler.removeCallbacksAndMessages(null)
        Log.d(TAG, "🧹 BatteryOptimizer cleanup completed")
    }

    /**
     * Controlla se il dispositivo è in modalità risparmio energetico
     */
    fun isPowerSaveMode(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isPowerSaveMode
    }

    /**
     * Ottieni raccomandazione per la qualità della notifica
     * In background e con battery saver, usa LOW priority
     */
    fun getNotificationPriority(batterySaverEnabled: Boolean): Int {
        return if (batterySaverEnabled && !isInForeground) {
            android.app.NotificationManager.IMPORTANCE_MIN
        } else {
            android.app.NotificationManager.IMPORTANCE_LOW
        }
    }

    /**
     * Determina se fare aggiornamenti frequenti della UI
     */
    fun shouldUpdateUI(batterySaverEnabled: Boolean): Boolean {
        // Se battery saver attivo e app in background, riduci aggiornamenti
        return if (batterySaverEnabled && !isInForeground) {
            // Aggiorna solo ogni 5 secondi
            false
        } else {
            true
        }
    }

    /**
     * Schedule un task ottimizzato per la batteria
     */
    fun scheduleTask(
        batterySaverEnabled: Boolean,
        task: Runnable
    ) {
        val delay = if (batterySaverEnabled && !isInForeground) {
            UPDATE_INTERVAL_BACKGROUND
        } else {
            UPDATE_INTERVAL_FOREGROUND
        }

        updateHandler.postDelayed(task, delay)
    }

    /**
     * Cancella tutti i task programmati
     */
    fun cancelAllTasks() {
        updateHandler.removeCallbacksAndMessages(null)
    }
}