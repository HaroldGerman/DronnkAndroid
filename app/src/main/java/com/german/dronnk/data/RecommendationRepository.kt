package com.german.dronnk.data

import com.german.dronnk.model.Song
import com.german.dronnk.network.ApiClient
import java.text.Normalizer
import java.util.Locale

object RecommendationRepository {

    suspend fun next(current: Song): Song? {
        val artist = current.canal?.trim().orEmpty()
        val currentId = current.id
        val currentTitle = canonicalTitle(current.titulo.orEmpty())

        val queries = buildList {
            if (artist.isNotBlank() && !artist.equals("Dronnk", true)) {
                add(artist)
                add("$artist canciones")
            }

            val titleWords = current.titulo.orEmpty()
                .split(" ")
                .filter { it.length >= 4 }
                .take(2)
                .joinToString(" ")

            if (titleWords.isNotBlank()) {
                add("$titleWords music")
            }
        }.distinct()

        for (query in queries) {
            val response = runCatching { ApiClient.api.buscar(query) }.getOrNull() ?: continue

            val candidates = response.canciones
                .filter { candidate ->
                    val differentId = currentId == null || candidate.id != currentId
                    val candidateTitle = canonicalTitle(candidate.titulo.orEmpty())
                    val differentTitle = candidateTitle.isNotBlank() &&
                        candidateTitle != currentTitle &&
                        !sameBaseTitle(candidateTitle, currentTitle)

                    differentId && differentTitle
                }
                .sortedByDescending { candidate ->
                    if (
                        artist.isNotBlank() &&
                        candidate.canal.orEmpty().contains(artist, ignoreCase = true)
                    ) 1 else 0
                }

            if (candidates.isNotEmpty()) {
                return candidates.first()
            }
        }

        return null
    }

    private fun canonicalTitle(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("""\([^)]*\)|\[[^]]*]"""), " ")
            .replace(
                Regex(
                    """\b(official|video|audio|lyrics?|lyric|visualizer|music|hd|4k|remaster(ed)?|live|version|clean|explicit)\b"""
                ),
                " "
            )
            .replace(Regex("""\s+"""), " ")
            .trim()

        return normalized
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun sameBaseTitle(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length < 4 || b.length < 4) return false
        return a.contains(b) || b.contains(a)
    }
}
