package com.example.musiclab

import android.content.Context
import android.content.Intent
import android.os.Build

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
            musicPlayer = MusicPlayer(context.applicationContext)
            startMusicService(context)
        }
        return musicPlayer!!
    }

    private fun startMusicService(context: Context) {
        val serviceIntent = Intent(context, MusicService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    fun release() {
        musicPlayer?.release()
        musicPlayer = null
    }

    fun isInitialized(): Boolean = musicPlayer != null
}