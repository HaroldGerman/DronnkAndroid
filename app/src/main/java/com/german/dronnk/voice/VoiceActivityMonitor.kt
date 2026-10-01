package com.german.dronnk.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlin.math.max

/**
 * Lightweight always-on voice activity detector.
 *
 * It does not perform speech recognition and therefore does not trigger the
 * system recognizer tones. SpeechRecognizer is started only after probable
 * human speech is detected.
 */
class VoiceActivityMonitor(
    context: Context,
    private val onVoiceDetected: () -> Unit
) {
    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val SILENT_THRESHOLD = 1_400.0
        private const val MUSIC_THRESHOLD = 5_500.0
        private const val REQUIRED_VOICE_FRAMES = 4
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile private var running = false
    @Volatile private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null

    fun start() {
        if (running) return

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return

        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuffer, SAMPLE_RATE / 2)
            )
        }.getOrNull() ?: return

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }

        audioRecord = record
        running = true

        worker = Thread({ monitor(record) }, "DronnkVoiceActivity").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        val record = audioRecord
        audioRecord = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        worker = null
    }

    private fun monitor(record: AudioRecord) {
        val echo = if (AcousticEchoCanceler.isAvailable()) {
            runCatching { AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull()
        } else null
        val noise = if (NoiseSuppressor.isAvailable()) {
            runCatching { NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull()
        } else null

        val buffer = ShortArray(640)
        var noiseFloor = 450.0
        var voiceFrames = 0

        try {
            record.startRecording()
            while (running && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val count = record.read(buffer, 0, buffer.size)
                if (count <= 0) continue

                var sum = 0.0
                for (i in 0 until count) sum += kotlin.math.abs(buffer[i].toInt()).toDouble()
                val level = sum / count

                val musicActive = audioManager.isMusicActive
                val base = if (musicActive) MUSIC_THRESHOLD else SILENT_THRESHOLD
                val adaptive = noiseFloor * if (musicActive) 4.5 else 3.5
                val threshold = max(base, adaptive)

                if (level >= threshold) {
                    voiceFrames++
                } else {
                    voiceFrames = 0
                    noiseFloor = (noiseFloor * 0.97) + (level * 0.03)
                }

                if (voiceFrames >= REQUIRED_VOICE_FRAMES) {
                    running = false
                    onVoiceDetected()
                    break
                }
            }
        } catch (_: Throwable) {
            // HandsFreeService will restart the monitor if necessary.
        } finally {
            runCatching { record.stop() }
            echo?.release()
            noise?.release()
            runCatching { record.release() }
            if (audioRecord === record) audioRecord = null
        }
    }
}
