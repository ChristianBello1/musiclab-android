package com.example.musiclab

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.text.Normalizer

class YouTubeImporter(private val context: Context) {

    companion object {
        private const val TAG = "YouTubeImporter"
        private const val YOUTUBE_API_KEY = "AIzaSyBrthCFqS1X4TQucREqXKouxHaBUG4rimg"
        private const val YOUTUBE_API_BASE = "https://www.googleapis.com/youtube/v3"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class ImportResult(
        val playlistTitle: String,
        val totalVideos: Int,
        val matchedSongs: List<Song>,
        val unmatchedTitles: List<String>
    )

    data class ProgressUpdate(
        val phase: String,
        val current: Int,
        val total: Int,
        val matchedCount: Int
    )

    suspend fun importPlaylist(
        playlistUrl: String,
        localSongs: List<Song>,
        onProgress: ((ProgressUpdate) -> Unit)? = null
    ): ImportResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== IMPORT START ===")
        Log.d(TAG, "URL: $playlistUrl")
        Log.d(TAG, "Local songs available: ${localSongs.size}")

        val playlistId = extractPlaylistId(playlistUrl)
            ?: throw IllegalArgumentException("URL playlist non valido")

        Log.d(TAG, "Playlist ID: $playlistId")

        onProgress?.invoke(ProgressUpdate("loading", 0, 0, 0))

        val playlistInfo = fetchPlaylistInfo(playlistId) { current, total ->
            onProgress?.invoke(ProgressUpdate("loading", current, total, 0))
        }

        val playlistTitle = playlistInfo.first
        val videoTitles = playlistInfo.second

        Log.d(TAG, "Playlist: $playlistTitle")
        Log.d(TAG, "Video trovati: ${videoTitles.size}")

        val preprocessedSongs = localSongs.map { song ->
            PreprocessedSong(
                song = song,
                cleanTitle = cleanText(song.title),
                cleanArtist = cleanText(song.artist),
                cleanFileName = cleanFileName(song.path),
                titleWords = extractKeywords(song.title),
                artistWords = extractKeywords(song.artist),
                fileWords = extractKeywords(song.path.substringAfterLast("/"))
            )
        }

        Log.d(TAG, "=== SAMPLE PREPROCESSED FILES ===")
        preprocessedSongs.take(5).forEach { prep ->
            Log.d(TAG, "File: ${prep.song.path.substringAfterLast("/")}")
            Log.d(TAG, "  Title: '${prep.song.title}' → '${prep.cleanTitle}'")
            Log.d(TAG, "  Artist: '${prep.song.artist}' → '${prep.cleanArtist}'")
            Log.d(TAG, "  Keywords: ${prep.titleWords.union(prep.artistWords).take(8).joinToString()}")
        }

        val matchedSet = mutableSetOf<Long>()
        val matched = mutableListOf<Song>()
        val unmatched = mutableListOf<String>()
        val totalVideos = videoTitles.size

        videoTitles.forEachIndexed { index, videoTitle ->
            val matchedSong = findBestMatch(videoTitle, preprocessedSongs)

            if (matchedSong != null) {
                if (!matchedSet.contains(matchedSong.id)) {
                    matched.add(matchedSong)
                    matchedSet.add(matchedSong.id)

                    if (matched.size <= 10) {
                        Log.d(TAG, "✅ Match #${matched.size}: '$videoTitle' → ${matchedSong.title}")
                    }
                } else {
                    unmatched.add("$videoTitle (duplicato)")
                }
            } else {
                unmatched.add(videoTitle)

                if (unmatched.size <= 10) {
                    Log.d(TAG, "❌ NOT FOUND: '$videoTitle'")
                    showTopCandidates(videoTitle, preprocessedSongs)
                }
            }

            onProgress?.invoke(ProgressUpdate(
                phase = "matching",
                current = index + 1,
                total = totalVideos,
                matchedCount = matched.size
            ))
        }

        Log.d(TAG, "=== IMPORT COMPLETE ===")
        Log.d(TAG, "Matched: ${matched.size}/${videoTitles.size} (${matched.size * 100 / videoTitles.size}%)")
        Log.d(TAG, "Unmatched: ${unmatched.size}")

        ImportResult(
            playlistTitle = playlistTitle,
            totalVideos = videoTitles.size,
            matchedSongs = matched,
            unmatchedTitles = unmatched
        )
    }

    private data class PreprocessedSong(
        val song: Song,
        val cleanTitle: String,
        val cleanArtist: String,
        val cleanFileName: String,
        val titleWords: Set<String>,
        val artistWords: Set<String>,
        val fileWords: Set<String>
    )

    private fun extractPlaylistId(url: String): String? {
        val patterns = listOf(
            "list=([^&]+)",
            "playlist\\?list=([^&]+)"
        )

        patterns.forEach { pattern ->
            val regex = pattern.toRegex()
            val match = regex.find(url)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }

    private fun fetchPlaylistInfo(
        playlistId: String,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): Pair<String, List<String>> {
        val videoTitles = mutableListOf<String>()
        var playlistTitle = "Imported Playlist"
        var nextPageToken: String? = null
        var estimatedTotal = 0

        do {
            val url = buildPlaylistItemsUrl(playlistId, nextPageToken)
            val request = Request.Builder().url(url).build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                throw Exception("YouTube API error: ${response.code}")
            }

            val jsonResponse = JSONObject(response.body?.string() ?: "{}")

            if (playlistTitle == "Imported Playlist") {
                try {
                    val pageInfo = jsonResponse.getJSONObject("pageInfo")
                    estimatedTotal = pageInfo.optInt("totalResults", 0)

                    val snippet = jsonResponse.getJSONArray("items")
                        .getJSONObject(0)
                        .getJSONObject("snippet")
                    playlistTitle = snippet.optString("playlistTitle", "Imported Playlist")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not get playlist info: ${e.message}")
                }
            }

            val items = jsonResponse.getJSONArray("items")
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val snippet = item.getJSONObject("snippet")
                val title = snippet.getString("title")

                if (title != "Private video" && title != "Deleted video") {
                    videoTitles.add(title)
                }
            }

            onProgress?.invoke(videoTitles.size, estimatedTotal)

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

    private fun cleanText(text: String): String {
        return Normalizer
            .normalize(text, Normalizer.Form.NFD)
            .replace("\\p{M}".toRegex(), "")
            .lowercase()
            .replace("&", "and")
            .replace("+", "and")
            .replace("[''`\"''«»]".toRegex(), "")
            .replace("[^a-z0-9\\s]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun cleanVideoTitle(title: String): String {
        var cleaned = title.lowercase()

        val patternsToRemove = listOf(
            "\\(official[^)]*\\)",
            "\\[official[^\\]]*\\]",
            "\\(lyric[^)]*\\)",
            "\\[lyric[^\\]]*\\]",
            "\\(audio[^)]*\\)",
            "\\[audio[^\\]]*\\]",
            "\\(music video[^)]*\\)",
            "\\[music video[^\\]]*\\]",
            "\\(visualizer[^)]*\\)",
            "\\[visualizer[^\\]]*\\]",
            "\\(color coded[^)]*\\)",
            "\\[color coded[^\\]]*\\]",
            "\\(lyrics[^)]*\\)",
            "\\[lyrics[^\\]]*\\]",
            "\\(live[^)]*\\)",
            "\\[live[^\\]]*\\]",
            "\\(mv[^)]*\\)",
            "\\[mv[^\\]]*\\]",
            "\\(hd[^)]*\\)",
            "\\[hd[^\\]]*\\]",
            "\\(4k[^)]*\\)",
            "\\[4k[^\\]]*\\]",
            "\\(remix[^)]*\\)",
            "\\[remix[^\\]]*\\]",
            "\\(cover[^)]*\\)",
            "\\[cover[^\\]]*\\]",
            "\\(feat[^)]*\\)",
            "\\[feat[^\\]]*\\]",
            "\\(ft[^)]*\\)",
            "\\[ft[^\\]]*\\]",
            "\\(han/rom/eng[^)]*\\)",
            "\\[han/rom/eng[^\\]]*\\]",
            "\\(eng/rom/han[^)]*\\)",
            "\\[eng/rom/han[^\\]]*\\]",
            "\\(color coded_han_rom_eng\\)",
            "\\|[^|]*official[^|]*",
            "\\|[^|]*lyric[^|]*",
            "\\|[^|]*audio[^|]*"
        )

        patternsToRemove.forEach { pattern ->
            try {
                cleaned = cleaned.replace(pattern.toRegex(), " ")
            } catch (e: Exception) {
                Log.w(TAG, "Pattern error: $pattern - ${e.message}")
            }
        }

        cleaned = cleaned
            .replace(" | ", " ")
            .replace(" / ", " ")

        val wordsToRemove = listOf(
            "official", "video", "audio", "lyrics", "lyric",
            "music video", "lyric video", "official video", "official audio",
            "visualizer", "mv", "hd", "hq", "4k", "8k", "ultra",
            "topic", "vevo", "color coded",
            "feat", "feat.", "ft", "ft.", "featuring", "with",
            "prod", "prod.", "produced by",
            "remix", "vip", "edit", "version",
            "han", "rom", "eng", "kor", "jpn",
            "han/rom/eng", "eng/rom/han",
            "cover", "live", "performance"
        )

        wordsToRemove.forEach { word ->
            cleaned = cleaned.replace(" $word ", " ")
        }

        return cleanText(cleaned)
    }

    private fun cleanFileName(path: String): String {
        return path.substringAfterLast("/")
            .substringBeforeLast(".")
            .let { cleanText(it) }
    }

    private fun extractKeywords(text: String): Set<String> {
        val words = cleanText(text)
            .split(" ")
            .filter { it.length >= 3 }

        val keywords = words.toSet()

        val bigrams = mutableSetOf<String>()
        for (i in 0 until words.size - 1) {
            bigrams.add("${words[i]}_${words[i + 1]}")
        }

        return keywords + bigrams
    }

    private fun calculateSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        val longer = if (s1.length > s2.length) s1 else s2
        val shorter = if (s1.length > s2.length) s2 else s1

        if (longer.length == 0) return 1.0

        if (longer.contains(shorter)) {
            return shorter.length.toDouble() / longer.length
        }

        val s1Chars = s1.toSet()
        val s2Chars = s2.toSet()
        val common = s1Chars.intersect(s2Chars).size
        val union = s1Chars.union(s2Chars).size

        return common.toDouble() / union
    }

    private fun findBestMatch(videoTitle: String, songs: List<PreprocessedSong>): Song? {
        val cleanVideo = cleanVideoTitle(videoTitle)
        val videoWords = extractKeywords(cleanVideo)

        if (videoWords.isEmpty()) return null

        Log.d(TAG, "Matching: '$videoTitle' → keywords: ${videoWords.filter { !it.contains("_") }.take(10).joinToString()}")

        songs.forEach { prep ->
            if (prep.cleanFileName.isNotEmpty() && prep.cleanFileName.length >= 10) {
                val similarity = calculateSimilarity(prep.cleanFileName, cleanVideo)
                if (similarity >= 0.95) {
                    Log.d(TAG, "  ✅ EXACT FILENAME (similarity: ${"%.2f".format(similarity)})")
                    return prep.song
                }
            }
        }

        songs.forEach { prep ->
            if (prep.cleanFileName.length >= 15 && cleanVideo.length >= 15) {
                if (prep.cleanFileName.contains(cleanVideo) || cleanVideo.contains(prep.cleanFileName)) {
                    Log.d(TAG, "  ✅ SUBSTRING FILENAME")
                    return prep.song
                }
            }
        }

        var bestMatch: PreprocessedSong? = null
        var bestScore = 0.0
        var bestSource = ""

        songs.forEach { prep ->
            val fileScore = calculateMatchScore(videoWords, prep.fileWords, "FILE")
            val metadataScore = calculateMatchScore(
                videoWords,
                prep.titleWords.union(prep.artistWords),
                "METADATA"
            )

            val weightedScore = (fileScore * 0.75) + (metadataScore * 0.25)
            val source = if (fileScore > metadataScore) "FILE" else "METADATA"

            if (weightedScore > bestScore) {
                bestScore = weightedScore
                bestMatch = prep
                bestSource = source
            }
        }

        if (bestMatch != null && bestScore >= 0.28) {
            val commonWords = videoWords.intersect(
                bestMatch.fileWords.union(bestMatch.titleWords).union(bestMatch.artistWords)
            ).filter { !it.contains("_") }

            if (commonWords.size >= 2 || bestScore >= 0.45) {
                Log.d(TAG, "  🔥 FUZZY $bestSource (score: ${"%.2f".format(bestScore)}, words: ${commonWords.size})")
                return bestMatch.song
            }
        }

        return null
    }

    private fun calculateMatchScore(queryWords: Set<String>, targetWords: Set<String>, source: String): Double {
        if (targetWords.isEmpty()) return 0.0

        val commonWords = queryWords.intersect(targetWords)
        val commonCount = commonWords.size

        if (commonCount == 0) return 0.0

        val jaccard = commonCount.toDouble() / (queryWords.size + targetWords.size - commonCount)
        val recall = commonCount.toDouble() / queryWords.size
        val precision = commonCount.toDouble() / targetWords.size

        return (recall * 0.50) + (jaccard * 0.30) + (precision * 0.20)
    }

    private fun showTopCandidates(videoTitle: String, songs: List<PreprocessedSong>) {
        val cleanVideo = cleanVideoTitle(videoTitle)
        val videoWords = extractKeywords(cleanVideo).filter { !it.contains("_") }

        if (videoWords.isEmpty()) {
            Log.d(TAG, "  No keywords extracted from: '$videoTitle'")
            return
        }

        data class Candidate(
            val song: PreprocessedSong,
            val score: Double,
            val commonWords: Set<String>,
            val source: String
        )
        val candidates = mutableListOf<Candidate>()

        songs.forEach { prep ->
            val fileCommon = videoWords.intersect(prep.fileWords.filter { !it.contains("_") })
            if (fileCommon.isNotEmpty()) {
                val fileScore = calculateMatchScore(videoWords.toSet(), prep.fileWords, "FILE")
                candidates.add(Candidate(prep, fileScore, fileCommon, "FILE"))
            }

            val metaWords = prep.titleWords.union(prep.artistWords).filter { !it.contains("_") }
            val metaCommon = videoWords.intersect(metaWords.toSet())
            if (metaCommon.isNotEmpty()) {
                val metaScore = calculateMatchScore(videoWords.toSet(), metaWords.toSet(), "META")
                candidates.add(Candidate(prep, metaScore, metaCommon, "META"))
            }
        }

        val top5 = candidates
            .sortedByDescending { it.score }
            .distinctBy { it.song.song.id }
            .take(5)

        if (top5.isNotEmpty()) {
            Log.d(TAG, "  YT keywords: ${videoWords.take(10).joinToString(", ")}")
            Log.d(TAG, "  Top 5 candidates:")
            top5.forEachIndexed { idx, c ->
                val fileName = c.song.song.path.substringAfterLast("/")
                Log.d(TAG, "    ${idx + 1}. [${c.source}] score=${"%.2f".format(c.score)} | match=[${c.commonWords.take(5).joinToString(", ")}]")
                Log.d(TAG, "        File: $fileName")
            }
        } else {
            Log.d(TAG, "  ⚠️ NO candidates with common words!")
            Log.d(TAG, "  Sample local files (first 3):")
            songs.take(3).forEach { prep ->
                val fileName = prep.song.path.substringAfterLast("/")
                Log.d(TAG, "    - $fileName")
                Log.d(TAG, "      file_kw: ${prep.fileWords.filter { !it.contains("_") }.take(5).joinToString(", ")}")
            }
        }
    }
}