package com.german.dronnk.data

import android.content.Context
import com.german.dronnk.model.Song
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

data class DronnkPlaylist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val songs: MutableList<Song> = mutableListOf()
)

object LibraryRepository {
    private const val PREFS = "dronnk_library"
    private const val FAVORITES = "favorites"
    private const val HISTORY = "history"
    private const val SEARCHES = "searches"
    private const val PLAYLISTS = "playlists"

    fun favorites(context: Context): List<Song> = readSongs(context, FAVORITES)
    fun history(context: Context): List<Song> = readSongs(context, HISTORY)

    fun isFavorite(context: Context, song: Song): Boolean {
        val key = songKey(song)
        return favorites(context).any { songKey(it) == key }
    }

    fun toggleFavorite(context: Context, song: Song): Boolean {
        val list = favorites(context).toMutableList()
        val key = songKey(song)
        val idx = list.indexOfFirst { songKey(it) == key }
        return if (idx >= 0) {
            list.removeAt(idx)
            write(context, FAVORITES, list)
            false
        } else {
            list.add(0, song.copy(isFavorite = true))
            write(context, FAVORITES, list)
            true
        }
    }

    fun addHistory(context: Context, song: Song) {
        val list = history(context).toMutableList()
        val key = songKey(song)
        list.removeAll { songKey(it) == key }
        list.add(0, song)
        write(context, HISTORY, list.take(50))
    }

    fun addSearch(context: Context, query: String) {
        val clean = query.trim()
        if (clean.isBlank()) return
        val list = recentSearches(context).toMutableList()
        list.removeAll { it.equals(clean, ignoreCase = true) }
        list.add(0, clean)
        write(context, SEARCHES, list.take(10))
    }

    fun recentSearches(context: Context): List<String> {
        val json = prefs(context).getString(SEARCHES, null) ?: return emptyList()
        val type = object : TypeToken<List<String>>() {}.type
        return runCatching { Gson().fromJson<List<String>>(json, type) }.getOrDefault(emptyList())
    }

    fun playlists(context: Context): List<DronnkPlaylist> {
        val json = prefs(context).getString(PLAYLISTS, null) ?: return emptyList()
        val type = object : TypeToken<List<DronnkPlaylist>>() {}.type
        return runCatching { Gson().fromJson<List<DronnkPlaylist>>(json, type) }.getOrDefault(emptyList())
    }

    fun createPlaylist(context: Context, name: String): DronnkPlaylist {
        val list = playlists(context).toMutableList()
        val playlist = DronnkPlaylist(name = name.trim())
        list.add(0, playlist)
        write(context, PLAYLISTS, list)
        return playlist
    }

    fun addToPlaylist(context: Context, playlistId: String, song: Song) {
        val list = playlists(context).toMutableList()
        val index = list.indexOfFirst { it.id == playlistId }
        if (index < 0) return
        val playlist = list[index]
        playlist.songs.removeAll { songKey(it) == songKey(song) }
        playlist.songs.add(0, song)
        write(context, PLAYLISTS, list)
    }

    private fun songKey(song: Song): String = song.id ?: song.url ?: song.titulo.orEmpty()
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readSongs(context: Context, key: String): List<Song> {
        val json = prefs(context).getString(key, null) ?: return emptyList()
        val type = object : TypeToken<List<Song>>() {}.type
        return runCatching { Gson().fromJson<List<Song>>(json, type) }.getOrDefault(emptyList())
    }

    private fun write(context: Context, key: String, value: Any) {
        prefs(context).edit().putString(key, Gson().toJson(value)).apply()
    }
}
