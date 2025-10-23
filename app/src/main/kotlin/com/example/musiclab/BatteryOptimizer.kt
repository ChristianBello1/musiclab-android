package com.example.musiclab

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Gestisce l'ottimizzazione del consumo della batteria
 * - Riduce la frequenza degli aggiornamenti quando in background
 * - Gestisce il WakeLock in modo efficiente
 */
class BatteryOptimizer private constructor(context: Context) {

    private val appContext = context.applicationContext
    private var wakeLock: PowerManager.WakeLock? = null
    private var isInForeground = false

    companion object {
        private const val TAG = "BatteryOptimizer"
        private const val WAKELOCK_TAG = "MusicLab::PlaybackWakeLock"

        // Intervalli di aggiornamento
        // const val UPDATE_INTERVAL_FOREGROUND = 1000L // 1 secondo quando app in primo piano
        //const val UPDATE_INTERVAL_BACKGROUND = 5000L // 5 secondi quando in background

        @Volatile
        private var instance: BatteryOptimizer? = null

        fun getInstance(context: Context): BatteryOptimizer {
            return instance ?: synchronized(this) {
                instance ?: BatteryOptimizer(context).also { instance = it }
            }
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
            val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKELOCK_TAG
            ).apply {
                setReferenceCounted(false)
            }
            Log.d(TAG, "🔌 WakeLock created")
        }

        wakeLock?.takeIf { !it.isHeld }?.apply {
            acquire(10 * 60 * 1000L) // Max 10 minuti, poi si rinnova
            Log.d(TAG, "✅ WakeLock acquired")
        }
    }

    /**
     * Rilascia WakeLock quando la musica è in pausa
     */
    fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.apply {
            release()
            Log.d(TAG, "🔓 WakeLock released")
        }
    }
}