package com.german.dronnk.voice

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Minimal RecognitionService required by VoiceInteractionService metadata.
 * Dronnk currently delegates real speech recognition to Android's selected
 * recognizer from HandsFreeService.
 */
class DronnkRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(listener: Callback) = Unit

    override fun onCancel(listener: Callback) = Unit
}
