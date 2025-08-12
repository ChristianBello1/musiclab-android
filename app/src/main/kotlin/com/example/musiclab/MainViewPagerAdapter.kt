package com.example.musiclab

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class MainViewPagerAdapter(
    fragmentActivity: FragmentActivity,
    private val songs: List<Song>
) : FragmentStateAdapter(fragmentActivity) {

    private lateinit var foldersFragment: FoldersFragment
    private lateinit var playlistsFragment: PlaylistsFragment

    override fun getItemCount(): Int = 2

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> {
                foldersFragment = FoldersFragment.newInstance(songs)
                foldersFragment
            }
            1 -> {
                playlistsFragment = PlaylistsFragment.newInstance()
                playlistsFragment
            }
            else -> throw IllegalStateException("Invalid position $position")
        }
    }

    fun getFoldersFragment(): FoldersFragment? {
        return if (::foldersFragment.isInitialized) foldersFragment else null
    }

    fun getPlaylistsFragment(): PlaylistsFragment? {
        return if (::playlistsFragment.isInitialized) playlistsFragment else null
    }

    fun updateSongs(newSongs: List<Song>) {
        getFoldersFragment()?.updateSongs(newSongs)
    }

    fun setLoginState(loggedIn: Boolean) {
        getPlaylistsFragment()?.setLoginState(loggedIn)
    }
}