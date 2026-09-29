package com.german.dronnk.player

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
            p.setWakeMode(C.WAKE_MODE_LOCAL)
            player = p
        }
    }

    fun playLocal(context: Context, song: Song) {
        val uriText = song.localPath ?: song.url ?: return
        require(uriText.startsWith("content://") || uriText.startsWith("file://")) {
            "Dronnk solo reproduce archivos locales"
        }

        currentSong = song

        val intent = Intent(context, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_PLAY_LOCAL
            putExtra(PlaybackService.EXTRA_URI, uriText)
            putExtra(PlaybackService.EXTRA_ID, song.id)
            putExtra(PlaybackService.EXTRA_TITLE, song.titulo)
            putExtra(PlaybackService.EXTRA_ARTIST, song.canal)
            putExtra(PlaybackService.EXTRA_ARTWORK, song.thumbnail)
        }
        ContextCompat.startForegroundService(context.applicationContext, intent)
    }

    fun toggle() {
        player?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun release() {
        player?.release()
        player = null
        currentSong = null
    }
}
