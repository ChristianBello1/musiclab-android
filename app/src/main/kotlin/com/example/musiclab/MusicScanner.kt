// Sostituisci il file MusicScanner.kt con questa versione per debug
package com.example.musiclab

import android.content.Context
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale
import android.Manifest

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val path: String,
    val size: Long
) {
    fun getFormattedDuration(): String {
        val minutes = (duration / 1000) / 60
        val seconds = (duration / 1000) % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}

class MusicScanner(private val context: Context) {

    fun scanMusicFiles(): List<Song> {
        Log.d("MusicScanner", "=== INIZIO SCANSIONE MUSICA ===")

        // 1. Controlla permessi
        val hasPermission = checkPermissions()
        if (!hasPermission) {
            Log.e("MusicScanner", "❌ PERMESSI MANCANTI!")
            return emptyList()
        }

        val songs = mutableListOf<Song>()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.SIZE
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} = 1"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        Log.d("MusicScanner", "🔍 Eseguendo query MediaStore...")

        try {
            val cursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder
            )

            Log.d("MusicScanner", "📂 Cursor ottenuto: ${cursor != null}")

            cursor?.use { c ->
                Log.d("MusicScanner", "📊 Numero di righe nel cursor: ${c.count}")

                if (c.count == 0) {
                    Log.w("MusicScanner", "⚠️ Nessuna riga trovata nel MediaStore!")
                    return@use
                }

                val idColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val pathColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val sizeColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)

                var processedCount = 0
                var filteredCount = 0

                while (c.moveToNext()) {
                    processedCount++

                    val id = c.getLong(idColumn)
                    val title = c.getString(titleColumn) ?: "Unknown Title"
                    val artist = c.getString(artistColumn) ?: "Unknown Artist"
                    val album = c.getString(albumColumn) ?: "Unknown Album"
                    val duration = c.getLong(durationColumn)
                    val path = c.getString(pathColumn) ?: ""
                    val size = c.getLong(sizeColumn)

                     // Log.d("MusicScanner", "🎵 Trovata: $title - $artist (${size/1024}KB)")

                    // Filtra file troppo piccoli (probabilmente non sono canzoni)
                    if (size > 500 * 1024) { // > 500KB (ridotto da 1MB)
                        songs.add(Song(id, title, artist, album, duration, path, size))
                        filteredCount++
                    } else {
                        Log.d("MusicScanner", "⏭️ Saltata (troppo piccola): $title")
                    }
                }

                Log.d("MusicScanner", "📈 Processate: $processedCount, Valide: $filteredCount")
            }
        } catch (e: Exception) {
            Log.e("MusicScanner", "💥 ERRORE durante scansione: $e")
            e.printStackTrace()
        }

        Log.d("MusicScanner", "✅ SCANSIONE COMPLETATA: ${songs.size} canzoni trovate")
        return songs
    }

    private fun checkPermissions(): Boolean {
        Log.d("MusicScanner", "🔐 Controllo permessi...")

        val androidVersion = android.os.Build.VERSION.SDK_INT
        Log.d("MusicScanner", "📱 Android API Level: $androidVersion")

        return if (androidVersion >= 33) { // Android 13+
            val permission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_AUDIO
            )
            val hasPermission = permission == PackageManager.PERMISSION_GRANTED
            Log.d("MusicScanner", "🎵 READ_MEDIA_AUDIO: ${if (hasPermission) "✅ GRANTED" else "❌ DENIED"}")
            hasPermission
        } else {
            val permission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
            val hasPermission = permission == PackageManager.PERMISSION_GRANTED
            Log.d("MusicScanner", "📁 READ_EXTERNAL_STORAGE: ${if (hasPermission) "✅ GRANTED" else "❌ DENIED"}")
            hasPermission
        }
    }
}