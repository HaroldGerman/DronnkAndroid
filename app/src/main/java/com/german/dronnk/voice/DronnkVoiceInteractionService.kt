package com.german.dronnk.voice

import android.service.voice.VoiceInteractionService

/**
 * Entry point used by Android when Dronnk is selected as the device's
 * digital assistant. Keeping this service lightweight is intentional;
 * voice command execution remains in HandsFreeService.
 */
class DronnkVoiceInteractionService : VoiceInteractionService()
