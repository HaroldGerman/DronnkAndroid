package com.german.dronnk.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
import com.german.dronnk.R
import com.german.dronnk.data.RecommendationRepository
import com.german.dronnk.download.DownloadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.german.dronnk.ui.MainActivity

class PlaybackService : MediaSessionService() {

    companion object {
        const val ACTION_PLAY_LOCAL = "com.german.dronnk.action.PLAY_LOCAL"
        const val ACTION_TOGGLE = "com.german.dronnk.action.TOGGLE"
        const val ACTION_STOP = "com.german.dronnk.action.STOP"
        const val ACTION_NEXT = "com.german.dronnk.action.NEXT"

        const val EXTRA_URI = "uri"
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_ARTWORK = "artwork"

        private const val CHANNEL_ID = "dronnk_playback"
        private const val NOTIFICATION_ID = 2401
    }

    private var session: MediaSession? = null
    private lateinit var player: Player
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var advancing = false

    override fun onCreate() {
        super.onCreate()
        val basePlayer = PlayerManager.getOrCreate(this)
        player = object : ForwardingPlayer(basePlayer) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands()
                    .buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .build()
            }

            override fun seekToNextMediaItem() {
                playNext()
            }

            override fun seekToNext() {
                playNext()
            }
        }
        session = MediaSession.Builder(this, player).build()
        createChannel()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = updateNotification()
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = updateNotification()
            override fun onPlaybackStateChanged(playbackState: Int) {
                updateNotification()
                if (playbackState == Player.STATE_ENDED) {
                    playNext()
                }
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_LOCAL -> {
                val uri = intent.getStringExtra(EXTRA_URI) ?: return START_NOT_STICKY
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Canción"
                val artist = intent.getStringExtra(EXTRA_ARTIST) ?: "Dronnk"
                val artwork = intent.getStringExtra(EXTRA_ARTWORK)

                val item = MediaItem.Builder()
                    .setMediaId(intent.getStringExtra(EXTRA_ID) ?: uri)
                    .setUri(uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(title)
                            .setArtist(artist)
                            .setAlbumTitle("Dronnk")
                            .setArtworkUri(
                                artwork?.takeIf { it.startsWith("http") }
                                    ?.let(android.net.Uri::parse)
                            )
                            .build()
                    )
                    .build()

                player.setMediaItem(item)
                player.prepare()

                // Android exige publicar la notificación inmediatamente al arrancar
                // un foreground service.
                startForeground(NOTIFICATION_ID, buildNotification())
                player.play()
                updateNotification()
            }

            ACTION_TOGGLE -> {
                if (player.isPlaying) player.pause() else player.play()
                updateNotification()
            }

            ACTION_NEXT -> {
                playNext()
            }

            ACTION_STOP -> {
                player.pause()
                player.clearMediaItems()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    private fun buildNotification(): Notification {
        val mediaSession = requireNotNull(session)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PlaybackService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, PlaybackService::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val metadata = player.mediaMetadata
        val playing = player.isPlaying

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(metadata.title ?: "Dronnk")
            .setContentText(metadata.artist ?: "Reproduciendo")
            .setContentIntent(openApp)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pausar" else "Reproducir",
                toggleIntent
            )
            .addAction(R.drawable.ic_next, "Siguiente", nextIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cerrar", stopIntent)
            .setStyle(
                MediaStyleNotificationHelper.MediaStyle(mediaSession)
                    .setShowActionsInCompactView(0, 1)
            )
            .build()
    }

    private fun playNext() {
        if (advancing) return
        val current = PlayerManager.currentSong ?: return
        advancing = true

        serviceScope.launch {
            try {
                val candidate = RecommendationRepository.next(current)
                if (candidate != null) {
                    val local = DownloadRepository.preferredLocalMedia(this@PlaybackService, candidate)
                        ?: DownloadRepository.ensureLocalMp3(this@PlaybackService, candidate).getOrNull()

                    if (local != null) {
                        PlayerManager.playLocal(this@PlaybackService, local)
                    }
                }
            } finally {
                advancing = false
            }
        }
    }

    private fun updateNotification() {
        if (session == null || player.mediaItemCount == 0) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Reproducción",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Controles de reproducción de Dronnk"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Cerrar Dronnk desde la vista de apps recientes significa cerrar
        // también la reproducción. Minimizar/bloquear pantalla no dispara esto.
        runCatching {
            player.pause()
            player.clearMediaItems()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Si Android destruye el servicio (por ejemplo al cerrar Dronnk desde
        // recientes), también debemos detener y liberar el ExoPlayer singleton.
        runCatching {
            player.pause()
            player.clearMediaItems()
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        PlayerManager.release()

        serviceScope.cancel()
        session?.release()
        session = null
        super.onDestroy()
    }
}
