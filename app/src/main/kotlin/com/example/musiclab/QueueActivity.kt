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

    // UNICO listener per cambiamenti del player - versione aggiornata
    private val queuePlayerListener: (Boolean, Song?) -> Unit = { isPlaying, currentSong ->
        runOnUiThread {
            updateCurrentSong()

            // Ricarica la coda quando cambia lo stato shuffle
            // perché l'ordine potrebbe essere cambiato
            val currentQueueFromPlayer = musicPlayer.getCurrentQueue()
            if (currentQueueFromPlayer.size != currentQueue.size ||
                currentQueueFromPlayer != currentQueue) {
                Log.d("QueueActivity", "Queue order changed - reloading")
                loadCurrentQueue()
            } else {
                queueAdapter.updateCurrentSong(currentSong)
            }

            // Aggiorna anche info shuffle/repeat nella UI
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

        // Aggiungi listener per aggiornamenti del player
        musicPlayer.addStateChangeListener(queuePlayerListener)

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
        Log.d("QueueActivity", "Loading current queue from MusicPlayer...")

        // Ottieni la coda reale dal MusicPlayer
        val realQueue = musicPlayer.getCurrentQueue()
        currentSongIndex = musicPlayer.getCurrentIndex()

        currentQueue.clear()
        currentQueue.addAll(realQueue)

        Log.d("QueueActivity", "Real queue loaded: ${currentQueue.size} songs, current index: $currentSongIndex")

        // Debug: stampa le prime 3 canzoni
        currentQueue.take(3).forEachIndexed { index, song ->
            Log.d("QueueActivity", "  $index: ${song.title} - ${song.artist}")
        }

        queueAdapter.updateQueue(currentQueue, currentSongIndex)
        updateCurrentSong()
        updateEmptyState()

        // Aggiorna anche le info shuffle/repeat
        updateShuffleRepeatInfo()
    }

    private fun updateCurrentSong() {
        val currentSong = musicPlayer.getCurrentSong()
        if (currentSong != null) {
            currentSongTitle.text = "${getString(R.string.currently_playing)}: ${currentSong.title}"
            currentSongIndex = musicPlayer.getCurrentIndex()
            queueAdapter.updateCurrentIndex(currentSongIndex)
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
        Log.d("QueueActivity", "Queue song clicked: ${song.title} at position $position")

        // Salta alla canzone selezionata usando il nuovo metodo
        val success = musicPlayer.jumpToIndex(position)

        if (success) {
            currentSongIndex = position
            queueAdapter.updateCurrentIndex(currentSongIndex)
            updateCurrentSong()
            Log.d("QueueActivity", "Jumped to song successfully")
        } else {
            Log.e("QueueActivity", "Failed to jump to song")
        }
    }

    private fun removeFromQueue(position: Int) {
        Log.d("QueueActivity", "Removing from queue at position: $position")

        if (position < 0 || position >= currentQueue.size) {
            Log.w("QueueActivity", "Invalid position for removal")
            return
        }

        val removedSong = currentQueue[position]

        // Rimuovi dal MusicPlayer (che aggiorna anche gli indici)
        val success = musicPlayer.removeFromQueue(position)

        if (success) {
            // Ricarica la coda dal MusicPlayer per essere sicuri
            loadCurrentQueue()

            Log.d("QueueActivity", "Song '${removedSong.title}' removed successfully")
        } else {
            Log.e("QueueActivity", "Failed to remove song")
        }
    }

    private fun moveItemInQueueFinal(fromPosition: Int, toPosition: Int) {
        Log.d("QueueActivity", "Final move: $fromPosition -> $toPosition")

        // Aggiorna il MusicPlayer con la posizione finale
        val success = musicPlayer.moveInQueue(fromPosition, toPosition)

        if (success) {
            // Sincronizza l'indice corrente
            currentSongIndex = musicPlayer.getCurrentIndex()
            queueAdapter.updateCurrentIndex(currentSongIndex)

            Log.d("QueueActivity", "Final move successful. New current index: $currentSongIndex")
        } else {
            Log.e("QueueActivity", "Final move failed, reloading queue")
            // Se fallisce, ricarica la coda per ripristinare lo stato corretto
            loadCurrentQueue()
        }
    }

    private fun clearQueue() {
        Log.d("QueueActivity", "Clearing entire queue")

        // Svuota la coda nel MusicPlayer
        musicPlayer.clearQueue()

        // Ricarica la coda (che ora dovrebbe contenere solo la canzone corrente)
        loadCurrentQueue()

        Log.d("QueueActivity", "Queue cleared")
    }

    // Metodo per aggiornare le info shuffle/repeat
    private fun updateShuffleRepeatInfo() {
        val isShuffleEnabled = musicPlayer.isShuffleEnabled()
        val repeatMode = musicPlayer.getRepeatMode()

        // Aggiorna il titolo della coda per mostrare lo stato
        val baseTitle = getString(R.string.playback_queue)
        val shuffleText = if (isShuffleEnabled) " (Shuffle)" else ""
        val repeatText = when (repeatMode) {
            androidx.media3.common.Player.REPEAT_MODE_ONE -> " (Ripeti Una)"
            androidx.media3.common.Player.REPEAT_MODE_ALL -> " (Ripeti Tutto)"
            else -> ""
        }

        queueTitle.text = "$baseTitle$shuffleText$repeatText"
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
        loadCurrentQueue() // Ricarica la coda in caso di cambiamenti
    }

    override fun onPause() {
        super.onPause()
        stopUpdates()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopUpdates()
        musicPlayer.removeStateChangeListener(queuePlayerListener)
        Log.d("QueueActivity", "QueueActivity destroyed, listener removed")
    }
}