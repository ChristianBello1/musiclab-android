package com.example.musiclab

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.media.session.MediaButtonReceiver

/**
 * Gestisce MediaSession per controlli esterni (cuffie, Bluetooth, lock screen, Android Auto)
 */
class MediaSessionManager(
    private val context: Context,
    private val musicPlayer: MusicPlayer
) {

    private var mediaSession: MediaSessionCompat? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    companion object {
        private const val TAG = "MediaSessionManager"
        private const val MEDIA_SESSION_TAG = "MusicLab"
    }

    /**
     * Inizializza la MediaSession
     */
    fun initialize() {
        try {
            mediaSession = MediaSessionCompat(context, MEDIA_SESSION_TAG).apply {
                // Imposta callback per i controlli
                setCallback(mediaSessionCallback)

                // Imposta flags per supportare tutti i controlli
                setFlags(
                    MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                            MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
                )

                // Imposta stato iniziale
                setPlaybackState(buildPlaybackState(false, 0L))

                // Attiva la sessione
                isActive = true

                Log.d(TAG, "✅ MediaSession initialized and activated")
            }

            // Listener per aggiornare metadata e stato
            musicPlayer.addStateChangeListener { isPlaying, currentSong ->
                updateMediaSession(isPlaying, currentSong)
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error initializing MediaSession: ${e.message}")
        }
    }

    /**
     * Callback per gestire i comandi dai controlli esterni
     */
    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {

        override fun onPlay() {
            Log.d(TAG, "▶️ Media button: PLAY")
            musicPlayer.play()
        }

        override fun onPause() {
            Log.d(TAG, "⏸️ Media button: PAUSE")
            musicPlayer.pause()
        }

        override fun onSkipToNext() {
            Log.d(TAG, "⏭️ Media button: NEXT")
            musicPlayer.playNext()
        }

        override fun onSkipToPrevious() {
            Log.d(TAG, "⏮️ Media button: PREVIOUS")
            musicPlayer.playPrevious()
        }

        override fun onStop() {
            Log.d(TAG, "⏹️ Media button: STOP")
            musicPlayer.pause()
        }

        override fun onSeekTo(pos: Long) {
            Log.d(TAG, "⏩ Media button: SEEK to $pos ms")
            musicPlayer.seekTo(pos)
        }

        override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
            Log.d(TAG, "🎛️ Media button event: $mediaButtonEvent")
            return super.onMediaButtonEvent(mediaButtonEvent)
        }
    }

    /**
     * Aggiorna metadata e playback state della MediaSession
     */
    private fun updateMediaSession(isPlaying: Boolean, currentSong: Song?) {
        try {
            if (currentSong != null) {
                // Aggiorna metadata (info canzone)
                val metadata = MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentSong.title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentSong.artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentSong.album)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentSong.duration)
                    .build()

                mediaSession?.setMetadata(metadata)

                // Aggiorna playback state
                val position = musicPlayer.getCurrentPosition()
                mediaSession?.setPlaybackState(buildPlaybackState(isPlaying, position))

                Log.d(TAG, "📊 MediaSession updated: ${currentSong.title}, playing=$isPlaying")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error updating MediaSession: ${e.message}")
        }
    }

    /**
     * Costruisce lo stato di riproduzione per la MediaSession
     */
    private fun buildPlaybackState(isPlaying: Boolean, position: Long): PlaybackStateCompat {
        val state = if (isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }

        return PlaybackStateCompat.Builder()
            .setState(state, position, 1.0f)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_STOP
            )
            .build()
    }

    /**
     * Richiedi focus audio (importante per interrompere altre app)
     */
    fun requestAudioFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.requestAudioFocus(
                android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build()
            )
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }

        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /**
     * Rilascia focus audio
     */
    fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.abandonAudioFocusRequest(
                android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build()
            )
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    /**
     * Listener per gestire i cambiamenti di audio focus
     */
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Perso focus permanentemente (es. telefono)
                Log.d(TAG, "🔇 Audio focus LOSS - pausing")
                musicPlayer.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Perso focus temporaneamente (es. notifica)
                Log.d(TAG, "🔇 Audio focus LOSS_TRANSIENT - pausing")
                musicPlayer.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Abbassa il volume temporaneamente
                Log.d(TAG, "🔉 Audio focus LOSS_TRANSIENT_CAN_DUCK")
                // Potresti implementare il "ducking" (abbassare volume)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // Riacquistato focus
                Log.d(TAG, "🔊 Audio focus GAIN")
                // Opzionale: riprendi automaticamente
                // musicPlayer.play()
            }
        }
    }

    /**
     * Ottieni il MediaSession token (per notifiche)
     */
    fun getSessionToken() = mediaSession?.sessionToken

    /**
     * Rilascia risorse
     */
    fun release() {
        abandonAudioFocus()
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        Log.d(TAG, "MediaSession released")
    }
}