package com.german.dronnk.communication

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.german.dronnk.apps.AppResolver
import com.german.dronnk.automation.WhatsAppAccessibilityService
import com.german.dronnk.contacts.ContactMatcher
import java.util.Locale

class CommunicationRouter(private val context: Context) {

    data class Plan(
        val intent: Intent,
        val appLabel: String,
        val targetLabel: String,
        val messagePrepared: Boolean,
        val targetResolved: Boolean
    )

    private val apps by lazy { AppResolver(context) }
    private val contacts by lazy { ContactMatcher(context) }

    fun openChat(requestedApp: String, spokenTarget: String): Plan? {
        return prepareMessage(requestedApp, spokenTarget, "")
    }

    fun prepareMessage(requestedApp: String, spokenTarget: String, message: String): Plan? {
        val appName = requestedApp.ifBlank { "whatsapp" }
        val normalized = appName.lowercase(Locale.getDefault())

        if (normalized == "default" || normalized.contains("whatsapp") || normalized == "wsp" || normalized == "wa") {
            return whatsappPlan(spokenTarget, message)
        }

        val app = apps.find(appName) ?: return null
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
            setPackage(app.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (message.isNotBlank() && share.resolveActivity(context.packageManager) != null) {
            return Plan(
                intent = share,
                appLabel = app.label,
                targetLabel = spokenTarget,
                messagePrepared = true,
                targetResolved = false
            )
        }

        val launch = context.packageManager.getLaunchIntentForPackage(app.packageName) ?: return null
        return Plan(
            intent = launch,
            appLabel = app.label,
            targetLabel = spokenTarget,
            messagePrepared = false,
            targetResolved = false
        )
    }

    private fun whatsappPlan(spokenTarget: String, message: String): Plan? {
        val contact = contacts.findBest(spokenTarget) ?: return null
        var digits = contact.phone.filter(Char::isDigit)
        if (!contact.phone.trim().startsWith("+") && digits.length == 9) digits = "51$digits"
        if (digits.isBlank()) return null

        if (message.isNotBlank()) {
            WhatsAppAccessibilityService.queueSend(message)
        }

        val uri = Uri.Builder()
            .scheme("https")
            .authority("wa.me")
            .appendPath(digits)
            .apply { if (message.isNotBlank()) appendQueryParameter("text", message) }
            .build()

        val normal = Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp")
        val business = Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp.w4b")
        val intent = when {
            normal.resolveActivity(context.packageManager) != null -> normal
            business.resolveActivity(context.packageManager) != null -> business
            else -> return null
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return Plan(
            intent = intent,
            appLabel = "WhatsApp",
            targetLabel = contact.name,
            messagePrepared = message.isNotBlank(),
            targetResolved = true
        )
    }
}
