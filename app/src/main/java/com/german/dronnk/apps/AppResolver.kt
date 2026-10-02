package com.german.dronnk.apps

import android.content.Context
import android.content.Intent
import java.text.Normalizer
import java.util.Locale

class AppResolver(private val context: Context) {

    data class Match(val label: String, val packageName: String, val score: Double)

    private val aliases = mapOf(
        "wsp" to "whatsapp",
        "wa" to "whatsapp",
        "ig" to "instagram",
        "insta" to "instagram",
        "fb" to "facebook",
        "yt" to "youtube",
        "yt music" to "youtube music",
        "spoty" to "spotify"
    )

    fun find(name: String): Match? {
        val wanted = normalize(aliases[normalize(name)] ?: name)
        if (wanted.isBlank()) return null

        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(launcher, 0)
            .map { info ->
                val label = info.loadLabel(context.packageManager).toString()
                val normalized = normalize(label)
                Match(label, info.activityInfo.packageName, similarity(wanted, normalized))
            }
            .maxByOrNull { it.score }
            ?.takeIf { it.score >= 0.58 }
    }

    fun launchIntent(name: String): Intent? =
        find(name)?.packageName?.let(context.packageManager::getLaunchIntentForPackage)

    private fun normalize(value: String): String {
        val noMarks = Normalizer.normalize(value.lowercase(Locale.getDefault()), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return noMarks.replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a in b || b in a) return 0.94
        val distance = levenshtein(a, b)
        return 1.0 - distance.toDouble() / maxOf(a.length, b.length).coerceAtLeast(1)
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + cost)
            }
            val tmp = previous
            previous = current
            current = tmp
        }
        return previous[b.length]
    }
}
