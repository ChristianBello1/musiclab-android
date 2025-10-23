package com.example.musiclab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class MusicService : Service() {

    private lateinit var musicPlayer: MusicPlayer
    private lateinit var mediaSessionManager: MediaSessionManager
    private lateinit var batteryOptimizer: BatteryOptimizer

    // ✅ NUOVO: BroadcastReceiver per disconnessione Bluetooth e cuffie
    private var audioDeviceReceiver: BroadcastReceiver? = null
    private var isForegroundStarted = false

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "music_playback_channel"
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

        // Inizializza MediaSession per controlli cuffie/Bluetooth
        mediaSessionManager = MediaSessionManager(applicationContext, musicPlayer)
        mediaSessionManager.initialize()
        Log.d(TAG, "✅ MediaSession initialized")

        // Inizializza BatteryOptimizer
        batteryOptimizer = BatteryOptimizer.getInstance(applicationContext)
        Log.d(TAG, "✅ BatteryOptimizer initialized")

        // Avvia subito come foreground con notifica placeholder
        startForegroundWithPlaceholder()

        // Aggiungi listener con gestione WakeLock
        musicPlayer.addStateChangeListener { isPlaying, currentSong ->
            updateNotification(currentSong, isPlaying)

            // Gestisci WakeLock in base allo stato di riproduzione
            val batterySaverEnabled = SettingsActivity.isBatterySaverEnabled(applicationContext)
            if (isPlaying) {
                batteryOptimizer.acquireWakeLock(batterySaverEnabled)
            } else {
                batteryOptimizer.releaseWakeLock()
            }
        }

        // ✅ NUOVO: Registra receiver per disconnessione audio
        registerAudioDeviceReceiver()
    }

    // ✅ NUOVA FUNZIONE: Registra receiver per Bluetooth e cuffie
    private fun registerAudioDeviceReceiver() {
        audioDeviceReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    // Bluetooth disconnesso
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        Log.d(TAG, "🔵 Bluetooth disconnesso - pausing music")
                        musicPlayer.pause()
                    }

                    // Cuffie scollegate
                    Intent.ACTION_HEADSET_PLUG -> {
                        val state = intent.getIntExtra("state", -1)
                        if (state == 0) { // 0 = disconnesso, 1 = connesso
                            Log.d(TAG, "🎧 Cuffie scollegate - pausing music")
                            musicPlayer.pause()
                        }
                    }

                    // Audio diventa "noisy" (es. cuffie bluetooth disconnesse)
                    AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                        Log.d(TAG, "🔊 Audio becoming noisy - pausing music")
                        musicPlayer.pause()
                    }
                }
            }
        }

        // Registra il receiver con tutti i filtri necessari
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }

        registerReceiver(audioDeviceReceiver, filter)
        Log.d(TAG, "✅ Audio device receiver registered")
    }

    private fun startForegroundWithPlaceholder() {
        val notification = createPlaceholderNotification()
        startForeground(NOTIFICATION_ID, notification)
        isForegroundStarted = true
        Log.d(TAG, "Started as foreground service with placeholder")
    }

    private fun createPlaceholderNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MusicLab")
            .setContentText("Pronto per riprodurre")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // Aggiungi MediaStyle anche al placeholder
        val sessionToken = mediaSessionManager.getSessionToken()
        if (sessionToken != null) {
            builder.setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(sessionToken)
            )
        }

        return builder.build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "=== ON START COMMAND ===")
        Log.d(TAG, "Action: ${intent?.action}")

        when (intent?.action) {
            ACTION_PREVIOUS -> {
                Log.d(TAG, "Previous clicked")
                musicPlayer.playPrevious()
            }
            ACTION_PLAY_PAUSE -> {
                Log.d(TAG, "Play/Pause clicked")

                // Richiedi audio focus quando si preme play
                if (!musicPlayer.isPlaying()) {
                    val gotFocus = mediaSessionManager.requestAudioFocus()
                    if (gotFocus) {
                        musicPlayer.play()
                        Log.d(TAG, "✅ Audio focus obtained, playing")
                    } else {
                        Log.w(TAG, "⚠️ Failed to get audio focus")
                    }
                } else {
                    musicPlayer.pause()
                }
            }
            ACTION_NEXT -> {
                Log.d(TAG, "Next clicked")
                musicPlayer.playNext()
            }
            ACTION_STOP -> {
                Log.d(TAG, "Stop clicked")

                // Rilascia audio focus quando si stoppa
                mediaSessionManager.abandonAudioFocus()

                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                // Aggiorna notifica se c'è una canzone
                val currentSong = musicPlayer.getCurrentSong()
                val isPlaying = musicPlayer.isPlaying()

                if (currentSong != null) {
                    updateNotification(currentSong, isPlaying)
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
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows currently playing music"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created")
        }
    }

    fun updateNotification(song: Song?, isPlaying: Boolean) {
        val notification = if (song != null) {
            createNotification(song, isPlaying)
        } else {
            createPlaceholderNotification()
        }

        if (!isForegroundStarted) {
            startForeground(NOTIFICATION_ID, notification)
            isForegroundStarted = true
        } else {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
        }

        Log.d(TAG, "Notification updated: ${song?.title ?: "placeholder"}")
    }

    private fun createNotification(song: Song, isPlaying: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val previousIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MusicService::class.java).setAction(ACTION_PREVIOUS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, MusicService::class.java).setAction(ACTION_PLAY_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, MusicService::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setSubText(song.album)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_previous, "Previous", previousIntent)
            .addAction(playPauseIcon, "Play/Pause", playPauseIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", nextIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(isPlaying)

        // Aggiungi MediaStyle con session token per controlli esterni
        val sessionToken = mediaSessionManager.getSessionToken()
        if (sessionToken != null) {
            builder.setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            Log.d(TAG, "✅ MediaStyle added to notification with session token")
        }

        return builder.build()
    }

    override fun onDestroy() {
        super.onDestroy()

        // Rilascia MediaSession
        mediaSessionManager.release()

        // Rilascia WakeLock
        batteryOptimizer.releaseWakeLock()

        // ✅ NUOVO: Unregister del receiver
        audioDeviceReceiver?.let {
            try {
                unregisterReceiver(it)
                Log.d(TAG, "✅ Audio device receiver unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering receiver: ${e.message}")
            }
        }

        Log.d(TAG, "Service destroyed, MediaSession released, WakeLock released")
    }
}