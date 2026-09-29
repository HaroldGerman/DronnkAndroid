package com.german.dronnk.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
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
            p.repeatMode = Player.REPEAT_MODE_OFF
            player = p
        }
    }

    fun playLocal(context: Context, song: Song) {
        val uriText = song.localPath ?: song.url ?: return
        require(uriText.startsWith("content://") || uriText.startsWith("file://")) {
            "Dronnk solo reproduce archivos locales"
        }

        val p = getOrCreate(context)
        val media = MediaItem.Builder()
            .setMediaId(song.id ?: uriText)
            .setUri(Uri.parse(uriText))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.titulo ?: "Canción")
                    .setArtist(song.canal ?: "Dronnk")
                    .setAlbumTitle("Dronnk")
                    .setArtworkUri(song.thumbnail?.takeIf { it.startsWith("http") }?.let(Uri::parse))
                    .build()
            )
            .build()

        currentSong = song
        p.setMediaItem(media)
        p.prepare()
        p.play()

        // El servicio se inicia DESPUÉS de poner el player en reproducción.
        // MediaSessionService se encarga de la notificación multimedia/lockscreen.
        context.startService(Intent(context, PlaybackService::class.java))
    }

    fun toggle() {
        player?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }

    fun release() {
        player?.release()
        player = null
        currentSong = null
    }
}
