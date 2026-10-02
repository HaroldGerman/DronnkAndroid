package com.german.dronnk.media

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import com.german.dronnk.apps.AppResolver

class MediaRouter(private val context: Context) {

    data class Plan(
        val intent: Intent,
        val appLabel: String,
        val directPlaybackRequested: Boolean
    )

    private val apps by lazy { AppResolver(context) }

    fun plan(query: String, requestedApp: String): Plan? {
        val app = apps.find(requestedApp) ?: return null

        val playIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage(app.packageName)
            putExtra(SearchManager.QUERY, query)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
        }

        if (playIntent.resolveActivity(context.packageManager) != null) {
            return Plan(playIntent, app.label, true)
        }

        val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
            setPackage(app.packageName)
            putExtra(SearchManager.QUERY, query)
        }
        if (searchIntent.resolveActivity(context.packageManager) != null) {
            return Plan(searchIntent, app.label, false)
        }

        val launch = context.packageManager.getLaunchIntentForPackage(app.packageName) ?: return null
        return Plan(launch, app.label, false)
    }
}
