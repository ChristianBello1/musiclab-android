package com.example.musiclab

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // Core components
    private lateinit var musicScanner: MusicScanner
    private lateinit var musicPlayer: MusicPlayer
    private var songs: List<Song> = emptyList()

    // Navigation components
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var viewPagerAdapter: MainViewPagerAdapter

    // Header buttons
    private lateinit var btnSearch: ImageButton
    private lateinit var btnLogin: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnSort: ImageButton
    private lateinit var songCountText: TextView

    // Search components
    private lateinit var searchContainer: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var btnCloseSearch: ImageButton
    private lateinit var searchResultsRecycler: RecyclerView
    private lateinit var searchAdapter: SongAdapter
    private var isSearchActive = false

    // Player Bottom Bar Views
    private lateinit var playerBottomContainer: LinearLayout
    private lateinit var currentSongTitle: TextView
    private lateinit var currentSongArtist: TextView
    private lateinit var btnBottomPrevious: ImageButton
    private lateinit var btnBottomPlayPause: ImageButton
    private lateinit var btnBottomNext: ImageButton
    private lateinit var btnExpandPlayer: ImageButton

    // Progress bar views
    private lateinit var bottomSeekBar: SeekBar
    private lateinit var bottomCurrentTime: TextView
    private lateinit var bottomTotalTime: TextView

    // State management
    private var isLoggedIn = false

    // Progress handler
    private val progressHandler = Handler(Looper.getMainLooper())
    private var isUpdatingBottomProgress = false

    private val bottomProgressRunnable = object : Runnable {
        override fun run() {
            updateBottomProgress()
            progressHandler.postDelayed(this, 1000)
        }
    }

    // NUOVO: Listener per la bottom bar
    private val mainActivityPlayerListener: (Boolean, Song?) -> Unit = { isPlaying, currentSong ->
        runOnUiThread {
            updatePlayerBottomBar(isPlaying, currentSong)
            Log.d("MainActivity", "📱 Bottom bar updated: playing=$isPlaying, song=${currentSong?.title}")
        }
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            loadMusicFiles()
        } else {
            Toast.makeText(this, getString(R.string.permissions_needed), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)

        Log.d("MainActivity", "=== MAIN ACTIVITY CREATED ===")

        setupViews()
        setupViewPager()
        setupListeners()

        // Setup modern back handling
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isSearchActive) {
                    closeSearch()
                } else {
                    finish()
                }
            }
        })

        musicScanner = MusicScanner(this)
        musicPlayer = MusicPlayerManager.getInstance().getMusicPlayer(this)

        // NUOVO: Usa addStateChangeListener invece di onPlayerStateChanged
        musicPlayer.addStateChangeListener(mainActivityPlayerListener)

        checkPermissionsAndLoadMusic()

        Log.d("MainActivity", "=== MAIN ACTIVITY SETUP COMPLETE ===")
    }

    private fun setupViews() {
        Log.d("MainActivity", "Setting up views...")

        // Header components
        btnSearch = findViewById(R.id.btn_search)
        btnLogin = findViewById(R.id.btn_login)
        btnSettings = findViewById(R.id.btn_settings)
        btnSort = findViewById(R.id.btn_sort)
        songCountText = findViewById(R.id.song_count)

        // Navigation components
        tabLayout = findViewById(R.id.tab_layout)
        viewPager = findViewById(R.id.view_pager)

        // Search components
        searchContainer = findViewById(R.id.search_container)
        searchInput = findViewById(R.id.search_input)
        btnCloseSearch = findViewById(R.id.btn_close_search)
        searchResultsRecycler = findViewById(R.id.search_results_recycler)

        // Setup search RecyclerView
        searchResultsRecycler.layoutManager = LinearLayoutManager(this)
        searchAdapter = SongAdapter(emptyList()) { song ->
            onSongClick(song)
            closeSearch()
        }
        searchResultsRecycler.adapter = searchAdapter

        // Setup Player Bottom Bar
        playerBottomContainer = findViewById(R.id.player_bottom_container)
        currentSongTitle = findViewById(R.id.current_song_title)
        currentSongArtist = findViewById(R.id.current_song_artist)
        btnBottomPrevious = findViewById(R.id.btn_previous)
        btnBottomPlayPause = findViewById(R.id.btn_play_pause)
        btnBottomNext = findViewById(R.id.btn_next)
        btnExpandPlayer = findViewById(R.id.btn_expand_player)

        // Setup Progress bar views
        bottomSeekBar = findViewById(R.id.seek_bar)
        bottomCurrentTime = findViewById(R.id.current_time)
        bottomTotalTime = findViewById(R.id.total_time)

        Log.d("MainActivity", "Views setup completed")
    }

    private fun setupViewPager() {
        Log.d("MainActivity", "Setting up ViewPager...")

        // Inizializza l'adapter con lista vuota
        viewPagerAdapter = MainViewPagerAdapter(this, emptyList())
        viewPager.adapter = viewPagerAdapter

        // Connect TabLayout with ViewPager2
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_folders)
                1 -> getString(R.string.tab_playlists)
                else -> ""
            }
        }.attach()

        Log.d("MainActivity", "ViewPager setup completed")
    }

    private fun setupListeners() {
        Log.d("MainActivity", "Setting up listeners...")

        // Header buttons
        btnSearch.setOnClickListener { toggleSearch() }
        btnLogin.setOnClickListener { toggleLogin() }
        btnSettings.setOnClickListener { openSettings() }
        btnSort.setOnClickListener { showSortOptions() }

        // Search listeners
        btnCloseSearch.setOnClickListener { closeSearch() }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                performSearch(s.toString())
            }
        })

        setupPlayerBottomBarListeners()

        Log.d("MainActivity", "Listeners setup completed")
    }

    private fun setupPlayerBottomBarListeners() {
        Log.d("MainActivity", "Setting up bottom bar listeners...")

        btnBottomPrevious.setOnClickListener {
            Log.d("MainActivity", "⏮️ Bottom Previous clicked")
            musicPlayer.playPrevious()
        }

        btnBottomPlayPause.setOnClickListener {
            Log.d("MainActivity", "⏯️ Bottom Play/Pause clicked")
            musicPlayer.playPause()
        }

        btnBottomNext.setOnClickListener {
            Log.d("MainActivity", "⏭️ Bottom Next clicked")
            musicPlayer.playNext()
        }

        btnExpandPlayer.setOnClickListener {
            Log.d("MainActivity", "📱 Queue button clicked - opening QueueActivity")
            val intent = Intent(this, QueueActivity::class.java)
            startActivity(intent)
        }

        // Click su tutta la barra per aprire il player
        playerBottomContainer.setOnClickListener {
            Log.d("MainActivity", "📱 Bottom bar clicked - opening player")
            openPlayerActivity()
        }

        // Setup SeekBar
        bottomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUpdatingBottomProgress = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUpdatingBottomProgress = false
                seekBar?.let { bar ->
                    val position = bar.progress
                    musicPlayer.seekTo(position)
                }
            }

            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    bottomCurrentTime.text = formatTime(progress)
                }
            }
        })
    }

    // Search functionality
    private fun toggleSearch() {
        if (isSearchActive) {
            closeSearch()
        } else {
            openSearch()
        }
    }

    private fun openSearch() {
        isSearchActive = true
        searchContainer.visibility = View.VISIBLE
        searchInput.requestFocus()
        Log.d("MainActivity", "🔍 Search opened")
    }

    private fun closeSearch() {
        isSearchActive = false
        searchContainer.visibility = View.GONE
        searchInput.text.clear()
        searchAdapter.updateSongs(emptyList())
        Log.d("MainActivity", "🔍 Search closed")
    }

    private fun performSearch(query: String) {
        if (query.isBlank()) {
            searchAdapter.updateSongs(emptyList())
            return
        }

        val foldersFragment = viewPagerAdapter.getFoldersFragment()
        val searchResults = foldersFragment?.filterSongs(query) ?: emptyList()
        searchAdapter.updateSongs(searchResults)

        Log.d("MainActivity", "🔍 Search for '$query' returned ${searchResults.size} results")
    }

    // Login functionality
    private fun toggleLogin() {
        if (isLoggedIn) {
            logout()
        } else {
            login()
        }
    }

    private fun login() {
        isLoggedIn = true
        updateLoginButton()
        viewPagerAdapter.setLoginState(true)
        Toast.makeText(this, "Login simulato!", Toast.LENGTH_SHORT).show()
        Log.d("MainActivity", "User logged in")
    }

    private fun logout() {
        isLoggedIn = false
        updateLoginButton()
        viewPagerAdapter.setLoginState(false)
        Toast.makeText(this, "Logout effettuato", Toast.LENGTH_SHORT).show()
        Log.d("MainActivity", "User logged out")
    }

    private fun updateLoginButton() {
        val iconRes = if (isLoggedIn) {
            android.R.drawable.ic_menu_mylocation
        } else {
            android.R.drawable.ic_dialog_info
        }
        btnLogin.setImageResource(iconRes)
    }

    // Settings functionality
    private fun openSettings() {
        Toast.makeText(this, "Impostazioni (coming soon!)", Toast.LENGTH_SHORT).show()
    }

    // Sort functionality
    private fun showSortOptions() {
        Toast.makeText(this, "Opzioni ordinamento (coming soon!)", Toast.LENGTH_SHORT).show()
    }

    private fun onSongClick(song: Song) {
        Log.d("MainActivity", "🎵 Song clicked: ${song.title}")

        musicPlayer.setPlaylist(songs, songs.indexOf(song))
        musicPlayer.playSong(song)

        showPlayerBottomBar()
        startBottomProgressUpdates()

        Toast.makeText(this, getString(R.string.now_playing_format, song.title), Toast.LENGTH_SHORT).show()
        Log.d("MainActivity", "▶️ Playing: ${song.title} - ${song.artist}")
    }

    fun openPlaylist(playlist: Playlist) {
        Toast.makeText(this, "Apertura playlist: ${playlist.name}", Toast.LENGTH_SHORT).show()
        Log.d("MainActivity", "Opening playlist: ${playlist.name}")
    }

    // Player UI updates
    private fun updatePlayerBottomBar(isPlaying: Boolean, currentSong: Song?) {
        Log.d("MainActivity", "🔄 Updating bottom bar: playing=$isPlaying, song=${currentSong?.title}")

        if (currentSong != null) {
            showPlayerBottomBar()

            currentSongTitle.text = currentSong.title
            currentSongArtist.text = currentSong.artist
            bottomTotalTime.text = currentSong.getFormattedDuration()
            bottomSeekBar.max = (currentSong.duration / 1000).toInt()

            val iconRes = if (isPlaying) {
                android.R.drawable.ic_media_pause
            } else {
                android.R.drawable.ic_media_play
            }
            btnBottomPlayPause.setImageResource(iconRes)

            if (isPlaying) {
                startBottomProgressUpdates()
            }
        } else {
            hidePlayerBottomBar()
            stopBottomProgressUpdates()
        }
    }

    private fun updateBottomProgress() {
        if (!isUpdatingBottomProgress && musicPlayer.isPlaying()) {
            val currentPosition = musicPlayer.getCurrentPosition()
            val currentSeconds = (currentPosition / 1000).toInt()

            bottomSeekBar.progress = currentSeconds
            bottomCurrentTime.text = formatTime(currentSeconds)
        }
    }

    private fun formatTime(seconds: Int): String {
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, remainingSeconds)
    }

    private fun startBottomProgressUpdates() {
        stopBottomProgressUpdates()
        progressHandler.post(bottomProgressRunnable)
    }

    private fun stopBottomProgressUpdates() {
        progressHandler.removeCallbacks(bottomProgressRunnable)
    }

    private fun showPlayerBottomBar() {
        if (playerBottomContainer.visibility != View.VISIBLE) {
            playerBottomContainer.visibility = View.VISIBLE
            Log.d("MainActivity", "📱 Bottom bar shown")
        }
    }

    private fun hidePlayerBottomBar() {
        if (playerBottomContainer.visibility != View.GONE) {
            playerBottomContainer.visibility = View.GONE
            stopBottomProgressUpdates()
            Log.d("MainActivity", "📱 Bottom bar hidden")
        }
    }

    private fun openPlayerActivity() {
        val intent = Intent(this, PlayerActivity::class.java)
        startActivity(intent)
        Log.d("MainActivity", "🎵 PlayerActivity opened")
    }

    // Permissions and music loading
    private fun checkPermissionsAndLoadMusic() {
        val permissionsNeeded = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissionsNeeded.isNotEmpty()) {
            requestPermissions.launch(permissionsNeeded.toTypedArray())
        } else {
            loadMusicFiles()
        }
    }

    private fun loadMusicFiles() {
        Log.d("MainActivity", "=== INIZIO CARICAMENTO MUSICA ===")

        try {
            songs = musicScanner.scanMusicFiles()

            Log.d("MainActivity", "✅ MUSICA CARICATA: ${songs.size} canzoni")

            // Stampa le prime 5 canzoni per debug
            songs.take(5).forEachIndexed { index, song ->
                Log.d("MainActivity", "$index: ${song.title} - ${song.artist}")
            }

            // Aggiorna UI
            songCountText.text = getString(R.string.song_count_format, songs.size)
            Log.d("MainActivity", "📊 UI aggiornata - contatore: ${songs.size}")

            // IMPORTANTE: Aspetta che il ViewPager sia pronto, poi aggiorna
            viewPager.post {
                Log.d("MainActivity", "🔄 Aggiornamento ViewPager...")

                // Aggiorna l'adapter con le nuove canzoni
                viewPagerAdapter = MainViewPagerAdapter(this@MainActivity, songs)
                viewPager.adapter = viewPagerAdapter

                // Riattacca le tab
                TabLayoutMediator(tabLayout, viewPager) { tab, position ->
                    tab.text = when (position) {
                        0 -> getString(R.string.tab_folders)
                        1 -> getString(R.string.tab_playlists)
                        else -> ""
                    }
                }.attach()

                Log.d("MainActivity", "📱 ViewPager completamente aggiornato!")
            }

            Toast.makeText(this, getString(R.string.songs_loaded_format, songs.size), Toast.LENGTH_SHORT).show()
            Log.d("MainActivity", "🎉 Toast mostrato")

        } catch (e: Exception) {
            Log.e("MainActivity", "💥 ERRORE caricamento musica: $e")
            e.printStackTrace()
            Toast.makeText(this, getString(R.string.error_loading_music, e.message), Toast.LENGTH_LONG).show()
        }

        Log.d("MainActivity", "=== FINE CARICAMENTO MUSICA ===")
    }

    override fun onResume() {
        super.onResume()
        Log.d("MainActivity", "=== ON RESUME ===")

        // Aggiorna la UI del player quando si torna alla MainActivity
        val currentSong = musicPlayer.getCurrentSong()
        val isPlaying = musicPlayer.isPlaying()
        updatePlayerBottomBar(isPlaying, currentSong)
    }

    override fun onPause() {
        super.onPause()
        stopBottomProgressUpdates()
        Log.d("MainActivity", "MainActivity paused")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBottomProgressUpdates()

        // NUOVO: Rimuovi il listener per evitare memory leak
        musicPlayer.removeStateChangeListener(mainActivityPlayerListener)

        Log.d("MainActivity", "MainActivity destroyed, listener removed")
    }
}