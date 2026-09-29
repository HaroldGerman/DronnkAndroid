package com.german.dronnk.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import com.german.dronnk.model.Song

object PlayerManager {
    var player: ExoPlayer? = null
        private set
    var currentSong: Song? = null
        private set

    fun getOrCreate(context: Context): ExoPlayer {
        return player ?: ExoPlayer.Builder(context.applicationContext).build().also { p ->
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            p.setHandleAudioBecomingNoisy(true)
            player = p
        }
    }

    fun playLocal(context: Context, song: Song) {
        val uriText = song.localPath ?: song.url ?: return
        require(uriText.startsWith("content://") || uriText.startsWith("file://")) {
            "Dronnk solo reproduce archivos locales en este flujo"
        }

        ContextCompat.startForegroundService(
            context,
            Intent(context, PlaybackService::class.java)
        )

        val p = getOrCreate(context)
        val media = MediaItem.Builder()
            .setUri(Uri.parse(uriText))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.titulo ?: "Canción")
                    .setArtist(song.canal ?: "Dronnk")
                    .setArtworkUri(song.thumbnail?.let(Uri::parse))
                    .build()
            )
            .build()
        currentSong = song
        p.setMediaItem(media)
        p.prepare()
        p.play()
    }

    fun toggle() {
        player?.let { if (it.isPlaying) it.pause() else it.play() }
    }
}
