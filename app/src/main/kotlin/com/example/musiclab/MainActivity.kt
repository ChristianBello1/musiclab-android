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

    // Core components
    private lateinit var musicScanner: MusicScanner
    private lateinit var musicPlayer: MusicPlayer
    private var songs: List<Song> = emptyList()

    // Persistenza stato
    private lateinit var playbackStateManager: PlaybackStateManager

    // ✅ NUOVO: Battery Optimizer
    private lateinit var batteryOptimizer: BatteryOptimizer

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

    private lateinit var btnStop: ImageButton
    private lateinit var btnExpandPlayer: ImageButton
    private lateinit var songInfoContainer: View
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

    // Timer per salvare periodicamente lo stato
    private val stateSaveHandler = Handler(Looper.getMainLooper())
    private val stateSaveRunnable = object : Runnable {
        override fun run() {
            saveCurrentPlaybackState()
            stateSaveHandler.postDelayed(this, 5000)
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

        playbackStateManager = PlaybackStateManager(this)

        // ✅ NUOVO: Inizializza BatteryOptimizer
        batteryOptimizer = BatteryOptimizer.getInstance(this)
        Log.d("MainActivity", "✅ BatteryOptimizer initialized")

        musicPlayer.addStateChangeListener(mainActivityPlayerListener)
        musicPlayer.addQueueChangeListener(mainQueueChangeListener)

        checkPermissionsAndLoadMusic()
        checkNotificationPermission()

        Log.d("MainActivity", "=== MAIN ACTIVITY SETUP COMPLETE ===")
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

        searchAdapter = SongAdapter(
            songs = emptyList(),
            onSongClick = { song ->
                onSongClick(song)
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
        btnStop = findViewById(R.id.btn_stop)
        btnExpandPlayer = findViewById(R.id.btn_expand_player)
        songInfoContainer = findViewById(R.id.song_info_container)

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

        btnSearch.setOnClickListener {
            AnimationUtils.scaleButton(btnSearch)
            toggleSearch()
        }

        btnLogin.setOnClickListener {
            AnimationUtils.scaleButton(btnLogin)
            toggleLogin()
        }

        btnSettings.setOnClickListener {
            AnimationUtils.scaleButton(btnSettings)
            openSettings()
        }

        btnSort.setOnClickListener {
            AnimationUtils.scaleButton(btnSort)
            showSortOptions()
        }

        btnCloseSearch.setOnClickListener { closeSearch() }

        var searchRunnable: Runnable? = null
        val searchHandler = Handler(Looper.getMainLooper())

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                searchRunnable?.let { searchHandler.removeCallbacks(it) }

                val query = s?.toString()?.trim() ?: ""

                if (query.isEmpty()) {
                    searchAdapter.updateSongs(emptyList())
                    return
                }

                searchRunnable = Runnable {
                    try {
                        performSearch(query)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error in search: ${e.message}")
                    }
                }

                searchHandler.postDelayed(searchRunnable!!, 300)
            }
        })

        btnStop.setOnClickListener {
            AnimationUtils.scaleButton(btnStop)
            musicPlayer.playPause()  // ✅ Cambiato da pause() a playPause()
            Log.d("MainActivity", "⏯️ Bottom bar play/pause clicked")
        }

// Pulsante Coda - apre PlayerActivity
        btnExpandPlayer.setOnClickListener {
            AnimationUtils.scaleButton(btnExpandPlayer)

            val intent = Intent(this, PlayerActivity::class.java)
            startActivity(intent)

            AnimationUtils.slideUpActivityTransition(this)
        }

// Cliccando sul testo della canzone - apre PlayerActivity
        songInfoContainer.setOnClickListener {
            val intent = Intent(this, PlayerActivity::class.java)
            startActivity(intent)
            AnimationUtils.slideUpActivityTransition(this)
            Log.d("MainActivity", "🎵 Song info clicked - opening player")
        }

        bottomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    bottomCurrentTime.text = formatTime(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUpdatingBottomProgress = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUpdatingBottomProgress = false
                seekBar?.let {
                    musicPlayer.seekTo(it.progress)
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
        searchContainer.visibility = View.VISIBLE
        searchInput.requestFocus()
        isSearchActive = true
        Log.d("MainActivity", "Search opened")
    }

    private fun closeSearch() {
        searchContainer.visibility = View.GONE
        searchInput.text.clear()
        searchInput.clearFocus()
        searchAdapter.updateSongs(emptyList())
        isSearchActive = false
        Log.d("MainActivity", "Search closed")
    }

    private fun performSearch(query: String) {
        val results = songs.filter { song ->
            song.title.contains(query, ignoreCase = true) ||
                    song.artist.contains(query, ignoreCase = true) ||
                    song.album.contains(query, ignoreCase = true)
        }

        searchAdapter.updateSongs(results)
        Log.d("MainActivity", "Search results: ${results.size} for query: $query")
    }

    private fun toggleLogin() {
        if (isLoggedIn) {
            showLogoutConfirmation()
        } else {
            startGoogleSignIn()
        }
    }

    private fun showLogoutConfirmation() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Vuoi effettuare il logout?")
            .setPositiveButton("Sì") { _, _ ->
                performLogout()
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun performLogout() {
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
    }

    private fun startGoogleSignIn() {
        // ✅ FIX: signIn() invece di getSignInIntent()
        val signInIntent = googleAuthManager.signIn()
        if (signInIntent != null) {
            googleSignInLauncher.launch(signInIntent)
            Log.d("MainActivity", "Starting Google Sign-In")
        } else {
            Toast.makeText(this, "Errore inizializzazione login", Toast.LENGTH_SHORT).show()
            Log.e("MainActivity", "Failed to get sign-in intent")
        }
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
        AnimationUtils.overrideActivityTransition(this)
    }

    private fun showSortOptions() {
        val options = arrayOf(
            getString(R.string.sort_by_title),
            getString(R.string.sort_by_artist),
            getString(R.string.sort_by_album),
            getString(R.string.sort_by_duration),
            getString(R.string.sort_by_date_added)
        )

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.sort_songs))
            .setItems(options) { _, which ->
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
            .show()
    }

    private fun applySorting() {
        val sortedSongs = when (currentSortType) {
            SortType.TITLE_ASC -> songs.sortedBy { it.title.lowercase() }
            SortType.ARTIST_ASC -> songs.sortedBy { it.artist.lowercase() }
            SortType.ALBUM_ASC -> songs.sortedBy { it.album.lowercase() }
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

    private fun handleSongMenuAction(song: Song, action: SongAdapter.MenuAction) {
        when (action) {
            SongAdapter.MenuAction.ADD_TO_PLAYLIST -> {
                Log.d("MainActivity", "Add to playlist: ${song.title}")
            }
            SongAdapter.MenuAction.SONG_DETAILS -> {
                showSongInfo(song)
            }
            SongAdapter.MenuAction.REMOVE_FROM_PLAYLIST -> {
                Log.d("MainActivity", "Remove from playlist: ${song.title}")
            }
            SongAdapter.MenuAction.DELETE_FROM_DEVICE -> {
                Log.d("MainActivity", "Delete from device: ${song.title}")
            }
        }
    }

    private fun showSongInfo(song: Song) {
        val info = """
            Titolo: ${song.title}
            Artista: ${song.artist}
            Album: ${song.album}
            Durata: ${song.getFormattedDuration()}
        """.trimIndent()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Info Canzone")
            .setMessage(info)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun updatePlayerBottomBar(isPlaying: Boolean, currentSong: Song?) {
        if (currentSong != null) {
            playerBottomContainer.visibility = View.VISIBLE

            currentSongTitle.text = currentSong.title
            currentSongArtist.text = currentSong.artist

            // Aggiorna icona stop (pause quando suona, play quando fermo)
            val iconRes = if (isPlaying) {
                android.R.drawable.ic_media_pause
            } else {
                android.R.drawable.ic_media_play
            }
            btnStop.setImageResource(iconRes)

            bottomSeekBar.max = (currentSong.duration / 1000).toInt()
            bottomTotalTime.text = currentSong.getFormattedDuration()

            if (!isUpdatingBottomProgress) {
                startBottomProgressUpdates()
            }

            Log.d("MainActivity", "Bottom bar updated: ${currentSong.title}, playing=$isPlaying")
        } else {
            playerBottomContainer.visibility = View.GONE
            stopBottomProgressUpdates()
            Log.d("MainActivity", "Bottom bar hidden: no song playing")
        }
    }

    private fun startBottomProgressUpdates() {
        if (!isUpdatingBottomProgress) {
            isUpdatingBottomProgress = true
            progressHandler.post(bottomProgressRunnable)
            Log.d("MainActivity", "✅ Bottom progress updates started")
        }
    }

    private fun stopBottomProgressUpdates() {
        isUpdatingBottomProgress = false
        progressHandler.removeCallbacks(bottomProgressRunnable)
        Log.d("MainActivity", "⏹️ Bottom progress updates stopped")
    }

    private fun updateBottomProgress() {
        if (!isUpdatingBottomProgress) return

        val currentPosition = musicPlayer.getCurrentPosition()
        val duration = musicPlayer.getDuration()

        if (duration > 0) {
            val progress = ((currentPosition.toFloat() / duration) * bottomSeekBar.max).toInt()

            AnimationUtils.animateSeekBar(bottomSeekBar, progress, 200L)

            // ✅ FIX: converti millisecondi in secondi
            val currentSeconds = (currentPosition / 1000).toInt()
            bottomCurrentTime.text = formatTime(currentSeconds)
        }
    }

    private fun formatTime(seconds: Int): String {
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

    private fun onMusicLoaded(loadedSongs: List<Song>) {
        Log.d("MainActivity", "=== MUSICA CARICATA, AGGIORNO UI ===")

        songs = loadedSongs
        viewPagerAdapter.updateSongs(songs)
        updateSongCount()

        val savedState = playbackStateManager.loadPlaybackState(songs)
        if (savedState != null) {
            Log.d("MainActivity", "✅ Ripristino stato: ${savedState.currentSong?.title}")

            musicPlayer.setPlaylist(savedState.queue, savedState.currentIndex)

            if (savedState.currentSong != null) {
                musicPlayer.playSong(savedState.currentSong)
                musicPlayer.seekTo(savedState.position.toInt())

                if (!savedState.wasPlaying) {
                    musicPlayer.pause()
                }

                // ✅ FIX: Ripristina shuffle condizionalmente
                if (savedState.isShuffleEnabled != musicPlayer.isShuffleEnabled()) {
                    musicPlayer.toggleShuffle()
                }

                // ✅ FIX: Ripristina repeat mode con loop
                var currentRepeatMode = musicPlayer.getRepeatMode()
                while (currentRepeatMode != savedState.repeatMode) {
                    currentRepeatMode = musicPlayer.toggleRepeat()
                }

                updatePlayerBottomBar(savedState.wasPlaying, savedState.currentSong)
            }
        }

        Toast.makeText(
            this,
            getString(R.string.songs_loaded_format, songs.size),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun saveCurrentPlaybackState() {
        try {
            val currentSong = musicPlayer.getCurrentSong()
            val position = musicPlayer.getCurrentPosition().toLong()
            // ✅ FIX: getCurrentQueue() invece di getQueue()
            val queue = musicPlayer.getCurrentQueue()
            // ✅ FIX: getCurrentIndex() invece di getCurrentSongIndex()
            val currentIndex = musicPlayer.getCurrentIndex()
            val isShuffleEnabled = musicPlayer.isShuffleEnabled()
            val repeatMode = musicPlayer.getRepeatMode()
            val wasPlaying = musicPlayer.isPlaying()

            playbackStateManager.savePlaybackState(
                currentSong = currentSong,
                position = position,
                queue = queue,
                currentIndex = currentIndex,
                isShuffleEnabled = isShuffleEnabled,
                repeatMode = repeatMode,
                wasPlaying = wasPlaying
            )

            Log.d("MainActivity", "💾 State saved")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error saving state: ${e.message}")
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