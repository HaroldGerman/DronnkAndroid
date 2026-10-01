package com.german.dronnk.youtube

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.german.dronnk.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest

class YouTubeSearchClient(private val context: Context) {

    private val client = OkHttpClient()

    fun findFirstVideoId(query: String): Result<String> {
        val apiKey = BuildConfig.YOUTUBE_API_KEY.trim()
        if (apiKey.isBlank()) {
            return Result.failure(IllegalStateException("YOUTUBE_API_KEY no configurada"))
        }
        if (query.isBlank()) {
            return Result.failure(IllegalArgumentException("Búsqueda vacía"))
        }

        val encoded = java.net.URLEncoder.encode(query, Charsets.UTF_8.name())
        val url = "https://www.googleapis.com/youtube/v3/search" +
            "?part=snippet&type=video&maxResults=1&safeSearch=none&q=$encoded&key=$apiKey"

        val requestBuilder = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("X-Android-Package", context.packageName)

        signingSha1()?.let { requestBuilder.header("X-Android-Cert", it) }

        return runCatching {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("YouTube API ${response.code}: $body")
                }
                val root = JSONObject(body)
                val items = root.optJSONArray("items")
                val videoId = items
                    ?.optJSONObject(0)
                    ?.optJSONObject("id")
                    ?.optString("videoId")
                    .orEmpty()
                if (videoId.isBlank()) throw NoSuchElementException("No se encontró un video")
                videoId
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun signingSha1(): String? = runCatching {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }

        val signatureBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo
                ?.apkContentsSigners
                ?.firstOrNull()
                ?.toByteArray()
        } else {
            packageInfo.signatures?.firstOrNull()?.toByteArray()
        } ?: return null

        MessageDigest.getInstance("SHA-1")
            .digest(signatureBytes)
            .joinToString("") { "%02X".format(it) }
    }.getOrNull()
}
