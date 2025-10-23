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

        // Registra SOLO il listener di stato
        musicPlayer.addStateChangeListener(playerStateListener)

        startUpdates()
    }

    private fun setupViews() {
        backButton = findViewById(R.id.btn_back_queue)
        queueTitle = findViewById(R.id.queue_title)
        currentSongTitle = findViewById(R.id.current_song_in_queue)
        queueRecyclerView = findViewById(R.id.queue_recycler_view)
        clearQueueButton = findViewById(R.id.btn_clear_queue)

        emptyStateContainer = findViewById(R.id.empty_queue_container) ?: View(this)

        queueTitle.text = getString(R.string.playback_queue)
    }

    private fun setupRecyclerView() {
        queueRecyclerView.layoutManager = LinearLayoutManager(this)

        // IMPORTANTE: Passa la stessa lista (currentQueue)
        queueAdapter = QueueAdapter(
            songs = currentQueue,
            currentSongIndex = currentSongIndex,
            onSongClick = { song, position -> onQueueSongClick(song, position) },
            onRemoveFromQueue = { position -> removeFromQueue(position) }
        )

        queueRecyclerView.adapter = queueAdapter

        // Setup drag & drop
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

        // ✅ NUOVO: Click su "In riproduzione: ..." per scrollare alla canzone corrente
        currentSongTitle.setOnClickListener {
            scrollToCurrentSong()
        }
    }

    private fun scrollToCurrentSong() {
        if (currentSongIndex >= 0 && currentSongIndex < currentQueue.size) {
            // Scrolla alla posizione corrente con animazione smooth
            queueRecyclerView.smoothScrollToPosition(currentSongIndex)

            Log.d("QueueActivity", "📍 Scrolled to current song at index: $currentSongIndex")

            // Opzionale: Fai un piccolo "pulse" sulla canzone per evidenziarla
            queueRecyclerView.postDelayed({
                val viewHolder = queueRecyclerView.findViewHolderForAdapterPosition(currentSongIndex)
                viewHolder?.itemView?.let { view ->
                    // Animazione di "pulse" per evidenziare
                    view.animate()
                        .scaleX(1.05f)
                        .scaleY(1.05f)
                        .setDuration(150)
                        .withEndAction {
                            view.animate()
                                .scaleX(1.0f)
                                .scaleY(1.0f)
                                .setDuration(150)
                                .start()
                        }
                        .start()
                }
            }, 300) // Aspetta che lo scroll sia completato
        }
    }

    private fun loadCurrentQueue() {
        Log.d("QueueActivity", "=== LOADING CURRENT QUEUE ===")

        // Ottieni la coda reale dal MusicPlayer
        val realQueue = musicPlayer.getCurrentQueue()
        currentSongIndex = musicPlayer.getCurrentIndex()

        // Sincronizza completamente
        currentQueue.clear()
        currentQueue.addAll(realQueue)

        Log.d("QueueActivity", "✅ Queue synchronized: ${currentQueue.size} songs, current index: $currentSongIndex")

        // Debug: stampa le prime 3 canzoni
        currentQueue.take(3).forEachIndexed { index, song ->
            val isCurrent = if (index == currentSongIndex) "🎵 PLAYING" else ""
            Log.d("QueueActivity", "  $index: ${song.title} $isCurrent")
        }

        // Aggiorna l'adapter
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

            // Sincronizza l'indice
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

        val success = musicPlayer.jumpToIndex(position)

        if (success) {
            Log.d("QueueActivity", "✅ Jump successful")
        } else {
            Log.e("QueueActivity", "❌ Failed to jump to song")
        }
    }

    private fun removeFromQueue(position: Int) {
        Log.d("QueueActivity", "🗑️ SWIPE REMOVE at position: $position")
        Log.d("QueueActivity", "📊 BEFORE - size: ${currentQueue.size}, index: $currentSongIndex")

        if (position < 0 || position >= currentQueue.size) {
            Log.w("QueueActivity", "❌ Invalid position")
            return
        }

        val removedSong = currentQueue[position]
        Log.d("QueueActivity", "🎵 Removing: ${removedSong.title}")

        // Rimuovi dal MusicPlayer PRIMA
        val success = musicPlayer.removeFromQueue(position)

        if (!success) {
            Log.e("QueueActivity", "❌ MusicPlayer failed, reloading")
            loadCurrentQueue()
            return
        }

        Log.d("QueueActivity", "✅ MusicPlayer confirmed removal")

        // Rimuovi dalla lista locale
        currentQueue.removeAt(position)

        // Aggiorna indice locale
        val oldIndex = currentSongIndex
        when {
            position < currentSongIndex -> {
                currentSongIndex--
            }
            position == currentSongIndex -> {
                if (currentSongIndex >= currentQueue.size) {
                    currentSongIndex = maxOf(0, currentQueue.size - 1)
                }
            }
        }

        Log.d("QueueActivity", "📊 AFTER - size: ${currentQueue.size}, index: $oldIndex → $currentSongIndex")

        // Notifica l'adapter della rimozione
        queueAdapter.notifyItemRemoved(position)

        // Aggiorna gli item vicini per il testo "Up Next #X"
        if (position > 0) {
            queueAdapter.notifyItemChanged(position - 1)
        }
        if (position < currentQueue.size) {
            queueAdapter.notifyItemChanged(position)
        }

        // Aggiorna l'indice corrente se è cambiato
        if (oldIndex != currentSongIndex) {
            queueAdapter.updateCurrentIndex(currentSongIndex)
        }

        updateCurrentSong()
        updateEmptyState()

        Log.d("QueueActivity", "✅ UI updated successfully")
    }

    private fun moveItemInQueueFinal(fromPosition: Int, toPosition: Int) {
        Log.d("QueueActivity", "🔄 Final move: $fromPosition -> $toPosition")

        val success = musicPlayer.moveInQueue(fromPosition, toPosition)

        if (success) {
            Log.d("QueueActivity", "✅ Move successful")
        } else {
            Log.e("QueueActivity", "❌ Move failed - reloading")
            loadCurrentQueue()
        }
    }

    private fun clearQueue() {
        Log.d("QueueActivity", "🗑️ Clearing entire queue")
        musicPlayer.clearQueue()
        loadCurrentQueue()
    }

    private fun updateShuffleRepeatInfo() {
        val isShuffleEnabled = musicPlayer.isShuffleEnabled()
        val repeatMode = musicPlayer.getRepeatMode()

        queueTitle.text = buildString {
            append(getString(R.string.playback_queue))
            if (isShuffleEnabled) append(" 🔀")
            when (repeatMode) {
                androidx.media3.common.Player.REPEAT_MODE_ONE -> append(" 🔂")
                androidx.media3.common.Player.REPEAT_MODE_ALL -> append(" 🔁")
            }
        }

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
    }

    override fun onPause() {
        super.onPause()
        stopUpdates()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopUpdates()

        // Rimuovi listener
        musicPlayer.removeStateChangeListener(playerStateListener)

        Log.d("QueueActivity", "QueueActivity destroyed")
    }
}