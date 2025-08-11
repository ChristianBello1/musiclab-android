package com.example.musiclab

import android.content.Context

/**
 * Manager per gestire l'istanza globale del MusicPlayer.
 * Evita memory leak usando applicationContext.
 */
class MusicPlayerManager private constructor() {

    companion object {
        @Volatile
        private var INSTANCE: MusicPlayerManager? = null

        fun getInstance(): MusicPlayerManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MusicPlayerManager().also { INSTANCE = it }
            }
        }
    }

    private var musicPlayer: MusicPlayer? = null

    fun getMusicPlayer(context: Context): MusicPlayer {
        if (musicPlayer == null) {
            // Usa solo applicationContext per evitare memory leak
            musicPlayer = MusicPlayer(context.applicationContext)
        }
        return musicPlayer!!
    }

    fun release() {
        musicPlayer?.release()
        musicPlayer = null
    }

    fun isInitialized(): Boolean = musicPlayer != null
}