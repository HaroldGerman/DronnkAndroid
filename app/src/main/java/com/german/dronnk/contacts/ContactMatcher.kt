package com.german.dronnk.contacts

import android.content.Context
import android.provider.ContactsContract
import java.text.Normalizer
import kotlin.math.min

class ContactMatcher(private val context: Context) {

    data class Match(val name: String, val phone: String, val score: Double)

    fun findBest(spoken: String): Match? {
        val query = normalize(spoken)
        if (query.isBlank()) return null

        val contacts = mutableListOf<Pair<String, String>>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val phoneIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx)?.trim().orEmpty()
                val phone = cursor.getString(phoneIdx)?.trim().orEmpty()
                if (name.isNotBlank() && phone.isNotBlank()) contacts += name to phone
            }
        }

        return contacts
            .map { (name, phone) -> Match(name, phone, similarity(query, normalize(name))) }
            .maxByOrNull { it.score }
            ?.takeIf { it.score >= 0.60 }
    }

    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a in b || b in a) return 0.92

        val pa = phonetic(a)
        val pb = phonetic(b)
        if (pa == pb) return 0.95
        if (pa in pb || pb in pa) return 0.90

        val direct = normalizedLevenshtein(a, b)
        val phonetic = normalizedLevenshtein(pa, pb)
        return maxOf(direct, phonetic)
    }

    private fun normalize(value: String): String {
        val noMarks = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return noMarks
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun phonetic(value: String): String {
        return value
            .replace("ll", "y")
            .replace("ye", "ye")
            .replace("ge", "je")
            .replace("gi", "ji")
            .replace("gue", "ge")
            .replace("gui", "gi")
            .replace("qu", "k")
            .replace("ce", "se")
            .replace("ci", "si")
            .replace("z", "s")
            .replace("v", "b")
            .replace("h", "")
            .replace("rr", "r")
            .replace(Regex("(.)\\1+"), "$1")
            .replace(" ", "")
    }

    private fun normalizedLevenshtein(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        val distance = levenshtein(a, b)
        return 1.0 - (distance.toDouble() / maxOf(a.length, b.length).toDouble())
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + cost
                )
            }
            val tmp = previous
            previous = current
            current = tmp
        }
        return previous[b.length]
    }
}
