package com.example.musiclab

import android.util.Log
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class MainViewPagerAdapter(
    fragmentActivity: FragmentActivity,
    private var songs: List<Song>
) : FragmentStateAdapter(fragmentActivity) {

    private var foldersFragment: FoldersFragment? = null
    private var playlistsFragment: PlaylistsFragment? = null

    override fun getItemCount(): Int = 2

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> {
                foldersFragment = FoldersFragment.newInstance(songs)
                foldersFragment!!
            }
            1 -> {
                playlistsFragment = PlaylistsFragment.newInstance()
                playlistsFragment!!
            }
            else -> throw IllegalStateException("Invalid position $position")
        }
    }

    fun getFoldersFragment(): FoldersFragment? {
        return foldersFragment
    }

    fun getPlaylistsFragment(): PlaylistsFragment? {
        return playlistsFragment
    }

    // AGGIORNATO: Ora aggiorna la variabile locale e i fragment
    fun updateSongs(newSongs: List<Song>) {
        Log.d("MainViewPagerAdapter", "🔄 Updating songs: ${newSongs.size}")

        // Aggiorna la lista locale
        songs = newSongs

        // Aggiorna il fragment delle cartelle se esiste
        foldersFragment?.updateSongs(newSongs)

        Log.d("MainViewPagerAdapter", "✅ Songs updated in adapter")
    }

    // AGGIORNATO: Ora accetta anche userId per passarlo al PlaylistsFragment
    fun setLoginState(loggedIn: Boolean, userId: String = "") {
        playlistsFragment?.setLoginState(loggedIn, userId)
        Log.d("MainViewPagerAdapter", "Login state updated: $loggedIn, userId: $userId")
    }
}