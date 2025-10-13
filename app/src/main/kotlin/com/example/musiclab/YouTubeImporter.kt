package com.example.musiclab

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

class YouTubeImporter(private val context: Context) {

    companion object {
        private const val TAG = "YouTubeImporter"

        // ✅ INSERISCI QUI LA TUA API KEY
        private const val YOUTUBE_API_KEY = "AIzaSyBrthCFqS1X4TQucREqXKouxHaBUG4rimg"

        private const val YOUTUBE_API_BASE = "https://www.googleapis.com/youtube/v3"
    }

    private val httpClient = OkHttpClient()

    data class ImportResult(
        val playlistTitle: String,
        val totalVideos: Int,
        val matchedSongs: List<Song>,
        val unmatchedTitles: List<String>
    )

    suspend fun importPlaylist(playlistUrl: String, localSongs: List<Song>): ImportResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== IMPORT START ===")
        Log.d(TAG, "URL: $playlistUrl")

        // 1. Estrai Playlist ID dall'URL
        val playlistId = extractPlaylistId(playlistUrl)
            ?: throw IllegalArgumentException("URL playlist non valido")

        Log.d(TAG, "Playlist ID: $playlistId")

        // 2. Ottieni info playlist
        val playlistInfo = fetchPlaylistInfo(playlistId)
        val playlistTitle = playlistInfo.first
        val videoTitles = playlistInfo.second

        Log.d(TAG, "Playlist: $playlistTitle")
        Log.d(TAG, "Video trovati: ${videoTitles.size}")

        // 3. Fai matching con canzoni locali
        val matched = mutableListOf<Song>()
        val unmatched = mutableListOf<String>()

        videoTitles.forEach { videoTitle ->
            val cleanTitle = cleanVideoTitle(videoTitle)
            val matchedSong = findBestMatch(cleanTitle, localSongs)

            if (matchedSong != null) {
                matched.add(matchedSong)
                Log.d(TAG, "✅ Match: '$cleanTitle' → ${matchedSong.title}")
            } else {
                unmatched.add(videoTitle)
                Log.d(TAG, "❌ No match: '$cleanTitle'")
            }
        }

        Log.d(TAG, "=== IMPORT COMPLETE ===")
        Log.d(TAG, "Matched: ${matched.size}, Unmatched: ${unmatched.size}")

        ImportResult(
            playlistTitle = playlistTitle,
            totalVideos = videoTitles.size,
            matchedSongs = matched,
            unmatchedTitles = unmatched
        )
    }

    private fun extractPlaylistId(url: String): String? {
        // Supporta vari formati URL YouTube
        val patterns = listOf(
            "list=([^&]+)",
            "playlist\\?list=([^&]+)"
        )

        patterns.forEach { pattern ->
            val regex = Regex(pattern)
            val match = regex.find(url)
            if (match != null) {
                return match.groupValues[1]
            }
        }

        return null
    }

    private fun fetchPlaylistInfo(playlistId: String): Pair<String, List<String>> {
        val videoTitles = mutableListOf<String>()
        var playlistTitle = "Imported Playlist"
        var nextPageToken: String? = null

        do {
            val url = buildPlaylistItemsUrl(playlistId, nextPageToken)
            val request = Request.Builder().url(url).build()

            val response = httpClient.newCall(request).execute()
            val jsonResponse = JSONObject(response.body?.string() ?: "{}")

            // Ottieni titolo playlist (solo prima volta)
            if (playlistTitle == "Imported Playlist") {
                try {
                    val snippet = jsonResponse.getJSONArray("items")
                        .getJSONObject(0)
                        .getJSONObject("snippet")
                    playlistTitle = snippet.optString("playlistTitle", "Imported Playlist")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not get playlist title")
                }
            }

            // Estrai titoli video
            val items = jsonResponse.getJSONArray("items")
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val snippet = item.getJSONObject("snippet")
                val title = snippet.getString("title")

                // Salta video privati/cancellati
                if (title != "Private video" && title != "Deleted video") {
                    videoTitles.add(title)
                }
            }

            nextPageToken = jsonResponse.optString("nextPageToken", null)

        } while (nextPageToken != null)

        return Pair(playlistTitle, videoTitles)
    }

    private fun buildPlaylistItemsUrl(playlistId: String, pageToken: String?): String {
        var url = "$YOUTUBE_API_BASE/playlistItems?" +
                "part=snippet&" +
                "maxResults=50&" +
                "playlistId=$playlistId&" +
                "key=$YOUTUBE_API_KEY"

        if (pageToken != null) {
            url += "&pageToken=$pageToken"
        }

        return url
    }

    private fun cleanVideoTitle(title: String): String {
        var cleaned = title
            .lowercase()
            // Rimuovi suffissi comuni
            .replace(Regex("\\(official.*?\\)"), "")
            .replace(Regex("\\[official.*?\\]"), "")
            .replace(Regex("official (video|audio|music video|lyric video)"), "")
            .replace("(official)", "")
            .replace("[official]", "")
            .replace("(lyrics)", "")
            .replace("[lyrics]", "")
            .replace("(lyric video)", "")
            .replace("[lyric video]", "")
            .replace("(audio)", "")
            .replace("[audio]", "")
            .replace("(hd)", "")
            .replace("[hd]", "")
            .replace("hd", "")
            .replace("4k", "")
            .replace("8k", "")
            .replace("(visualizer)", "")
            .replace("[visualizer]", "")
            .replace("(music video)", "")
            .replace("[music video]", "")
            .replace("- topic", "")
            .replace("(topic)", "")
            // Rimuovi caratteri speciali comuni
            .replace("'", "")
            .replace("'", "")
            .replace("\"", "")
            .replace("feat.", "")
            .replace("ft.", "")
            .replace("featuring", "")
            // Rimuovi caratteri non alfanumerici
            .replace(Regex("[^a-z0-9\\s-]"), " ")
            // Normalizza spazi
            .replace(Regex("\\s+"), " ")
            .trim()

        return cleaned
    }

    private fun findBestMatch(cleanTitle: String, songs: List<Song>): Song? {
        // STRATEGIA 1: Match esatto completo (artista + titolo)
        songs.forEach { song ->
            val songKey = "${song.artist} ${song.title}".lowercase()
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            if (songKey == cleanTitle) {
                Log.d(TAG, "EXACT MATCH: $cleanTitle -> ${song.title}")
                return song
            }
        }

        // STRATEGIA 2: Match solo titolo canzone (ignora artista)
        songs.forEach { song ->
            val titleKey = song.title.lowercase()
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            if (titleKey.isNotEmpty() && (titleKey == cleanTitle || cleanTitle.contains(titleKey) || titleKey.contains(cleanTitle))) {
                Log.d(TAG, "TITLE MATCH: $cleanTitle -> ${song.title}")
                return song
            }
        }

        // STRATEGIA 3: Match parziale artista + titolo
        songs.forEach { song ->
            val songKey = "${song.artist} ${song.title}".lowercase()
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            if (songKey.isNotEmpty() && (cleanTitle.contains(songKey) || songKey.contains(cleanTitle))) {
                Log.d(TAG, "PARTIAL MATCH: $cleanTitle -> ${song.title}")
                return song
            }
        }

        // STRATEGIA 4: Fuzzy match (almeno 60% parole in comune)
        val cleanWords = cleanTitle.split(" ").filter { it.length > 2 }

        if (cleanWords.isEmpty()) return null

        var bestMatch: Song? = null
        var bestScore = 0.0

        songs.forEach { song ->
            val songWords = "${song.artist} ${song.title}".lowercase()
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .split(" ")
                .filter { it.length > 2 }

            if (songWords.isEmpty()) return@forEach

            val commonWords = cleanWords.intersect(songWords.toSet()).size
            val score = commonWords.toDouble() / maxOf(cleanWords.size, songWords.size)

            if (score > bestScore && score >= 0.6) { // Abbassato da 0.7 a 0.6
                bestScore = score
                bestMatch = song
                Log.d(TAG, "FUZZY MATCH (score: $score): $cleanTitle -> ${song.title}")
            }
        }

        return bestMatch
    }
}