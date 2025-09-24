package com.example.musiclab

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class QueueActivity : AppCompatActivity() {

    private lateinit var backButton: ImageButton
    private lateinit var queueTitle: TextView
    private lateinit var currentSongTitle: TextView
    private lateinit var queueRecyclerView: RecyclerView
    private lateinit var clearQueueButton: ImageButton
    private lateinit var emptyStateContainer: View

    private lateinit var queueAdapter: QueueAdapter
    private lateinit var musicPlayer: MusicPlayer

    private var currentQueue: MutableList<Song> = mutableListOf()
    private var currentSongIndex: Int = 0

    // Progress handler per aggiornamenti in tempo reale
    private val progressHandler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateCurrentSong()
            progressHandler.postDelayed(this, 1000)
        }
    }

    // NUOVO: Listener specifico per cambiamenti della coda
    private val queueChangeListener: () -> Unit = {
        runOnUiThread {
            Log.d("QueueActivity", "🔄 Queue changed - reloading from MusicPlayer")
            loadCurrentQueue()
        }
    }

    // Listener per cambiamenti del player
    private val playerStateListener: (Boolean, Song?) -> Unit = { isPlaying, currentSong ->
        runOnUiThread {
            updateCurrentSong()
            updateShuffleRepeatInfo()
            Log.d("QueueActivity", "Player state updated in queue")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_queue)

        Log.d("QueueActivity", "=== QUEUE ACTIVITY CREATED ===")

        musicPlayer = MusicPlayerManager.getInstance().getMusicPlayer(this)

        setupViews()
        setupRecyclerView()
        setupClickListeners()
        loadCurrentQueue()

        // NUOVO: Aggiungi entrambi i listener
        musicPlayer.addQueueChangeListener(queueChangeListener)
        musicPlayer.addStateChangeListener(playerStateListener)

        startUpdates()
    }

    private fun setupViews() {
        backButton = findViewById(R.id.btn_back_queue)
        queueTitle = findViewById(R.id.queue_title)
        currentSongTitle = findViewById(R.id.current_song_in_queue)
        queueRecyclerView = findViewById(R.id.queue_recycler_view)
        clearQueueButton = findViewById(R.id.btn_clear_queue)

        // Trova l'empty state container (se esiste nel layout)
        emptyStateContainer = findViewById<View?>(R.id.empty_queue_container) ?: View(this)

        queueTitle.text = getString(R.string.playback_queue)
    }

    private fun setupRecyclerView() {
        queueRecyclerView.layoutManager = LinearLayoutManager(this)

        queueAdapter = QueueAdapter(
            songs = currentQueue,
            currentSongIndex = currentSongIndex,
            onSongClick = { song, position -> onQueueSongClick(song, position) },
            onRemoveFromQueue = { position -> removeFromQueue(position) }
        )

        queueRecyclerView.adapter = queueAdapter

        // Setup drag & drop con callback corretto
        val itemTouchHelper = ItemTouchHelper(
            QueueItemTouchHelperCallback(queueAdapter) { fromPos, toPos ->
                Log.d("QueueActivity", "Drag completed: $fromPos -> $toPos")
                moveItemInQueueFinal(fromPos, toPos)
            }
        )
        itemTouchHelper.attachToRecyclerView(queueRecyclerView)

        Log.d("QueueActivity", "RecyclerView setup with drag & drop")
    }

    private fun setupClickListeners() {
        backButton.setOnClickListener {
            Log.d("QueueActivity", "Back button clicked")
            finish()
        }

        clearQueueButton.setOnClickListener {
            Log.d("QueueActivity", "Clear queue clicked")
            clearQueue()
        }
    }

    private fun loadCurrentQueue() {
        Log.d("QueueActivity", "=== LOADING CURRENT QUEUE ===")

        // Ottieni la coda reale dal MusicPlayer - SEMPRE aggiornata
        val realQueue = musicPlayer.getCurrentQueue()
        currentSongIndex = musicPlayer.getCurrentIndex()

        // IMPORTANTE: Sincronizza completamente
        currentQueue.clear()
        currentQueue.addAll(realQueue)

        Log.d("QueueActivity", "✅ Queue synchronized: ${currentQueue.size} songs, current index: $currentSongIndex")

        // Debug: stampa le prime 3 canzoni per verifica
        currentQueue.take(3).forEachIndexed { index, song ->
            val isCurrent = if (index == currentSongIndex) "🎵 PLAYING" else ""
            Log.d("QueueActivity", "  $index: ${song.title} $isCurrent")
        }

        // Aggiorna l'adapter con i dati sincronizzati
        queueAdapter.updateQueue(currentQueue, currentSongIndex)
        updateCurrentSong()
        updateEmptyState()
        updateShuffleRepeatInfo()

        Log.d("QueueActivity", "=== QUEUE LOAD COMPLETE ===")
    }

    private fun updateCurrentSong() {
        val currentSong = musicPlayer.getCurrentSong()
        val realIndex = musicPlayer.getCurrentIndex()

        if (currentSong != null) {
            currentSongTitle.text = "${getString(R.string.currently_playing)}: ${currentSong.title}"

            // SINCRONIZZA l'indice locale con quello reale
            if (realIndex != currentSongIndex) {
                Log.d("QueueActivity", "🔄 Index sync: local=$currentSongIndex, real=$realIndex")
                currentSongIndex = realIndex
                queueAdapter.updateCurrentIndex(currentSongIndex)
            }
        } else {
            currentSongTitle.text = getString(R.string.no_song_playing)
        }
    }

    private fun updateEmptyState() {
        if (currentQueue.isEmpty()) {
            queueRecyclerView.visibility = View.GONE
            emptyStateContainer.visibility = View.VISIBLE
            Log.d("QueueActivity", "Showing empty state")
        } else {
            queueRecyclerView.visibility = View.VISIBLE
            emptyStateContainer.visibility = View.GONE
            Log.d("QueueActivity", "Showing queue with ${currentQueue.size} songs")
        }
    }

    private fun onQueueSongClick(song: Song, position: Int) {
        Log.d("QueueActivity", "🎵 Queue song clicked: ${song.title} at position $position")

        // Salta alla canzone selezionata
        val success = musicPlayer.jumpToIndex(position)

        if (success) {
            // Non aggiornare manualmente - il listener se ne occuperà
            Log.d("QueueActivity", "✅ Jump successful - waiting for listener update")
        } else {
            Log.e("QueueActivity", "❌ Failed to jump to song")
        }
    }

    private fun removeFromQueue(position: Int) {
        Log.d("QueueActivity", "🗑️ Removing from queue at position: $position")

        if (position < 0 || position >= currentQueue.size) {
            Log.w("QueueActivity", "❌ Invalid position for removal")
            return
        }

        val removedSong = currentQueue[position]

        // Rimuovi dal MusicPlayer - il listener aggiornerà automaticamente
        val success = musicPlayer.removeFromQueue(position)

        if (success) {
            Log.d("QueueActivity", "✅ Song '${removedSong.title}' removed - listener will update UI")
        } else {
            Log.e("QueueActivity", "❌ Failed to remove song")
        }
    }

    private fun moveItemInQueueFinal(fromPosition: Int, toPosition: Int) {
        Log.d("QueueActivity", "🔄 Final move: $fromPosition -> $toPosition")

        // Aggiorna il MusicPlayer - il listener si occuperà del resto
        val success = musicPlayer.moveInQueue(fromPosition, toPosition)

        if (success) {
            Log.d("QueueActivity", "✅ Move successful - listener will update UI")
        } else {
            Log.e("QueueActivity", "❌ Move failed - reloading to restore correct state")
            // Se fallisce, il listener dovrebbe comunque aggiornare
        }
    }

    private fun clearQueue() {
        Log.d("QueueActivity", "🗑️ Clearing entire queue")

        // Svuota la coda nel MusicPlayer - il listener aggiornerà l'UI
        musicPlayer.clearQueue()

        Log.d("QueueActivity", "✅ Clear command sent - listener will update UI")
    }

    // Metodo per aggiornare le info shuffle/repeat
    private fun updateShuffleRepeatInfo() {
        val isShuffleEnabled = musicPlayer.isShuffleEnabled()
        val repeatMode = musicPlayer.getRepeatMode()

        // Aggiorna il titolo della coda per mostrare lo stato
        val baseTitle = getString(R.string.playback_queue)
        val shuffleText = if (isShuffleEnabled) " 🔀" else ""
        val repeatText = when (repeatMode) {
            androidx.media3.common.Player.REPEAT_MODE_ONE -> " 🔂"
            androidx.media3.common.Player.REPEAT_MODE_ALL -> " 🔁"
            else -> ""
        }

        queueTitle.text = "$baseTitle$shuffleText$repeatText"

        Log.d("QueueActivity", "UI updated: shuffle=$isShuffleEnabled, repeat=$repeatMode")
    }

    private fun startUpdates() {
        progressHandler.post(updateRunnable)
    }

    private fun stopUpdates() {
        progressHandler.removeCallbacks(updateRunnable)
    }

    override fun onResume() {
        super.onResume()
        Log.d("QueueActivity", "=== ON RESUME ===")
        startUpdates()
        // Il queue change listener si occuperà degli aggiornamenti automatici
    }

    override fun onPause() {
        super.onPause()
        stopUpdates()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopUpdates()

        // IMPORTANTE: Rimuovi entrambi i listener
        musicPlayer.removeQueueChangeListener(queueChangeListener)
        musicPlayer.removeStateChangeListener(playerStateListener)

        Log.d("QueueActivity", "QueueActivity destroyed, listeners removed")
    }
}