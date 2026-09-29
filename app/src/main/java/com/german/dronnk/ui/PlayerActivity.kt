package com.german.dronnk.ui

import android.content.Intent
import android.content.ClipData
import android.net.Uri
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.ui.PlayerView
import coil.load
import com.german.dronnk.R
import com.german.dronnk.data.LibraryRepository
import com.german.dronnk.download.DownloadRepository
import com.german.dronnk.model.Song
import com.german.dronnk.player.PlayerManager
import com.german.dronnk.player.PlaybackService
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var seek: SeekBar
    private lateinit var current: TextView
    private lateinit var total: TextView
    private lateinit var playPause: ImageButton
    private lateinit var favorite: ImageButton
    private var boundSongKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        applySystemInsets()

        val song = PlayerManager.currentSong
        if (song == null) {
            finish()
            return
        }

        bindCurrentSong(song)

        seek = findViewById(R.id.playerSeek)
        current = findViewById(R.id.currentTime)
        total = findViewById(R.id.totalTime)
        playPause = findViewById(R.id.btnPlayPause)
        favorite = findViewById(R.id.btnFavorite)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        playPause.setOnClickListener { PlayerManager.toggle(); refreshPlayIcon() }
        findViewById<ImageButton>(R.id.btnBack10).setOnClickListener {
            PlayerManager.player?.let { it.seekTo((it.currentPosition - 10_000L).coerceAtLeast(0L)) }
        }
        findViewById<ImageButton>(R.id.btnForward10).setOnClickListener {
            PlayerManager.player?.let { it.seekTo((it.currentPosition + 10_000L).coerceAtMost(it.duration.coerceAtLeast(0L))) }
        }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener {
            startService(
                Intent(this, PlaybackService::class.java)
                    .setAction(PlaybackService.ACTION_NEXT)
            )
        }
        favorite.setOnClickListener {
            LibraryRepository.toggleFavorite(this, song)
            refreshFavorite(song)
        }
        findViewById<ImageButton>(R.id.btnShare).setOnClickListener { shareSong(song) }
        findViewById<ImageButton>(R.id.btnVideo).setOnClickListener { downloadVideo(song) }
        findViewById<ImageButton>(R.id.btnMore).setOnClickListener { shareSong(song) }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) current.text = format(progress.toLong())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let { PlayerManager.player?.seekTo(it.progress.toLong()) }
            }
        })

        refreshFavorite(song)
        refreshPlayIcon()
        updater.run()
    }

    private fun bindCurrentSong(song: Song) {
        boundSongKey = song.id ?: song.localPath ?: song.titulo
        bindMediaVisual(song)
        findViewById<TextView>(R.id.playerTitle).text = song.titulo ?: "Canción"
        findViewById<TextView>(R.id.playerArtist).text = song.canal ?: "Dronnk"
        refreshFavorite(song)
    }

    private fun refreshFavorite(song: Song) {
        val active = LibraryRepository.isFavorite(this, song)
        favorite.setImageResource(if (active) R.drawable.ic_heart_solid else R.drawable.ic_heart_outline)
        favorite.setColorFilter(if (active) Color.parseColor("#9A67FF") else Color.parseColor("#9A9AA6"))
    }

    private fun refreshPlayIcon() {
        playPause.setImageResource(if (PlayerManager.player?.isPlaying == true) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
    }

    private val updater = object : Runnable {
        override fun run() {
            PlayerManager.currentSong?.let { active ->
                val key = active.id ?: active.localPath ?: active.titulo
                if (key != boundSongKey) bindCurrentSong(active)
            }
            PlayerManager.player?.let { p ->
                val duration = p.duration.takeIf { it > 0 } ?: 0L
                val position = p.currentPosition.coerceAtLeast(0L)
                if (duration > 0) seek.max = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                seek.progress = position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                current.text = format(position)
                total.text = format(duration)
                refreshPlayIcon()
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun downloadVideo(song: Song) {
        val source = song.sourceUrl ?: song.url
        if (source.isNullOrBlank() || !source.startsWith("http")) {
            Toast.makeText(this, "No está disponible la URL original del video.", Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(this, "Preparando video…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            DownloadRepository.downloadVideo(this@PlayerActivity, song)
                .onSuccess { uri ->
                    val localVideo = DownloadRepository.asDownloadedVideo(song, uri)
                    LibraryRepository.addHistory(this@PlayerActivity, localVideo)
                    PlayerManager.playLocal(this@PlayerActivity, localVideo)
                    bindMediaVisual(localVideo)
                    findViewById<TextView>(R.id.playerTitle).text = localVideo.titulo ?: "Canción"
                    findViewById<TextView>(R.id.playerArtist).text = localVideo.canal ?: "Dronnk"
                    Toast.makeText(this@PlayerActivity, "Video guardado y reproduciendo", Toast.LENGTH_LONG).show()
                }
                .onFailure {
                    Toast.makeText(this@PlayerActivity, "No se pudo descargar el video: ${it.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun shareSong(song: Song) {
        val local = song.localPath ?: song.url
        if (!local.isNullOrBlank() && (local.startsWith("content://") || local.startsWith("file://"))) {
            val uri = Uri.parse(local)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "audio/mpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "${song.titulo ?: "Canción"} — ${song.canal ?: "Dronnk"}")
                clipData = ClipData.newRawUri("Dronnk audio", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Compartir canción"))
            return
        }

        val source = song.sourceUrl ?: song.url
        val text = buildString {
            append(song.titulo ?: "Canción")
            song.canal?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
            source?.takeIf { it.startsWith("http") }?.let { append("\n").append(it) }
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Compartir canción"))
    }

    private fun bindMediaVisual(song: Song) {
        val playerView = findViewById<PlayerView>(R.id.playerVideo)
        val cover = findViewById<ImageView>(R.id.playerCover)

        if (song.mediaType == "video") {
            cover.visibility = android.view.View.GONE
            playerView.visibility = android.view.View.VISIBLE
            playerView.player = PlayerManager.player
        } else {
            playerView.player = null
            playerView.visibility = android.view.View.GONE
            cover.visibility = android.view.View.VISIBLE
            cover.load(song.thumbnail) {
                placeholder(R.drawable.dronnk_app_icon)
                error(R.drawable.dronnk_app_icon)
                crossfade(true)
            }
        }
    }

    private fun applySystemInsets() {
        val root = findViewById<android.view.View>(R.id.playerRoot)
        val density = resources.displayMetrics.density
        val base = (18f * density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                base,
                base + bars.top,
                base,
                base + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun format(ms: Long): String {
        val totalSec = ms / 1000
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }

    override fun onDestroy() {
        handler.removeCallbacks(updater)
        super.onDestroy()
    }
}
