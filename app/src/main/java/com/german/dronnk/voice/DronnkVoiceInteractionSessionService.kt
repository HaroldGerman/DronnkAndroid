package com.german.dronnk.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class DronnkVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        DronnkVoiceInteractionSession(this)
}

private class DronnkVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)

        val target = if (Build.VERSION.SDK_INT >= 33) {
            args?.getParcelable(DronnkVoiceInteractionService.EXTRA_TARGET_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            args?.getParcelable(DronnkVoiceInteractionService.EXTRA_TARGET_INTENT)
        }

        if (target != null) {
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            runCatching { startVoiceActivity(target) }
        }
        hide()
    }
}
