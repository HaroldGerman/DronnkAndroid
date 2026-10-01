package com.german.dronnk.voice

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.german.dronnk.R
import com.german.dronnk.ui.MainActivity
import java.util.Locale

class HandsFreeService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_START = "com.german.dronnk.voice.START"
        const val ACTION_STOP = "com.german.dronnk.voice.STOP"
        const val PREFS = "dronnk_hands_free"
        const val KEY_ENABLED = "enabled"
        private const val CHANNEL_ID = "dronnk_hands_free"
        private const val NOTIFICATION_ID = 2101
        private const val HOTWORD_SESSION_MS = 30_000L
        private const val RETRY_DELAY_MS = 2_500L
        private const val COMMAND_TIMEOUT_MS = 8_000L
    }

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var listening = false
    private var awaitingCommand = false
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    private val hotwordWatchdog = Runnable {
        if (!isEnabled() || awaitingCommand) return@Runnable
        restartRecognition(false, 700L)
    }

    private val commandTimeout = Runnable {
        if (!isEnabled() || !awaitingCommand) return@Runnable
        awaitingCommand = false
        updateNotification("No escuché una orden. Esperando “Dronnk”…")
        restartRecognition(false, 1_000L)
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        acquireWakeLock()
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            disableAndStop()
            return START_NOT_STICKY
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            disableAndStop()
            return START_NOT_STICKY
        }

        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, true).apply()
        startAsForeground("Esperando “Dronnk”…")
        ensureRecognizer()
        startHotwordListening()
        return START_STICKY
    }

    private fun acquireWakeLock() {
        val power = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Dronnk:HandsFree").apply {
            setReferenceCounted(false)
            if (!isHeld) acquire()
        }
    }

    private fun startAsForeground(text: String) {
        val openApp = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            2,
            Intent(this, HandsFreeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.dronnk_assistant_icon)
            .setContentTitle("Dronnk manos libres")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(0, "Detener", stop)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun updateNotification(text: String) = startAsForeground(text)

    private fun ensureRecognizer() {
        if (recognizer != null || !SpeechRecognizer.isRecognitionAvailable(this)) return

        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listening = true
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                listening = false
            }

            override fun onError(error: Int) {
                listening = false
                handler.removeCallbacks(hotwordWatchdog)
                if (!isEnabled()) return

                if (awaitingCommand) {
                    handler.removeCallbacks(commandTimeout)
                    updateNotification("No escuché la orden. Inténtalo otra vez después de decir “Dronnk”.")
                    awaitingCommand = false
                }

                // SpeechRecognizer may emit TIMEOUT/NO_MATCH quickly on Samsung.
                // Never loop immediately: rapid restarts cause the repeated system chime.
                handler.postDelayed({
                    if (isEnabled()) startHotwordListening()
                }, RETRY_DELAY_MS)
            }

            override fun onResults(results: Bundle?) {
                listening = false
                handler.removeCallbacks(hotwordWatchdog)
                handler.removeCallbacks(commandTimeout)

                val candidates = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty()
                    .map(String::trim)
                    .filter(String::isNotBlank)

                if (candidates.isEmpty()) {
                    restartRecognition(awaitingCommand, 1_000L)
                    return
                }

                processRecognitionCandidates(candidates)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (awaitingCommand) return
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()

                // Do NOT stop the recognizer here. Doing so used to cut off phrases such as
                // “Dronnk, apaga la linterna” immediately after the wake word.
                if (findWakeWord(partial) != null) {
                    updateNotification("Te escucho… termina la orden")
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
    }

    private fun recognitionIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-PE")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-PE")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_300L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
    }

    private fun startHotwordListening() {
        if (!isEnabled() || listening) return
        awaitingCommand = false
        handler.removeCallbacks(hotwordWatchdog)
        handler.removeCallbacks(commandTimeout)
        updateNotification("Esperando “Dronnk”…")

        val started = runCatching {
            recognizer?.startListening(recognitionIntent())
        }.isSuccess

        if (started) {
            handler.postDelayed(hotwordWatchdog, HOTWORD_SESSION_MS)
        } else {
            handler.postDelayed(::startHotwordListening, RETRY_DELAY_MS)
        }
    }

    private fun startCommandListening() {
        if (!isEnabled()) return

        handler.removeCallbacks(hotwordWatchdog)
        handler.removeCallbacks(commandTimeout)
        runCatching { recognizer?.cancel() }
        listening = false
        awaitingCommand = true
        updateNotification("Te escucho… di la orden")

        handler.postDelayed({
            if (!isEnabled() || !awaitingCommand) return@postDelayed
            val started = runCatching {
                recognizer?.startListening(recognitionIntent())
            }.isSuccess
            if (started) {
                handler.postDelayed(commandTimeout, COMMAND_TIMEOUT_MS)
            } else {
                awaitingCommand = false
                handler.postDelayed(::startHotwordListening, RETRY_DELAY_MS)
            }
        }, 450L)
    }

    private fun restartRecognition(commandMode: Boolean, delay: Long) {
        handler.removeCallbacks(hotwordWatchdog)
        handler.removeCallbacks(commandTimeout)
        runCatching { recognizer?.cancel() }
        listening = false
        handler.postDelayed({
            if (!isEnabled()) return@postDelayed
            if (commandMode) startCommandListening() else startHotwordListening()
        }, delay)
    }

    private fun findWakeWord(text: String): IntRange? {
        val lower = text.lowercase(Locale.getDefault())
        val variants = listOf("dronnk", "dronk", "dron", "drone")
        variants.forEach { variant ->
            val index = lower.indexOf(variant)
            if (index >= 0) return index until (index + variant.length)
        }
        return null
    }

    private fun processRecognitionCandidates(candidates: List<String>) {
        if (awaitingCommand) {
            awaitingCommand = false
            val command = candidates
                .map(::stripWakeWordPrefix)
                .firstOrNull { looksLikeSupportedCommand(it) }
                ?: stripWakeWordPrefix(candidates.first())

            if (command.isBlank()) {
                updateNotification("No escuché una orden. Esperando “Dronnk”…")
                restartRecognition(false, 1_000L)
            } else {
                executeCommand(command)
            }
            return
        }

        val withWakeWord = candidates.firstOrNull { findWakeWord(it) != null }
        if (withWakeWord == null) {
            restartRecognition(false, 1_200L)
            return
        }

        val match = findWakeWord(withWakeWord) ?: run {
            restartRecognition(false, 1_200L)
            return
        }
        val rest = withWakeWord
            .substring(match.last + 1)
            .trim(' ', ',', '.', ':', ';', '-')

        if (rest.isBlank()) {
            startCommandListening()
        } else {
            executeCommand(rest)
        }
    }

    private fun stripWakeWordPrefix(text: String): String {
        val match = findWakeWord(text) ?: return text.trim()
        if (match.first > 3) return text.trim()
        return text.substring(match.last + 1).trim(' ', ',', '.', ':', ';', '-')
    }

    private fun looksLikeSupportedCommand(text: String): Boolean {
        val q = text.lowercase(Locale.getDefault())
        return q.contains("linterna") ||
            q.contains("volumen") ||
            q.startsWith("llama") ||
            q.contains("whatsapp") ||
            Regex("\\bwsp\\b").containsMatchIn(q) ||
            q.startsWith("pon ") ||
            q.startsWith("reproduce") ||
            q.startsWith("abre ") ||
            q.contains("batería") ||
            q.contains("bateria") ||
            q.contains("desactiva manos libres")
    }

    private fun executeCommand(command: String) {
        awaitingCommand = false
        handler.removeCallbacks(commandTimeout)
        updateNotification("Procesando: $command")
        val q = stripWakeWordPrefix(command).lowercase(Locale.getDefault()).trim()

        when {
            (q.contains("apaga") || q.contains("desactiva")) && q.contains("linterna") -> setTorch(false)
            (q.contains("enciende") || q.contains("prende") || q.contains("activa")) && q.contains("linterna") -> setTorch(true)
            q.contains("sube") && q.contains("volumen") -> changeVolume(AudioManager.ADJUST_RAISE, "Subiendo el volumen.")
            q.contains("baja") && q.contains("volumen") -> changeVolume(AudioManager.ADJUST_LOWER, "Bajando el volumen.")
            q.contains("silencia") || q.contains("silencio") -> changeVolume(AudioManager.ADJUST_MUTE, "Silenciando.")
            q.startsWith("llama a ") || q.startsWith("llamar a ") -> callContact(command.substringAfter(" a ").trim())
            isWhatsAppChatCommand(q) -> openWhatsAppChat(command)
            q.startsWith("pon ") || q.startsWith("reproduce ") || q.startsWith("reproducir ") -> playMusic(command)
            q == "abre whatsapp" || q == "abre wsp" -> openPackage("com.whatsapp", "WhatsApp")
            q.startsWith("abre ") -> openApp(command.substringAfter(" ").trim())
            q.contains("batería") || q.contains("bateria") -> {
                val manager = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
                speakAndResume("Tienes ${manager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)} por ciento de batería.")
            }
            q.contains("detén dronnk") || q.contains("deten dronnk") || q.contains("desactiva manos libres") -> disableAndStop()
            else -> {
                updateNotification("No reconocí la orden: $command")
                speakAndResume("No entendí esa orden. Dila de nuevo después de decir Dronnk.")
            }
        }
    }

    private fun setTorch(enabled: Boolean) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            speakAndResume("Necesito permiso de cámara para controlar la linterna.")
            return
        }
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        val id = runCatching {
            manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
        if (id == null) speakAndResume("No encontré una linterna disponible.")
        else runCatching { manager.setTorchMode(id, enabled) }
            .onSuccess { speakAndResume(if (enabled) "Linterna encendida." else "Linterna apagada.") }
            .onFailure { speakAndResume("No pude cambiar la linterna.") }
    }

    private fun changeVolume(direction: Int, response: String) {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        runCatching { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI) }
            .onSuccess { speakAndResume(response) }
            .onFailure { speakAndResume("No pude cambiar el volumen.") }
    }

    private fun playMusic(command: String) {
        val q = command.lowercase(Locale.getDefault())
        val query = command
            .replace(Regex("(?i)^(pon|reproduce|reproducir)\\s+"), "")
            .replace(Regex("(?i)\\s+(en|desde)\\s+(spotify|youtube music|youtube).*$"), "")
            .replace(Regex("(?i)^m[uú]sica\\s*"), "")
            .trim()
        if (q.contains("youtube music")) {
            openExternal(Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=${Uri.encode(query)}")).setPackage("com.google.android.apps.youtube.music"), "Abriendo $query en YouTube Music.")
            return
        }
        if (q.contains("youtube") && !q.contains("youtube music")) {
            openExternal(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")).setPackage("com.google.android.youtube"), "Abriendo $query en YouTube.")
            return
        }
        val clean = query.ifBlank { "música" }
        val playIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage("com.spotify.music")
            putExtra(android.app.SearchManager.QUERY, clean)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (playIntent.resolveActivity(packageManager) != null) {
            openExternal(playIntent, "Reproduciendo $clean en Spotify.")
        } else {
            openExternal(
                Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:${Uri.encode(clean)}")).setPackage("com.spotify.music"),
                "Abriendo $clean en Spotify."
            )
        }
    }

    private fun callContact(target: String) {
        val direct = target.filter { it.isDigit() || it == '+' }
        if (direct.length >= 5 && direct.length >= target.length - 2) {
            openExternal(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$direct")), "Preparando llamada.")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            speakAndResume("Necesito permiso de contactos. Abre Dronnk para concederlo.")
            return
        }
        val contact = findContact(target)
        if (contact == null) speakAndResume("No encontré a $target en tus contactos.")
        else openExternal(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(contact.second)}")), "Preparando llamada a ${contact.first}.")
    }

    private fun isWhatsAppChatCommand(q: String): Boolean {
        val mentions = q.contains("whatsapp") || Regex("\\bwsp\\b").containsMatchIn(q)
        val chat = q.contains("chat") || q.contains("conversación") || q.contains("conversacion") || q.contains("mensaje")
        return q.startsWith("abre el chat de ") || q.startsWith("entra al chat de ") || (mentions && chat)
    }

    private fun openWhatsAppChat(command: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            speakAndResume("Necesito permiso de contactos. Abre Dronnk para concederlo.")
            return
        }
        val patterns = listOf(
            Regex("(?i)(?:chat|conversaci[oó]n)\\s+(?:de|con)\\s+(.+?)(?:\\s+en\\s+(?:whatsapp|wsp))?$"),
            Regex("(?i)(?:whatsapp|wsp)\\s+(?:a|con)\\s+(.+)$")
        )
        val name = patterns.firstNotNullOfOrNull {
            it.find(command)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)
        }
        if (name.isNullOrBlank()) {
            openPackage("com.whatsapp", "WhatsApp")
            return
        }
        val contact = findContact(name)
        if (contact == null) {
            speakAndResume("No encontré a $name en tus contactos.")
            return
        }
        var digits = contact.second.filter(Char::isDigit)
        if (!contact.second.trim().startsWith("+") && digits.length == 9) digits = "51$digits"
        openExternal(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")).setPackage("com.whatsapp"),
            "Abriendo el chat de ${contact.first}."
        )
    }

    private fun findContact(name: String): Pair<String, String>? {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )?.use { if (it.moveToFirst()) return it.getString(0) to it.getString(1) }
        return null
    }

    private fun openApp(name: String) {
        val aliases = mapOf("wsp" to "whatsapp", "yt" to "youtube", "spoty" to "spotify")
        val normalized = aliases[name.lowercase(Locale.getDefault())] ?: name.lowercase(Locale.getDefault())
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = packageManager.queryIntentActivities(launcher, 0).firstOrNull {
            it.loadLabel(packageManager).toString().lowercase(Locale.getDefault()).contains(normalized)
        }
        val intent = match?.activityInfo?.packageName?.let(packageManager::getLaunchIntentForPackage)
        if (intent == null) speakAndResume("No encontré una app llamada $name.")
        else openExternal(intent, "Abriendo ${match.loadLabel(packageManager)}.")
    }

    private fun openPackage(packageName: String, label: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) speakAndResume("No encontré $label instalado.")
        else openExternal(intent, "Abriendo $label.")
    }

    private fun openExternal(intent: Intent, response: String) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onSuccess { speakAndResume(response) }
            .onFailure {
                postUnlockNotification(intent, response)
                speakAndResume("Entendí la orden. Desbloquea el teléfono para completar esta acción.")
            }
    }

    private fun postUnlockNotification(intent: Intent, text: String) {
        val pending = PendingIntent.getActivity(
            this,
            99,
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.dronnk_assistant_icon)
            .setContentTitle("Dronnk necesita desbloqueo")
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(2102, notification)
    }

    private fun speakAndResume(message: String) {
        handler.removeCallbacks(hotwordWatchdog)
        handler.removeCallbacks(commandTimeout)
        runCatching { recognizer?.cancel() }
        listening = false
        awaitingCommand = false
        updateNotification(message)

        val engine = tts
        if (engine == null) {
            handler.postDelayed(::startHotwordListening, 1_500L)
            return
        }

        engine.speak(message, TextToSpeech.QUEUE_FLUSH, null, "hands-free-response")
        handler.postDelayed({
            if (isEnabled()) startHotwordListening()
        }, 2_300L)
    }

    private fun isEnabled(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    private fun disableAndStop() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply()
        handler.removeCallbacksAndMessages(null)
        runCatching { recognizer?.cancel() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Dronnk manos libres",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene Dronnk disponible por voz incluso con la pantalla bloqueada."
                enableLights(false)
                lightColor = Color.RED
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale("es", "PE")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { recognizer?.cancel() }
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
