package com.german.dronnk.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.german.dronnk.apps.AppResolver
import com.german.dronnk.contacts.ContactMatcher

class CommunicationPlanner(private val context: Context) {

    data class Plan(
        val intent: Intent,
        val resolvedPerson: String = "",
        val appLabel: String = "",
        val description: String = ""
    )

    private val contacts by lazy { ContactMatcher(context) }
    private val apps by lazy { AppResolver(context) }

    fun phoneCall(spokenTarget: String): Plan? {
        val direct = spokenTarget.filter { it.isDigit() || it == '+' }
        val number: String
        val label: String
        if (direct.length >= 5 && direct.length >= spokenTarget.length - 2) {
            number = direct
            label = spokenTarget
        } else {
            val match = contacts.findBest(spokenTarget) ?: return null
            number = match.phone
            label = match.name
        }
        return Plan(
            intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(number)}")),
            resolvedPerson = label,
            appLabel = "Teléfono",
            description = "Llamando a $label"
        )
    }

    fun whatsapp(spokenTarget: String, message: String = ""): Plan? {
        val match = contacts.findBest(spokenTarget) ?: return null
        var digits = match.phone.filter(Char::isDigit)
        if (!match.phone.trim().startsWith("+") && digits.length == 9) digits = "51$digits"
        val uri = Uri.Builder()
            .scheme("https")
            .authority("wa.me")
            .appendPath(digits)
            .apply { if (message.isNotBlank()) appendQueryParameter("text", message) }
            .build()
        return Plan(
            intent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp"),
            resolvedPerson = match.name,
            appLabel = "WhatsApp",
            description = if (message.isBlank()) "Abriendo el chat de ${match.name}" else "Preparando mensaje para ${match.name}"
        )
    }

    fun appMessage(appName: String, message: String): Plan? {
        val app = apps.find(appName) ?: return null
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
            setPackage(app.packageName)
        }
        return Plan(
            intent = share,
            appLabel = app.label,
            description = "Preparando mensaje en ${app.label}"
        )
    }

    fun openApp(appName: String): Plan? {
        val app = apps.find(appName) ?: return null
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName) ?: return null
        return Plan(intent = intent, appLabel = app.label, description = "Abriendo ${app.label}")
    }
}
