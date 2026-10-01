package com.german.dronnk.update

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import com.german.dronnk.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class JarvisRelease(
    val versionName: String,
    val downloadUrl: String,
    val fileName: String
)

object AppUpdateManager {
    private const val RELEASE_API =
        "https://api.github.com/repos/HaroldGerman/DronnkAndroid/releases/latest"
    const val PREFS = "jarvis_updates"
    const val KEY_DOWNLOAD_ID = "download_id"

    private val http = OkHttpClient()

    suspend fun checkLatest(context: Context): Result<JarvisRelease?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(RELEASE_API)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "Jarvis-Android")
                    .build()

                http.newCall(request).execute().use { response ->
                    if (response.code == 404) return@runCatching null
                    require(response.isSuccessful) {
                        "GitHub respondió HTTP ${response.code}"
                    }

                    val body = requireNotNull(response.body).string()
                    val json = JSONObject(body)
                    val tag = json.optString("tag_name")
                        .removePrefix("v")
                        .removePrefix("V")
                        .trim()

                    if (!isNewer(tag, BuildConfig.VERSION_NAME)) {
                        return@runCatching null
                    }

                    val assets = json.optJSONArray("assets")
                        ?: error("La versión no tiene APK")

                    var apkUrl: String? = null
                    var apkName: String? = null

                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url")
                            apkName = name
                            break
                        }
                    }

                    require(!apkUrl.isNullOrBlank()) {
                        "La versión publicada no contiene un APK"
                    }

                    JarvisRelease(
                        versionName = tag,
                        downloadUrl = apkUrl!!,
                        fileName = apkName ?: "Jarvis-$tag.apk"
                    )
                }
            }
        }

    fun startDownload(activity: Activity, release: JarvisRelease) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            Toast.makeText(
                activity,
                "Activa “Instalar apps desconocidas” para Jarvis. La descarga continuará.",
                Toast.LENGTH_LONG
            ).show()

            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
            )
        }

        val request = DownloadManager.Request(Uri.parse(release.downloadUrl))
            .setTitle("Actualizando Jarvis")
            .setDescription("Jarvis ${release.versionName}")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                release.fileName
            )

        val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = manager.enqueue(request)

        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_DOWNLOAD_ID, id)
            .apply()

        Toast.makeText(
            activity,
            "Descargando Jarvis ${release.versionName}",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val l = local.split(".").map { it.toIntOrNull() ?: 0 }
        val size = maxOf(r.size, l.size)

        for (i in 0 until size) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }
}
