package com.example.musiclab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class MusicService : Service() {

    private lateinit var musicPlayer: MusicPlayer
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "music_playback_channel"

    companion object {
        private const val TAG = "MusicService"
        const val ACTION_PLAY_PAUSE = "ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "ACTION_NEXT"
        const val ACTION_PREVIOUS = "ACTION_PREVIOUS"
        const val ACTION_STOP = "ACTION_STOP"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "=== SERVICE CREATED ===")

        createNotificationChannel()
        musicPlayer = MusicPlayerManager.getInstance().getMusicPlayer(applicationContext)

        // Aggiungi listener per aggiornare notifica
        musicPlayer.addStateChangeListener { isPlaying, currentSong ->
            updateNotification(currentSong, isPlaying)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "=== ON START COMMAND ===")
        Log.d(TAG, "Action: ${intent?.action}")

        when (intent?.action) {
            ACTION_PREVIOUS -> {
                Log.d(TAG, "⏮️ Previous clicked")
                musicPlayer.playPrevious()
            }
            ACTION_PLAY_PAUSE -> {
                Log.d(TAG, "⏯️ Play/Pause clicked")
                musicPlayer.playPause()
            }
            ACTION_NEXT -> {
                Log.d(TAG, "⏭️ Next clicked")
                musicPlayer.playNext()
            }
            ACTION_STOP -> {
                Log.d(TAG, "⏹️ Stop clicked")
                stopForeground(true)
                stopSelf()
            }
            else -> {
                // ✅ FORZA L'UPDATE DELLA NOTIFICA
                Log.d(TAG, "🔔 First start - creating notification")
                val currentSong = musicPlayer.getCurrentSong()
                val isPlaying = musicPlayer.isPlaying()
                Log.d(TAG, "Current song: ${currentSong?.title}, Playing: $isPlaying")

                if (currentSong != null) {
                    updateNotification(currentSong, isPlaying)
                    Log.d(TAG, "✅ Notification should be visible now!")
                } else {
                    Log.e(TAG, "❌ No current song - can't show notification")
                }
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Music Playback",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows currently playing music"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "✅ Notification channel created")
        }
    }

    fun updateNotification(song: Song?, isPlaying: Boolean) {
        if (song == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            return
        }

        val notification = createNotification(song, isPlaying)
        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "🔔 Notification updated: ${song.title}")
    }

    private fun createNotification(song: Song, isPlaying: Boolean): Notification {
        // Intent per aprire l'app quando clicchi sulla notifica
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent per Previous
        val previousIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MusicService::class.java).setAction(ACTION_PREVIOUS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent per Play/Pause
        val playPauseIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, MusicService::class.java).setAction(ACTION_PLAY_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent per Next
        val nextIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, MusicService::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Icona Play o Pause
        val playPauseIcon = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setSubText(song.album)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)

            // ✅ AGGIUNGI CATEGORIA MEDIA
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)

            // Azioni
            .addAction(android.R.drawable.ic_media_previous, "Previous", previousIntent)
            .addAction(playPauseIcon, "Play/Pause", playPauseIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", nextIntent)

            // ✅ MediaStyle CORRETTO
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
            )

            // ✅ PRIORITÀ CORRETTA
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(isPlaying)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "❌ Service destroyed")
    }
}