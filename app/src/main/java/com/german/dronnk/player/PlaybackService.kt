package com.german.dronnk.player

import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = PlayerManager.getOrCreate(this)
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // No detener la música cuando el usuario quita Dronnk de recientes.
        // El servicio se mantiene mientras exista reproducción activa.
        val player = PlayerManager.player
        if (player == null || (player.playbackState == Player.STATE_IDLE && !player.playWhenReady)) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}
