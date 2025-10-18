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

    // NUOVO: Google Auth
    private lateinit var googleAuthManager: GoogleAuthManager
    private var isLoggedIn = false

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
    private lateinit var bottomSeekBar: SeekBar
    private lateinit var bottomCurrentTime: TextView
    private lateinit var bottomTotalTime: TextView

    private var currentSortType = SortType.TITLE_ASC

    // NUOVO: Launcher per Google Sign-In
    @Suppress("DEPRECATION")
    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val success = googleAuthManager.handleSignInResult(result.data)
        if (success) {
            val user = googleAuthManager.getCurrentUser()
            val userId = googleAuthManager.getUserId() ?: ""

            // ← AGGIUNGI QUESTE RIGHE
            isLoggedIn = true
            saveLoginState(true, userId)
            updateLoginButton()
            updatePlaylistsFragmentLoginState()
            // FINE AGGIUNTA

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

        // IMPORTANTE: Inizializza Google Auth DOPO setupViews()
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

        musicPlayer.addStateChangeListener(mainActivityPlayerListener)
        musicPlayer.addQueueChangeListener(mainQueueChangeListener)

        checkPermissionsAndLoadMusic()
        checkNotificationPermission()

        Log.d("MainActivity", "=== MAIN ACTIVITY SETUP COMPLETE ===")
    }

    // NUOVO: Inizializza Google Authentication
// NUOVO: Inizializza Google Authentication
// NUOVO: Inizializza Google Authentication
    private fun initializeGoogleAuth() {
        googleAuthManager = GoogleAuthManager.getInstance()
        googleAuthManager.initialize(this)

        googleAuthManager.addAuthStateListener { loggedIn, user ->
            runOnUiThread {
                isLoggedIn = loggedIn
                updateLoginButton()

                val userId = googleAuthManager.getUserId() ?: ""

                // ← AGGIUNGI QUESTE 2 RIGHE
                saveLoginState(loggedIn, userId)
                updatePlaylistsFragmentLoginState()
                // FINE AGGIUNTA

                // Resto del codice rimane uguale
                viewPagerAdapter.getPlaylistsFragment()?.setLoginState(loggedIn, userId)

                Log.d("MainActivity", "Auth state: logged in = $loggedIn")
                if (user != null) {
                    Log.d("MainActivity", "User: ${user.displayName} (${user.email})")
                }
            }
        }
    }

    // NUOVO: Controlla lo stato di login salvato all'avvio
    private fun checkSavedLoginState() {
        val prefs = getSharedPreferences("MusicLabPrefs", Context.MODE_PRIVATE)
        val savedLoginState = prefs.getBoolean("is_logged_in", false)
        val savedUserId = prefs.getString("user_id", "")

        Log.d("MainActivity", "Checking saved login state: logged=$savedLoginState, userId=$savedUserId")

        // Se c'è uno stato salvato, verifica con GoogleAuthManager
        if (savedLoginState && !savedUserId.isNullOrEmpty()) {
            isLoggedIn = googleAuthManager.isLoggedIn()

            if (isLoggedIn) {
                Log.d("MainActivity", "Session restored successfully")
                updateLoginButton()
                updatePlaylistsFragmentLoginState()
            } else {
                Log.d("MainActivity", "Saved session expired, clearing saved state")
                // Pulisci lo stato salvato se la sessione è scaduta
                prefs.edit().apply {
                    putBoolean("is_logged_in", false)
                    putString("user_id", "")
                    apply()
                }
            }
        }
    }

    // NUOVO: Salva lo stato di login
    private fun saveLoginState(loggedIn: Boolean, userId: String) {
        val prefs = getSharedPreferences("MusicLabPrefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("is_logged_in", loggedIn)
            putString("user_id", userId)
            apply()
        }

        Log.d("MainActivity", "Saved login state: logged=$loggedIn, userId=$userId")
    }

    // NUOVO: Aggiorna lo stato di login nel fragment delle playlist
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
                closeSearch()
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
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString()
                if (query.isNotEmpty()) {
                    performSearch(query)
                } else {
                    searchAdapter.updateSongs(emptyList())
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
                    // Il progress è da 0 a 100 (perché max="100" nell'XML)
                    val progress = it.progress
                    val duration = musicPlayer.getDuration() // in millisecondi

                    // ✅ FIX: Calcola la posizione in millisecondi usando .toLong()
                    val newPositionMs = (progress.toLong() * duration) / 100

                    Log.d("MainActivity", "🎯 SeekBar: progress=$progress%, duration=$duration ms, seeking to $newPositionMs ms")

                    // ✅ FIX CRITICO: Chiama seekTo(Long) per passare millisecondi direttamente
                    // NON usare .toInt() perché seekTo(Int) moltiplica per 1000!
                    musicPlayer.seekTo(newPositionMs)
                }
                isUpdatingBottomProgress = false
            }

            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Opzionale: Mostra il tempo mentre l'utente trascina
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

    private fun performSearch(query: String) {
        val searchResults = if (query.isEmpty()) {
            emptyList()
        } else {
            songs.filter { song ->
                song.title.contains(query, ignoreCase = true) ||
                        song.artist.contains(query, ignoreCase = true) ||
                        song.album.contains(query, ignoreCase = true)
            }
        }

        searchAdapter.updateSongs(searchResults)
        Log.d("MainActivity", "🔍 Search for '$query' returned ${searchResults.size} results")
    }

    // AGGIORNATO: Login functionality con Google reale
    private fun toggleLogin() {
        if (isLoggedIn) {
            performLogout()
        } else {
            performLogin()
        }
    }

    private fun performLogin() {
        val signInIntent = googleAuthManager.signIn()
        if (signInIntent != null) {
            googleSignInLauncher.launch(signInIntent)
            Log.d("MainActivity", "Google Sign-In launched")
        } else {
            Toast.makeText(this, "Errore inizializzazione login", Toast.LENGTH_SHORT).show()
            Log.e("MainActivity", "Failed to get sign-in intent")
        }
    }

    private fun performLogout() {
        googleAuthManager.signOut(this) { success ->
            if (success) {
                // ← AGGIUNGI QUESTA RIGA
                saveLoginState(false, "")

                Toast.makeText(this, "Logout effettuato", Toast.LENGTH_SHORT).show()
                Log.d("MainActivity", "Logout successful")
            } else {
                Toast.makeText(this, "Errore durante il logout", Toast.LENGTH_SHORT).show()
                Log.e("MainActivity", "Logout failed")
            }
        }
    }

    private fun updateLoginButton() {
        val iconRes = if (isLoggedIn) {
            R.drawable.ic_logout
        } else {
            R.drawable.ic_login
        }
        btnLogin.setImageResource(iconRes)

        // IMPORTANTE: Forza il tint bianco dopo aver cambiato icona
        ImageViewCompat.setImageTintList(
            btnLogin,
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.text_primary))
        )
    }

    private fun openSettings() {
        Toast.makeText(this, "Impostazioni (coming soon!)", Toast.LENGTH_SHORT).show()
    }

    private fun showSortOptions() {
        Log.d("MainActivity", "🔄 Opening sort options")

        val sortOptions = arrayOf(
            getString(R.string.sort_by_title),
            getString(R.string.sort_by_artist),
            getString(R.string.sort_by_album),
            getString(R.string.sort_by_duration),
            getString(R.string.sort_by_date_added)
        )

        val currentSelection = when (currentSortType) {
            SortType.TITLE_ASC -> 0
            SortType.ARTIST_ASC -> 1
            SortType.ALBUM_ASC -> 2
            SortType.DURATION_ASC -> 3
            SortType.DATE_ADDED_DESC -> 4
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.sort_dialog_title))
            .setSingleChoiceItems(sortOptions, currentSelection) { dialog, which ->
                val newSortType = when (which) {
                    0 -> SortType.TITLE_ASC
                    1 -> SortType.ARTIST_ASC
                    2 -> SortType.ALBUM_ASC
                    3 -> SortType.DURATION_ASC
                    4 -> SortType.DATE_ADDED_DESC
                    else -> SortType.TITLE_ASC
                }

                if (newSortType != currentSortType) {
                    currentSortType = newSortType
                    applySorting()
                    updateSortButtonIcon()
                }

                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun applySorting() {
        Log.d("MainActivity", "🔄 Applying sort: $currentSortType")

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
        // Usa sempre l'icona custom ic_sort
        btnSort.setImageResource(R.drawable.ic_sort)

        // Forza il tint bianco
        ImageViewCompat.setImageTintList(
            btnSort,
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.text_primary))
        )
    }

    fun onSongClick(song: Song) {
        Log.d("MainActivity", "🎵 Song clicked: ${song.title}")

        musicPlayer.setPlaylist(songs, songs.indexOf(song))
        musicPlayer.playSong(song)

        // ✅ FIX: Aggiorna SUBITO la bottom bar, non aspettare il listener
        updatePlayerBottomBar(true, song)

        // ✅ FORZA L'AVVIO DEL SERVIZIO
        val serviceIntent = Intent(this, MusicService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Log.d("MainActivity", "🚀 Service intent sent! Bottom bar updated immediately")

        showPlayerBottomBar()
    }

    private fun handleSongMenuAction(song: Song, action: SongAdapter.MenuAction) {
        when (action) {
            SongAdapter.MenuAction.ADD_TO_PLAYLIST -> {
                // Ottieni il fragment playlist dal ViewPager
                val playlistsFragment = viewPagerAdapter.getPlaylistsFragment()

                if (playlistsFragment != null) {
                    playlistsFragment.showAddToPlaylistDialog(song)
                } else {
                    Toast.makeText(
                        this,
                        "Caricamento playlist in corso...",
                        Toast.LENGTH_SHORT
                    ).show()
                    Log.w("MainActivity", "PlaylistsFragment not yet initialized")
                }
            }
            SongAdapter.MenuAction.SONG_DETAILS -> {
                showSongInfo(song)
            }
            SongAdapter.MenuAction.REMOVE_FROM_PLAYLIST -> {
                // In MainActivity non ha senso rimuovere da playlist
                Toast.makeText(this, "Operazione non disponibile qui", Toast.LENGTH_SHORT).show()
            }
            SongAdapter.MenuAction.DELETE_FROM_DEVICE -> {
                confirmDeleteSong(song)
            }
        }
    }

    private fun showSongInfo(song: Song) {
        val info = """
            Titolo: ${song.title}
            Artista: ${song.artist}
            Album: ${song.album}
            Durata: ${song.getFormattedDuration()}
            Dimensione: ${formatFileSize(song.size)}
            Percorso: ${song.path}
        """.trimIndent()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Informazioni Canzone")
            .setMessage(info)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun confirmDeleteSong(song: Song) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Elimina canzone")
            .setMessage("Vuoi eliminare definitivamente '${song.title}' dal dispositivo?")
            .setPositiveButton("Elimina") { dialog, _ ->
                deleteSongFromDevice(song)
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun deleteSongFromDevice(song: Song) {
        try {
            val file = java.io.File(song.path)
            if (file.exists() && file.delete()) {
                songs = songs.filter { it.id != song.id }
                applySorting()

                if (isSearchActive && searchInput.text.isNotEmpty()) {
                    performSearch(searchInput.text.toString())
                }

                Toast.makeText(this, "Canzone eliminata", Toast.LENGTH_SHORT).show()
                Log.d("MainActivity", "Song deleted: ${song.title}")
            } else {
                Toast.makeText(this, "Errore durante l'eliminazione", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Errore: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e("MainActivity", "Error deleting: $e")
        }
    }

    private fun formatFileSize(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0

        return when {
            gb >= 1.0 -> String.format("%.1f GB", gb)
            mb >= 1.0 -> String.format("%.1f MB", mb)
            kb >= 1.0 -> String.format("%.1f KB", kb)
            else -> "$bytes bytes"
        }
    }

    private fun updatePlayerBottomBar(isPlaying: Boolean, currentSong: Song?) {
        if (currentSong != null) {
            currentSongTitle.text = currentSong.title
            currentSongArtist.text = currentSong.artist

            val playPauseIcon = if (isPlaying) {
                R.drawable.ic_pause
            } else {
                R.drawable.ic_play
            }
            btnBottomPlayPause.setImageResource(playPauseIcon)

            showPlayerBottomBar()
            startBottomProgressUpdates()
        } else {
            hidePlayerBottomBar()
        }
    }

    private fun updateBottomProgress() {
        if (isUpdatingBottomProgress) return

        val currentPos = musicPlayer.getCurrentPosition()
        val duration = musicPlayer.getDuration()

        if (duration > 0) {
            val progress = ((currentPos.toFloat() / duration) * 100).toInt()
            bottomSeekBar.progress = progress

            bottomCurrentTime.text = formatTime(currentPos)
            bottomTotalTime.text = formatTime(duration)
        }
    }

    private fun formatTime(milliseconds: Long): String {
        val seconds = (milliseconds / 1000) % 60
        val minutes = (milliseconds / (1000 * 60)) % 60
        return String.format("%d:%02d", minutes, seconds)
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

            songCountText.text = getString(R.string.song_count_format, songs.size)
            Log.d("MainActivity", "📊 UI aggiornata")

            viewPager.post {
                Log.d("MainActivity", "🔄 Aggiornamento ViewPager...")

                applySorting()
                updateSortButtonIcon()

                viewPagerAdapter = MainViewPagerAdapter(this@MainActivity, songs)
                viewPager.adapter = viewPagerAdapter

                TabLayoutMediator(tabLayout, viewPager) { tab, position ->
                    tab.text = when (position) {
                        0 -> getString(R.string.tab_folders)
                        1 -> getString(R.string.tab_playlists)
                        else -> ""
                    }
                }.attach()

                Log.d("MainActivity", "📱 ViewPager aggiornato!")

                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.songs_loaded_format, songs.size),
                    Toast.LENGTH_SHORT
                ).show()
            }

        } catch (e: Exception) {
            Log.e("MainActivity", "❌ Errore caricamento musica: ${e.message}")
            Toast.makeText(
                this,
                getString(R.string.error_loading_music, e.message),
                Toast.LENGTH_LONG
            ).show()
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

        musicPlayer.removeStateChangeListener(mainActivityPlayerListener)
        musicPlayer.removeQueueChangeListener(mainQueueChangeListener)

        Log.d("MainActivity", "MainActivity destroyed")
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