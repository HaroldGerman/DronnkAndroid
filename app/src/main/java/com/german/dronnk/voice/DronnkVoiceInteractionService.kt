package com.german.dronnk.voice

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession

/**
 * Android binds this service only when Dronnk is selected as the device's
 * digital assistant. External activities requested by hands-free mode are
 * routed through a VoiceInteractionSession because ordinary background
 * Service.startActivity() calls are restricted by modern Android.
 */
class DronnkVoiceInteractionService : VoiceInteractionService() {

    companion object {
        const val EXTRA_TARGET_INTENT = "dronnk_target_intent"

        @Volatile
        private var activeService: DronnkVoiceInteractionService? = null

        /**
         * Returns false when Dronnk is not currently the system assistant.
         */
        fun launchExternal(intent: Intent): Boolean {
            val service = activeService ?: return false
            val args = Bundle().apply {
                putParcelable(EXTRA_TARGET_INTENT, intent)
            }
            return runCatching {
                service.showSession(args, VoiceInteractionSession.SHOW_WITH_ASSIST)
                true
            }.getOrDefault(false)
        }
    }

    override fun onReady() {
        super.onReady()
        activeService = this
    }

    override fun onShutdown() {
        if (activeService === this) activeService = null
        super.onShutdown()
    }
}
