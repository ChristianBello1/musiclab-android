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
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.signin.GoogleSignInAccount

class MainActivity : AppCompatActivity() {

    // NUOVO: Enum per i tipi di ordinamento
    enum class SortType {
        TITLE_ASC,      // Nome A-Z
        ARTIST_ASC,     // Artista A-Z
        ALBUM_ASC,      // Album A-Z
        DURATION_ASC,   // Durata crescente
        DATE_ADDED_DESC // Data aggiunta (più recenti prima)
    }

    private lateinit var googleAuthManager: GoogleAuthManager

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
    private var currentGoogleUser: GoogleSignInAccount? = null

    // NUOVO: Ordinamento corrente
    private var currentSortType = SortType.TITLE_ASC

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val success = googleAuthManager.handleSignInResult(result.data)
        if (success) {
            Toast.makeText(this, "Login effettuato con successo!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Login fallito", Toast.LENGTH_SHORT).show()
        }
    }

    // Progress handler
    private val progressHandler = Handler(Looper.getMainLooper())
    private var isUpdatingBottomProgress = false

    private val bottomProgressRunnable = object : Runnable {
        override fun run() {
            updateBottomProgress()
            progressHandler.postDelayed(this, 1000)
        }
    }

    // Listener separato per cambiamenti della coda
    private val mainQueueChangeListener: () -> Unit = {
        runOnUiThread {
            Log.d("MainActivity", "🔄 Queue changed in MainActivity - updating bottom bar")
            val currentSong = musicPlayer.getCurrentSong()
            val isPlaying = musicPlayer.isPlaying()
            updatePlayerBottomBar(isPlaying, currentSong)
        }
    }

    // Listener per cambiamenti di stato del player
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

        // Aggiungi entrambi i listener
        musicPlayer.addStateChangeListener(mainActivityPlayerListener)
        musicPlayer.addQueueChangeListener(mainQueueChangeListener)

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
        searchAdapter = SongAdapter(
            songs = emptyList(),
            onSongClick = { song ->
                onSongClick(song)
                closeSearch()
            },
            onSongMenuClick = { song, action -> // NUOVO: callback menu
                handleSongMenuAction(song, action)
            }
        )
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
        btnSort.setOnClickListener { showSortOptions() } // Ora funziona davvero!

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

    // NUOVO: Sistema di ordinamento completo
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

    // NUOVO: Applica l'ordinamento alle canzoni
    private fun applySorting() {
        Log.d("MainActivity", "🔄 Applying sort: $currentSortType")

        val sortedSongs = when (currentSortType) {
            SortType.TITLE_ASC -> songs.sortedBy { it.title.lowercase() }
            SortType.ARTIST_ASC -> songs.sortedBy { it.artist.lowercase() }
            SortType.ALBUM_ASC -> songs.sortedBy { it.album.lowercase() }
            SortType.DURATION_ASC -> songs.sortedBy { it.duration }
            SortType.DATE_ADDED_DESC -> songs.sortedByDescending { it.id } // ID più alto = aggiunto più di recente
        }

        // Aggiorna la lista locale
        songs = sortedSongs

        // Aggiorna il ViewPager con le canzoni ordinate
        viewPagerAdapter.updateSongs(sortedSongs)

        // Aggiorna anche l'adapter di ricerca se attivo
        if (isSearchActive && searchInput.text.isNotEmpty()) {
            performSearch(searchInput.text.toString())
        }

        // Mostra toast con conferma
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

    // NUOVO: Aggiorna l'icona del pulsante sort
    private fun updateSortButtonIcon() {
        val iconRes = when (currentSortType) {
            SortType.TITLE_ASC -> android.R.drawable.ic_menu_sort_alphabetically
            SortType.ARTIST_ASC -> android.R.drawable.ic_menu_info_details
            SortType.ALBUM_ASC -> android.R.drawable.ic_menu_gallery
            SortType.DURATION_ASC -> android.R.drawable.ic_menu_recent_history
            SortType.DATE_ADDED_DESC -> android.R.drawable.ic_menu_today
        }

        btnSort.setImageResource(iconRes)

        // Cambia anche il colore per indicare che un ordinamento è attivo
        val colorRes = if (currentSortType == SortType.TITLE_ASC) {
            // Ordinamento di default - colore normale
            ContextCompat.getColor(this, android.R.color.black)
        } else {
            // Ordinamento personalizzato - colore primario
            ContextCompat.getColor(this, R.color.purple_500)
        }

        btnSort.setColorFilter(colorRes)
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

            val realCurrentSong = musicPlayer.getCurrentSong()
            val songToShow = realCurrentSong ?: currentSong

            currentSongTitle.text = songToShow.title
            currentSongArtist.text = songToShow.artist
            bottomTotalTime.text = songToShow.getFormattedDuration()
            bottomSeekBar.max = (songToShow.duration / 1000).toInt()

            val iconRes = if (isPlaying) {
                android.R.drawable.ic_media_pause
            } else {
                android.R.drawable.ic_media_play
            }
            btnBottomPlayPause.setImageResource(iconRes)

            if (isPlaying) {
                startBottomProgressUpdates()
            }

            Log.d("MainActivity", "✅ Bottom bar updated with: ${songToShow.title}")
        } else {
            hidePlayerBottomBar()
            stopBottomProgressUpdates()
            Log.d("MainActivity", "❌ No song - hiding bottom bar")
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

    // AGGIORNATO: Ora applica ordinamento dopo il caricamento
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

                // NUOVO: Applica ordinamento di default dopo il caricamento
                applySorting()
                updateSortButtonIcon()

                // Aggiorna l'adapter con le nuove canzoni (già ordinate)
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

    private fun handleSongMenuAction(song: Song, action: SongAdapter.MenuAction) {
        when (action) {
            SongAdapter.MenuAction.ADD_TO_PLAYLIST -> {
                showAddToPlaylistDialog(song)
            }
            SongAdapter.MenuAction.SONG_DETAILS -> {
                showSongDetails(song)
            }
            SongAdapter.MenuAction.DELETE_FROM_DEVICE -> {
                showDeleteSongConfirmation(song)
            }
        }
    }

    private fun showAddToPlaylistDialog(song: Song) {
        // Per ora mostra un toast, dopo implementeremo le playlist
        Toast.makeText(
            this,
            "Aggiungi '${song.title}' a playlist (coming soon!)",
            Toast.LENGTH_SHORT
        ).show()
        Log.d("MainActivity", "Add to playlist requested for: ${song.title}")
    }

    private fun showSongDetails(song: Song) {
        val fileSize = formatFileSize(song.size)
        val message = """
        Titolo: ${song.title}
        Artista: ${song.artist}
        Album: ${song.album}
        Durata: ${song.getFormattedDuration()}
        Dimensione: $fileSize
        Percorso: ${song.path}
    """.trimIndent()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.song_details_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.close)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()

        Log.d("MainActivity", "Song details shown for: ${song.title}")
    }

    private fun showDeleteSongConfirmation(song: Song) {
        val message = getString(R.string.delete_song_message, song.title)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_song_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.delete_confirm)) { dialog, _ ->
                deleteSongFromDevice(song)
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()

        Log.d("MainActivity", "Delete confirmation shown for: ${song.title}")
    }

    private fun deleteSongFromDevice(song: Song) {
        try {
            val file = java.io.File(song.path)
            if (file.exists() && file.delete()) {
                // Rimuovi dalla lista locale
                songs = songs.filter { it.id != song.id }

                // Aggiorna UI
                applySorting()

                // Aggiorna anche la ricerca se attiva
                if (isSearchActive && searchInput.text.isNotEmpty()) {
                    performSearch(searchInput.text.toString())
                }

                Toast.makeText(this, "Canzone eliminata", Toast.LENGTH_SHORT).show()
                Log.d("MainActivity", "Song deleted successfully: ${song.title}")
            } else {
                Toast.makeText(this, "Errore durante l'eliminazione", Toast.LENGTH_SHORT).show()
                Log.e("MainActivity", "Failed to delete song: ${song.title}")
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Errore: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e("MainActivity", "Error deleting song: $e")
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

        // Rimuovi entrambi i listener per evitare memory leak
        musicPlayer.removeStateChangeListener(mainActivityPlayerListener)
        musicPlayer.removeQueueChangeListener(mainQueueChangeListener)

        Log.d("MainActivity", "MainActivity destroyed, all listeners removed")
    }
}