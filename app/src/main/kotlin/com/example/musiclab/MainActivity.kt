package com.example.musiclab

import androidx.core.app.ActivityCompat
import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity() {

    enum class SortType {
        TITLE_ASC,
        ARTIST_ASC,
        ALBUM_ASC,
        DURATION_ASC,
        DATE_ADDED_DESC
    }

    // Google Auth
    private lateinit var googleAuthManager: GoogleAuthManager
    private var isLoggedIn = false

    private lateinit var batteryOptimizer: BatteryOptimizer

    // Core components
    private lateinit var musicScanner: MusicScanner
    private lateinit var musicPlayer: MusicPlayer
    private var songs: List<Song> = emptyList()

    // ✅ NUOVO: Persistenza stato
    private lateinit var playbackStateManager: PlaybackStateManager

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
    private lateinit var bottomSeekBar: SeekBar
    private lateinit var bottomCurrentTime: TextView
    private lateinit var bottomTotalTime: TextView

    private var currentSortType = SortType.TITLE_ASC

    // Launcher per Google Sign-In
    @Suppress("DEPRECATION")
    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val success = googleAuthManager.handleSignInResult(result.data)
        if (success) {
            val user = googleAuthManager.getCurrentUser()
            val userId = googleAuthManager.getUserId() ?: ""

            isLoggedIn = true
            saveLoginState(true, userId)
            updateLoginButton()
            updatePlaylistsFragmentLoginState()

            Toast.makeText(this, "Benvenuto ${user?.displayName}!", Toast.LENGTH_SHORT).show()
            Log.d("MainActivity", "Login successful: ${user?.email}, userId=$userId")
        } else {
            Toast.makeText(this, "Login fallito", Toast.LENGTH_SHORT).show()
            Log.e("MainActivity", "Login failed")
        }
    }

    private val progressHandler = Handler(Looper.getMainLooper())
    private var isUpdatingBottomProgress = false

    private val bottomProgressRunnable = object : Runnable {
        override fun run() {
            updateBottomProgress()
            progressHandler.postDelayed(this, 1000)
        }
    }

    // ✅ NUOVO: Timer per salvare periodicamente lo stato
    private val stateSaveHandler = Handler(Looper.getMainLooper())
    private val stateSaveRunnable = object : Runnable {
        override fun run() {
            saveCurrentPlaybackState()
            stateSaveHandler.postDelayed(this, 5000) // Salva ogni 5 secondi
        }
    }

    private val mainQueueChangeListener: () -> Unit = {
        runOnUiThread {
            Log.d("MainActivity", "🔄 Queue changed in MainActivity")
            val currentSong = musicPlayer.getCurrentSong()
            val isPlaying = musicPlayer.isPlaying()
            updatePlayerBottomBar(isPlaying, currentSong)
        }
    }

    private val mainActivityPlayerListener: (Boolean, Song?) -> Unit = { isPlaying, currentSong ->
        runOnUiThread {
            updatePlayerBottomBar(isPlaying, currentSong)
            Log.d("MainActivity", "📱 Bottom bar updated: playing=$isPlaying")
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

        initializeGoogleAuth()
        checkSavedLoginState()

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
        batteryOptimizer = BatteryOptimizer.getInstance(this)
        Log.d("MainActivity", "✅ BatteryOptimizer initialized")

        // ✅ NUOVO: Inizializza PlaybackStateManager
        playbackStateManager = PlaybackStateManager(this)

        musicPlayer.addStateChangeListener(mainActivityPlayerListener)
        musicPlayer.addQueueChangeListener(mainQueueChangeListener)

        checkPermissionsAndLoadMusic()
        checkNotificationPermission()

        Log.d("MainActivity", "=== MAIN ACTIVITY SETUP COMPLETE ===")

        batteryOptimizer = BatteryOptimizer.getInstance(this)
    }

    private fun initializeGoogleAuth() {
        googleAuthManager = GoogleAuthManager.getInstance()
        googleAuthManager.initialize(this)

        googleAuthManager.addAuthStateListener { loggedIn, user ->
            runOnUiThread {
                isLoggedIn = loggedIn
                updateLoginButton()

                val userId = googleAuthManager.getUserId() ?: ""
                updatePlaylistsFragmentLoginState()

                if (loggedIn) {
                    Log.d("MainActivity", "Auth state changed: Logged in as ${user?.email}")
                } else {
                    Log.d("MainActivity", "Auth state changed: Logged out")
                }
            }
        }

        Log.d("MainActivity", "GoogleAuthManager initialized")
    }

    private fun checkSavedLoginState() {
        val prefs = getSharedPreferences("MusicLabPrefs", Context.MODE_PRIVATE)
        isLoggedIn = prefs.getBoolean("is_logged_in", false)
        val userId = prefs.getString("user_id", "") ?: ""

        updateLoginButton()
        updatePlaylistsFragmentLoginState()

        Log.d("MainActivity", "Loaded saved login state: logged=$isLoggedIn, userId=$userId")
    }

    private fun saveLoginState(isLoggedIn: Boolean, userId: String) {
        val prefs = getSharedPreferences("MusicLabPrefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("is_logged_in", isLoggedIn)
            .putString("user_id", userId)
            .apply()

        Log.d("MainActivity", "Saved login state: logged=$isLoggedIn, userId=$userId")
    }

    private fun updateLoginButton() {
        if (isLoggedIn) {
            btnLogin.setImageResource(R.drawable.ic_logout)
            Log.d("MainActivity", "Login button updated: showing logout icon")
        } else {
            btnLogin.setImageResource(R.drawable.ic_login)
            Log.d("MainActivity", "Login button updated: showing login icon")
        }
    }

    private fun updatePlaylistsFragmentLoginState() {
        val userId = googleAuthManager.getUserId() ?: ""
        viewPagerAdapter.getPlaylistsFragment()?.setLoginState(isLoggedIn, userId)

        Log.d("MainActivity", "Updated playlists fragment: logged=$isLoggedIn, userId=$userId")
    }

    private fun setupViews() {
        Log.d("MainActivity", "Setting up views...")

        btnSearch = findViewById(R.id.btn_search)
        btnLogin = findViewById(R.id.btn_login)
        btnSettings = findViewById(R.id.btn_settings)
        btnSort = findViewById(R.id.btn_sort)
        songCountText = findViewById(R.id.song_count)

        tabLayout = findViewById(R.id.tab_layout)
        viewPager = findViewById(R.id.view_pager)

        searchContainer = findViewById(R.id.search_container)
        searchInput = findViewById(R.id.search_input)
        btnCloseSearch = findViewById(R.id.btn_close_search)
        searchResultsRecycler = findViewById(R.id.search_results_recycler)

        searchResultsRecycler.layoutManager = LinearLayoutManager(this)

        // ✅ FIX CRASH RICERCA: Handler con delay per evitare race condition
        searchAdapter = SongAdapter(
            songs = emptyList(),
            onSongClick = { song ->
                onSongClick(song)
                // Delay per evitare che closeSearch() cancelli l'adapter troppo presto
                Handler(Looper.getMainLooper()).postDelayed({
                    closeSearch()
                }, 100)
            },
            onSongMenuClick = { song, action ->
                handleSongMenuAction(song, action)
            }
        )
        searchResultsRecycler.adapter = searchAdapter

        playerBottomContainer = findViewById(R.id.player_bottom_container)
        currentSongTitle = findViewById(R.id.current_song_title)
        currentSongArtist = findViewById(R.id.current_song_artist)
        btnBottomPrevious = findViewById(R.id.btn_previous)
        btnBottomPlayPause = findViewById(R.id.btn_play_pause)
        btnBottomNext = findViewById(R.id.btn_next)
        btnExpandPlayer = findViewById(R.id.btn_expand_player)

        bottomSeekBar = findViewById(R.id.seek_bar)
        bottomCurrentTime = findViewById(R.id.current_time)
        bottomTotalTime = findViewById(R.id.total_time)

        Log.d("MainActivity", "Views setup completed")
    }

    private fun setupViewPager() {
        Log.d("MainActivity", "Setting up ViewPager...")

        viewPagerAdapter = MainViewPagerAdapter(this, emptyList())
        viewPager.adapter = viewPagerAdapter

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

        btnSearch.setOnClickListener { toggleSearch() }
        btnLogin.setOnClickListener { toggleLogin() }
        btnSettings.setOnClickListener { openSettings() }
        btnSort.setOnClickListener { showSortOptions() }

        btnCloseSearch.setOnClickListener { closeSearch() }

        // ✅ FIX: TextWatcher più robusto con debouncing e protezioni
        var searchRunnable: Runnable? = null
        val searchHandler = Handler(Looper.getMainLooper())

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                // Non fare nulla
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Non fare nulla qui - aspettiamo afterTextChanged
            }

            override fun afterTextChanged(s: Editable?) {
                // ✅ PROTEZIONE 1: Verifica che s non sia null
                if (s == null) {
                    Log.w("MainActivity", "⚠️ Search text is null")
                    return
                }

                try {
                    // ✅ PROTEZIONE 2: Cancella la ricerca precedente (debouncing)
                    searchRunnable?.let { searchHandler.removeCallbacks(it) }

                    val query = s.toString()
                    Log.d("MainActivity", "🔍 Search text changed: '$query' (length: ${query.length})")

                    // ✅ PROTEZIONE 3: Aspetta 300ms prima di cercare (debouncing)
                    // Questo evita crash se l'utente digita velocemente
                    searchRunnable = Runnable {
                        try {
                            if (query.isEmpty()) {
                                // Svuota i risultati se la query è vuota
                                runOnUiThread {
                                    searchAdapter.updateSongs(emptyList())
                                }
                            } else {
                                // Esegui la ricerca
                                runOnUiThread {
                                    performSearch(query)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "❌ Error in search runnable: ${e.message}")
                            e.printStackTrace()
                            runOnUiThread {
                                try {
                                    searchAdapter.updateSongs(emptyList())
                                } catch (e2: Exception) {
                                    Log.e("MainActivity", "❌ Error clearing search results: ${e2.message}")
                                }
                            }
                        }
                    }

                    // Ritarda l'esecuzione di 300ms
                    searchHandler.postDelayed(searchRunnable!!, 300)

                } catch (e: Exception) {
                    Log.e("MainActivity", "❌ Error in afterTextChanged: ${e.message}")
                    e.printStackTrace()
                }
            }
        })

        btnBottomPrevious.setOnClickListener {
            musicPlayer.playPrevious()
            Log.d("MainActivity", "⏮ Bottom bar previous clicked")
        }

        btnBottomPlayPause.setOnClickListener {
            musicPlayer.playPause()
            Log.d("MainActivity", "⏯ Bottom bar play/pause clicked")
        }

        btnBottomNext.setOnClickListener {
            musicPlayer.playNext()
            Log.d("MainActivity", "⏭ Bottom bar next clicked")
        }

        btnExpandPlayer.setOnClickListener {
            openPlayerActivity()
            btnExpandPlayer.setOnClickListener {
                // NUOVO: Animazione sul bottone
                AnimationUtils.scaleButton(btnExpandPlayer)

                val intent = Intent(this, PlayerActivity::class.java)
                startActivity(intent)

                // NUOVO: Transizione fluida
                AnimationUtils.slideUpActivityTransition(this)
            }
        }

        playerBottomContainer.setOnClickListener {
            openPlayerActivity()
        }

        bottomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUpdatingBottomProgress = true
                Log.d("MainActivity", "⏸ Started touching bottom seekBar")
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let {
                    val progress = it.progress
                    val duration = musicPlayer.getDuration()
                    val newPositionMs = (progress.toLong() * duration) / 100

                    Log.d("MainActivity", "🎯 SeekBar: progress=$progress%, duration=$duration ms, seeking to $newPositionMs ms")
                    musicPlayer.seekTo(newPositionMs)
                }
                isUpdatingBottomProgress = false
            }

            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = musicPlayer.getDuration()
                    val estimatedTime = (progress.toLong() * duration) / 100
                    bottomCurrentTime.text = formatTime(estimatedTime)
                }
            }
        })

        Log.d("MainActivity", "Listeners setup completed")
    }

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

    // ✅ FIX CRASH RICERCA: Try-catch e gestione errori
    private fun performSearch(query: String) {
        try {
            Log.d("MainActivity", "🔍 Performing search for: '$query'")

            // ✅ PROTEZIONE 1: Verifica che songs non sia vuota
            if (songs.isEmpty()) {
                Log.w("MainActivity", "⚠️ Songs list is empty, cannot search")
                searchAdapter.updateSongs(emptyList())
                return
            }

            // ✅ PROTEZIONE 2: Verifica che la query non sia vuota
            if (query.isBlank()) {
                searchAdapter.updateSongs(emptyList())
                return
            }

            // ✅ PROTEZIONE 3: Filtra con try-catch
            val searchResults = try {
                songs.filter { song ->
                    song.title.contains(query, ignoreCase = true) ||
                            song.artist.contains(query, ignoreCase = true) ||
                            song.album.contains(query, ignoreCase = true)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "❌ Error filtering songs: ${e.message}")
                e.printStackTrace()
                emptyList()
            }

            // ✅ PROTEZIONE 4: Aggiorna adapter con try-catch
            try {
                searchAdapter.updateSongs(searchResults)
                Log.d("MainActivity", "🔍 Search for '$query' returned ${searchResults.size} results")
            } catch (e: Exception) {
                Log.e("MainActivity", "❌ Error updating search adapter: ${e.message}")
                e.printStackTrace()
            }

        } catch (e: Exception) {
            Log.e("MainActivity", "❌ Critical error in performSearch: ${e.message}")
            e.printStackTrace()
            try {
                searchAdapter.updateSongs(emptyList())
            } catch (e2: Exception) {
                Log.e("MainActivity", "❌ Error clearing adapter: ${e2.message}")
            }
        }
    }

    private fun toggleLogin() {
        if (isLoggedIn) {
            // ✅ FIX: signOut richiede context e callback
            googleAuthManager.signOut(this) { success ->
                if (success) {
                    isLoggedIn = false
                    saveLoginState(false, "")
                    updateLoginButton()
                    updatePlaylistsFragmentLoginState()

                    Toast.makeText(this, "Logout effettuato", Toast.LENGTH_SHORT).show()
                    Log.d("MainActivity", "User logged out")
                } else {
                    Toast.makeText(this, "Errore durante il logout", Toast.LENGTH_SHORT).show()
                    Log.e("MainActivity", "Logout failed")
                }
            }
        } else {
            // ✅ FIX: signIn() invece di getSignInIntent()
            val signInIntent = googleAuthManager.signIn()
            if (signInIntent != null) {
                googleSignInLauncher.launch(signInIntent)
                Log.d("MainActivity", "Sign in intent launched")
            } else {
                Toast.makeText(this, "Errore inizializzazione login", Toast.LENGTH_SHORT).show()
                Log.e("MainActivity", "Failed to get sign-in intent")
            }
        }
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    private fun showSortOptions() {
        val options = arrayOf(
            getString(R.string.sorted_by_title),
            getString(R.string.sorted_by_artist),
            getString(R.string.sorted_by_album),
            getString(R.string.sorted_by_duration),
            getString(R.string.sorted_by_date)
        )

        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle(getString(R.string.sort_songs))
        builder.setItems(options) { _, which ->
            currentSortType = when (which) {
                0 -> SortType.TITLE_ASC
                1 -> SortType.ARTIST_ASC
                2 -> SortType.ALBUM_ASC
                3 -> SortType.DURATION_ASC
                4 -> SortType.DATE_ADDED_DESC
                else -> SortType.TITLE_ASC
            }
            applySorting()
        }
        builder.show()
    }

    private fun applySorting() {
        val sortedSongs = when (currentSortType) {
            SortType.TITLE_ASC -> songs.sortedBy { it.title }
            SortType.ARTIST_ASC -> songs.sortedBy { it.artist }
            SortType.ALBUM_ASC -> songs.sortedBy { it.album }
            SortType.DURATION_ASC -> songs.sortedBy { it.duration }
            SortType.DATE_ADDED_DESC -> songs.sortedByDescending { it.id }
        }

        songs = sortedSongs
        viewPagerAdapter.updateSongs(sortedSongs)

        if (isSearchActive && searchInput.text.isNotEmpty()) {
            performSearch(searchInput.text.toString())
        }

        val sortName = when (currentSortType) {
            SortType.TITLE_ASC -> getString(R.string.sorted_by_title)
            SortType.ARTIST_ASC -> getString(R.string.sorted_by_artist)
            SortType.ALBUM_ASC -> getString(R.string.sorted_by_album)
            SortType.DURATION_ASC -> getString(R.string.sorted_by_duration)
            SortType.DATE_ADDED_DESC -> getString(R.string.sorted_by_date)
        }

        Toast.makeText(this, sortName, Toast.LENGTH_SHORT).show()
        Log.d("MainActivity", "✅ Sorting applied: $sortName")
    }

    private fun updateSortButtonIcon() {
        btnSort.setImageResource(R.drawable.ic_sort)
        ImageViewCompat.setImageTintList(
            btnSort,
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.text_primary))
        )
    }

    // ✅ FIX CRASH RICERCA: Gestione sicura dell'indice
    fun onSongClick(song: Song) {
        Log.d("MainActivity", "🎵 Song clicked: ${song.title}")

        try {
            val songIndex = songs.indexOfFirst { it.id == song.id }

            if (songIndex == -1) {
                Log.w("MainActivity", "⚠️ Song not found in main list")
                musicPlayer.setPlaylist(songs, 0)
                musicPlayer.playSong(song)
            } else {
                musicPlayer.setPlaylist(songs, songIndex)
                musicPlayer.playSong(song)
            }

            updatePlayerBottomBar(true, song)

            // ✅ NUOVO: Animazione
            AnimationUtils.slideInFromBottom(playerBottomContainer, AnimationUtils.DURATION_SHORT)

            val serviceIntent = Intent(this, MusicService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            Log.d("MainActivity", "🚀 Service started")

        } catch (e: Exception) {
            Log.e("MainActivity", "❌ Error: ${e.message}")
            AnimationUtils.shake(playerBottomContainer)
        }
    }

    fun handleSongMenuAction(song: Song, action: SongAdapter.MenuAction) {
        when (action) {
            SongAdapter.MenuAction.ADD_TO_PLAYLIST -> {
                Log.d("MainActivity", "Add to playlist: ${song.title}")
            }
            SongAdapter.MenuAction.SONG_DETAILS -> {
                Log.d("MainActivity", "Show details: ${song.title}")
            }
            SongAdapter.MenuAction.REMOVE_FROM_PLAYLIST -> {
                Log.d("MainActivity", "Remove from playlist: ${song.title}")
            }
            SongAdapter.MenuAction.DELETE_FROM_DEVICE -> {
                Log.d("MainActivity", "Delete from device: ${song.title}")
            }
        }
    }

    private fun openPlayerActivity() {
        val intent = Intent(this, PlayerActivity::class.java)
        startActivity(intent)
    }

    private fun updatePlayerBottomBar(isPlaying: Boolean, song: Song?) {
        if (song != null) {
            playerBottomContainer.visibility = View.VISIBLE

            currentSongTitle.text = song.title
            currentSongArtist.text = song.artist

            if (isPlaying) {
                btnBottomPlayPause.setImageResource(android.R.drawable.ic_media_pause)
            } else {
                btnBottomPlayPause.setImageResource(android.R.drawable.ic_media_play)
            }

            bottomTotalTime.text = song.getFormattedDuration()

            startBottomProgressUpdates()
            updateBottomProgress()

            Log.d("MainActivity", "Bottom bar updated: ${song.title}, isPlaying=$isPlaying")
        } else {
            playerBottomContainer.visibility = View.GONE
            stopBottomProgressUpdates()
            Log.d("MainActivity", "Bottom bar hidden (no song)")
        }
    }

    private fun startBottomProgressUpdates() {
        progressHandler.removeCallbacks(bottomProgressRunnable)
        progressHandler.post(bottomProgressRunnable)
    }

    private fun stopBottomProgressUpdates() {
        progressHandler.removeCallbacks(bottomProgressRunnable)
    }

    private fun updateBottomProgress() {
        if (!isUpdatingBottomProgress) return

        val currentPosition = musicPlayer.getCurrentPosition()
        val duration = musicPlayer.getDuration()

        if (duration > 0) {
            val progress = ((currentPosition.toFloat() / duration) * bottomSeekBar.max).toInt()

            // ✅ NUOVO: Animazione seekbar
            AnimationUtils.animateSeekBar(bottomSeekBar, progress, 200L)

            bottomCurrentTime.text = formatTime(currentPosition.toInt())
        }
    }

    private fun formatTime(milliseconds: Long): String {
        val seconds = (milliseconds / 1000).toInt()
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format("%d:%02d", minutes, remainingSeconds)
    }

    private fun updateSongCount() {
        songCountText.text = getString(R.string.song_count_format, songs.size)
    }

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

            onMusicLoaded(songs)

        } catch (e: Exception) {
            Log.e("MainActivity", "❌ Errore caricamento musica: ${e.message}")
            Toast.makeText(
                this,
                getString(R.string.error_loading_music, e.message),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ✅ NUOVO: Callback dopo caricamento musica - ripristina stato
    private fun onMusicLoaded(loadedSongs: List<Song>) {
        Log.d("MainActivity", "=== MUSIC LOADED ===")
        Log.d("MainActivity", "Songs loaded: ${loadedSongs.size}")

        songs = loadedSongs
        updateSongCount()

        applySorting()
        updateSortButtonIcon()

        viewPagerAdapter.updateSongs(songs)

        // ✅ NUOVO: Ripristina lo stato salvato
        restorePlaybackState()

        Toast.makeText(
            this,
            getString(R.string.songs_loaded_format, songs.size),
            Toast.LENGTH_SHORT
        ).show()

        Log.d("MainActivity", "✅ MainActivity updated with songs")
    }

    // ✅ NUOVO: Ripristina stato salvato
    private fun restorePlaybackState() {
        try {
            val savedState = playbackStateManager.loadPlaybackState(songs)

            if (savedState != null) {
                Log.d("MainActivity", "🔄 Restoring playback state...")

                // Ripristina la coda e la canzone corrente
                musicPlayer.setPlaylist(savedState.queue, savedState.currentIndex)

                // Ripristina shuffle e repeat
                if (savedState.isShuffleEnabled != musicPlayer.isShuffleEnabled()) {
                    musicPlayer.toggleShuffle()
                }

                var currentRepeatMode = musicPlayer.getRepeatMode()
                while (currentRepeatMode != savedState.repeatMode) {
                    currentRepeatMode = musicPlayer.toggleRepeat()
                }

                // Ripristina la posizione
                musicPlayer.seekTo(savedState.position)

                // Aggiorna l'UI
                updatePlayerBottomBar(musicPlayer.isPlaying(), savedState.currentSong)

                Toast.makeText(this, "Ripresa: ${savedState.currentSong.title}", Toast.LENGTH_SHORT).show()
                Log.d("MainActivity", "✅ Playback state restored!")
            } else {
                Log.d("MainActivity", "No saved state to restore")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "❌ Error restoring state: ${e.message}")
        }
    }

    // ✅ NUOVO: Salva stato corrente
    private fun saveCurrentPlaybackState() {
        try {
            val currentSong = musicPlayer.getCurrentSong()
            if (currentSong != null) {
                playbackStateManager.savePlaybackState(
                    currentSong = currentSong,
                    position = musicPlayer.getCurrentPosition(),
                    queue = musicPlayer.getCurrentQueue(),
                    currentIndex = musicPlayer.getCurrentIndex(),
                    isShuffleEnabled = musicPlayer.isShuffleEnabled(),
                    repeatMode = musicPlayer.getRepeatMode(),
                    wasPlaying = musicPlayer.isPlaying()
                )
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error saving playback state: ${e.message}")
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    999
                )
                Log.d("MainActivity", "🔔 Requesting notification permission")
            } else {
                Log.d("MainActivity", "✅ Notification permission already granted")
            }
        } else {
            Log.d("MainActivity", "📱 Android < 13, no notification permission needed")
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d("MainActivity", "=== ON RESUME ===")

        // ✅ NUOVO: Notifica foreground
        batteryOptimizer.setForeground(true)

        val currentSong = musicPlayer.getCurrentSong()
        val isPlaying = musicPlayer.isPlaying()
        updatePlayerBottomBar(isPlaying, currentSong)

        stateSaveHandler.post(stateSaveRunnable)
    }

    override fun onPause() {
        super.onPause()

        // ✅ NUOVO: Notifica background
        batteryOptimizer.setForeground(false)

        stopBottomProgressUpdates()
        stateSaveHandler.removeCallbacks(stateSaveRunnable)
        saveCurrentPlaybackState()

        Log.d("MainActivity", "MainActivity paused, state saved")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBottomProgressUpdates()

        // ✅ NUOVO: Salva stato prima di distruggere
        saveCurrentPlaybackState()

        musicPlayer.removeStateChangeListener(mainActivityPlayerListener)
        musicPlayer.removeQueueChangeListener(mainQueueChangeListener)

        Log.d("MainActivity", "MainActivity destroyed, final state saved")
    }

    fun getCurrentUserId(): String {
        val userId = googleAuthManager.getUserId() ?: ""
        Log.d("MainActivity", "getCurrentUserId: $userId")
        return userId
    }

    fun getAllSongs(): List<Song> {
        return songs
    }
}