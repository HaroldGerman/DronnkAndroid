package com.german.dronnk.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.german.dronnk.R
import com.german.dronnk.model.Song
import com.german.dronnk.network.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

object DownloadRepository {
    private const val DOWNLOAD_CHANNEL = "dronnk_downloads"
    private const val VIDEO_NOTIFICATION_ID = 4102

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    suspend fun ensureLocalMp3(context: Context, song: Song): Result<Song> = withContext(Dispatchers.IO) {
        runCatching {
            findExistingAudio(context, song)?.let {
                return@runCatching song.copy(isDownloaded = true, localPath = it.toString(), url = it.toString())
            }

            val source = requireNotNull(song.sourceUrl ?: song.url) { "La canción no tiene URL" }
            val prepared = ApiClient.api.prepararMp3(source)
            require(prepared.status == "success" && !prepared.url.isNullOrBlank()) {
                prepared.message ?: "No se pudo preparar el MP3"
            }

            val finalTitle = prepared.titulo ?: song.titulo ?: "Dronnk"
            val uri = saveMedia(
                context = context,
                url = prepared.url,
                displayName = "${safeName(finalTitle)}.mp3",
                mime = "audio/mpeg",
                relativePath = "Music/Dronnk",
                collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            )
            song.copy(
                titulo = finalTitle,
                canal = prepared.canal ?: song.canal,
                thumbnail = prepared.thumbnail ?: song.thumbnail,
                duracion = prepared.duracion ?: song.duracion,
                isDownloaded = true,
                localPath = uri.toString(),
                url = uri.toString(),
                sourceUrl = song.sourceUrl ?: source
            )
        }
    }

    suspend fun downloadVideo(context: Context, song: Song): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            ensureDownloadChannel(context)
            val source = requireNotNull(song.sourceUrl ?: song.url) { "La canción no tiene URL original" }
            require(source.startsWith("http")) { "El video requiere la URL original" }

            showVideoProgress(context, song.titulo ?: "Video", 0, true)
            val prepared = ApiClient.api.prepararVideo(source)
            require(prepared.status == "success" && !prepared.url.isNullOrBlank()) {
                prepared.message ?: "No se pudo preparar el video"
            }

            val title = prepared.titulo ?: song.titulo ?: "Dronnk"
            val uri = saveMedia(
                context = context,
                url = prepared.url,
                displayName = "${safeName(title)}.mp4",
                mime = "video/mp4",
                relativePath = "Movies/Dronnk",
                collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                onProgress = { percent -> showVideoProgress(context, title, percent, false) }
            )
            showVideoComplete(context, title)
            uri
        }.onFailure {
            showVideoFailed(context, song.titulo ?: "Video")
        }
    }

    fun downloadedAudio(context: Context): List<Song> {
        val result = mutableListOf<Song>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION
        )
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
        } else null
        val args = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) arrayOf("Music/Dronnk%") else null
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    val durationMs = c.getLong(durationCol)
                    result += Song(
                        id = "local-$id",
                        titulo = c.getString(titleCol) ?: "Audio Dronnk",
                        canal = c.getString(artistCol)?.takeUnless { it == "<unknown>" } ?: "Dronnk",
                        duracion = formatDuration(durationMs),
                        url = uri.toString(),
                        localPath = uri.toString(),
                        isDownloaded = true
                    )
                }
            }
        }
        return result
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return ""
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    private fun safeName(name: String): String =
        name.replace(Regex("[\\/:*?\"<>|]"), "_").take(100)

    private fun findExistingAudio(context: Context, song: Song): Uri? {
        val title = safeName(song.titulo ?: return null)
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME)
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.DISPLAY_NAME}=?",
            arrayOf("$title.mp3"),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
            }
        }
        return null
    }

    private fun saveMedia(
        context: Context,
        url: String,
        displayName: String,
        mime: String,
        relativePath: String,
        collection: Uri,
        onProgress: ((Int) -> Unit)? = null
    ): Uri {
        val response = http.newCall(Request.Builder().url(url).build()).execute()
        require(response.isSuccessful) { "Descarga HTTP ${response.code}" }
        val body = requireNotNull(response.body)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = context.contentResolver.insert(collection, values)
            ?: error("No se pudo crear el archivo local")

        try {
            val total = body.contentLength()
            context.contentResolver.openOutputStream(uri)?.use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        downloaded += read
                        if (onProgress != null && total > 0) {
                            val percent = ((downloaded.toDouble() / total.toDouble()) * 100.0)
                                .roundToInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            } ?: error("No se pudo escribir el archivo")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }
            return uri
        } catch (e: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw e
        }
    }

    private fun ensureDownloadChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    DOWNLOAD_CHANNEL,
                    "Descargas",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun showVideoProgress(context: Context, title: String, percent: Int, preparing: Boolean) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(if (preparing) "Preparando video" else "Descargando video")
            .setContentText(if (preparing) title else "$title · $percent%")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent, preparing)
            .build()
        manager.notify(VIDEO_NOTIFICATION_ID, notification)
    }

    private fun showVideoComplete(context: Context, title: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Video descargado")
            .setContentText(title)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        manager.notify(VIDEO_NOTIFICATION_ID, notification)
    }

    private fun showVideoFailed(context: Context, title: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("No se pudo descargar el video")
            .setContentText(title)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        manager.notify(VIDEO_NOTIFICATION_ID, notification)
    }
}
