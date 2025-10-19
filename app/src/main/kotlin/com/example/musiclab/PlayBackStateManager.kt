package com.example.musiclab

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Gestisce la persistenza dello stato di riproduzione
 * Salva: canzone corrente, posizione, coda, shuffle, repeat
 */
class PlaybackStateManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val gson = Gson()

    companion object {
        private const val PREFS_NAME = "PlaybackState"
        private const val KEY_CURRENT_SONG_ID = "current_song_id"
        private const val KEY_POSITION = "position"
        private const val KEY_QUEUE = "queue"
        private const val KEY_CURRENT_INDEX = "current_index"
        private const val KEY_IS_SHUFFLE = "is_shuffle"
        private const val KEY_REPEAT_MODE = "repeat_mode"
        private const val KEY_WAS_PLAYING = "was_playing"
    }

    /**
     * Salva lo stato completo della riproduzione
     */
    fun savePlaybackState(
        currentSong: Song?,
        position: Long,
        queue: List<Song>,
        currentIndex: Int,
        isShuffleEnabled: Boolean,
        repeatMode: Int,
        wasPlaying: Boolean
    ) {
        try {
            val editor = prefs.edit()

            // Salva ID della canzone corrente
            editor.putLong(KEY_CURRENT_SONG_ID, currentSong?.id ?: -1L)

            // Salva posizione di riproduzione
            editor.putLong(KEY_POSITION, position)

            // Salva coda come JSON
            val queueIds = queue.map { it.id }
            val queueJson = gson.toJson(queueIds)
            editor.putString(KEY_QUEUE, queueJson)

            // Salva indice corrente
            editor.putInt(KEY_CURRENT_INDEX, currentIndex)

            // Salva shuffle e repeat
            editor.putBoolean(KEY_IS_SHUFFLE, isShuffleEnabled)
            editor.putInt(KEY_REPEAT_MODE, repeatMode)

            // Salva se stava riproducendo
            editor.putBoolean(KEY_WAS_PLAYING, wasPlaying)

            editor.apply()

            Log.d("PlaybackStateManager", "✅ State saved: song=${currentSong?.title}, pos=$position ms, queue=${queue.size}, index=$currentIndex")
        } catch (e: Exception) {
            Log.e("PlaybackStateManager", "❌ Error saving state: ${e.message}")
        }
    }

    /**
     * Carica lo stato salvato
     * Ritorna null se non c'è stato salvato
     */
    fun loadPlaybackState(allSongs: List<Song>): PlaybackState? {
        try {
            val currentSongId = prefs.getLong(KEY_CURRENT_SONG_ID, -1L)
            if (currentSongId == -1L) {
                Log.d("PlaybackStateManager", "No saved state found")
                return null
            }

            // Carica posizione
            val position = prefs.getLong(KEY_POSITION, 0L)

            // Carica coda
            val queueJson = prefs.getString(KEY_QUEUE, null)
            val queueIds: List<Long> = if (queueJson != null) {
                val type = object : TypeToken<List<Long>>() {}.type
                gson.fromJson(queueJson, type)
            } else {
                emptyList()
            }

            // Ricostruisci la coda dalle canzoni reali
            val queue = queueIds.mapNotNull { id ->
                allSongs.firstOrNull { it.id == id }
            }

            if (queue.isEmpty()) {
                Log.w("PlaybackStateManager", "Queue is empty after reconstruction")
                return null
            }

            // Carica indice
            val currentIndex = prefs.getInt(KEY_CURRENT_INDEX, 0)

            // Carica shuffle e repeat
            val isShuffleEnabled = prefs.getBoolean(KEY_IS_SHUFFLE, false)
            val repeatMode = prefs.getInt(KEY_REPEAT_MODE, 0)

            // Carica se stava riproducendo
            val wasPlaying = prefs.getBoolean(KEY_WAS_PLAYING, false)

            // Trova la canzone corrente
            val currentSong = queue.getOrNull(currentIndex)

            if (currentSong == null) {
                Log.w("PlaybackStateManager", "Current song not found in queue")
                return null
            }

            Log.d("PlaybackStateManager", "✅ State loaded: song=${currentSong.title}, pos=$position ms, queue=${queue.size}, index=$currentIndex")

            return PlaybackState(
                currentSong = currentSong,
                position = position,
                queue = queue,
                currentIndex = currentIndex,
                isShuffleEnabled = isShuffleEnabled,
                repeatMode = repeatMode,
                wasPlaying = wasPlaying
            )

        } catch (e: Exception) {
            Log.e("PlaybackStateManager", "❌ Error loading state: ${e.message}")
            return null
        }
    }

    /**
     * Cancella lo stato salvato
     */
    fun clearState() {
        prefs.edit().clear().apply()
        Log.d("PlaybackStateManager", "State cleared")
    }

    /**
     * Data class per lo stato di riproduzione
     */
    data class PlaybackState(
        val currentSong: Song,
        val position: Long,
        val queue: List<Song>,
        val currentIndex: Int,
        val isShuffleEnabled: Boolean,
        val repeatMode: Int,
        val wasPlaying: Boolean
    )
}