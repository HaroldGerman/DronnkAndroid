package com.german.dronnk.voice

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
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
import android.service.voice.VoiceInteractionService
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.german.dronnk.R
import com.german.dronnk.ui.MainActivity
import com.german.dronnk.youtube.YouTubeSearchClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class HandsFreeService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_START = "com.german.dronnk.voice.START"
        const val ACTION_STOP = "com.german.dronnk.voice.STOP"
        const val PREFS = "dronnk_hands_free"
        const val KEY_ENABLED = "enabled"
        private const val CHANNEL_ID = "dronnk_hands_free"
        private const val NOTIFICATION_ID = 2101
        private const val COMMAND_TIMEOUT_MS = 8_000L
        private const val TTS_UTTERANCE_ID = "hands-free-response"
        private const val MONITOR_RESUME_MS = 700L
    }

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var listening = false
    private var awaitingCommand = false
    private var isSpeaking = false
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val youtubeSearch by lazy { YouTubeSearchClient(this) }
    private val voiceMonitor by lazy {
        VoiceActivityMonitor(this) {
            handler.post {
                if (isEnabled() && !isSpeaking && !listening) {
                    handler.postDelayed(::startRecognitionFromVoiceActivity, 300L)
                }
            }
        }
    }

    private val commandTimeout = Runnable {
        if (!isEnabled() || !awaitingCommand) return@Runnable
        awaitingCommand = false
        runCatching { recognizer?.cancel() }
        listening = false
        updateNotification("Esperando voz…")
        handler.postDelayed(::startVoiceMonitoring, MONITOR_RESUME_MS)
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
        startAsForeground("Manos libres activo · esperando voz")
        ensureRecognizer()
        handler.postDelayed(::startVoiceMonitoring, 350L)
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
        if (recognizer != null) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("El reconocimiento de voz no está disponible")
            return
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    listening = true
                    updateNotification(if (awaitingCommand) "Dronnk activado · di tu orden" else "Escuchando…")
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { listening = false }

                override fun onError(error: Int) {
                    listening = false
                    handler.removeCallbacks(commandTimeout)
                    if (!isEnabled() || isSpeaking) return
                    awaitingCommand = false
                    updateNotification("Manos libres activo · esperando voz")
                    handler.postDelayed(::startVoiceMonitoring, MONITOR_RESUME_MS)
                }

                override fun onResults(results: Bundle?) {
                    listening = false
                    handler.removeCallbacks(commandTimeout)
                    if (!isEnabled()) return

                    val candidates = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        .orEmpty()
                        .map(String::trim)
                        .filter(String::isNotBlank)

                    if (candidates.isEmpty()) {
                        awaitingCommand = false
                        handler.postDelayed(::startVoiceMonitoring, MONITOR_RESUME_MS)
                    } else {
                        processRecognitionCandidates(candidates)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (partial.isNotBlank() && findWakeWord(partial) != null) {
                        updateNotification("Dronnk detectado · termina la orden")
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
    }

    private fun recognitionIntent(commandMode: Boolean): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-PE")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-PE")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, if (commandMode) 1_500L else 1_900L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, if (commandMode) 1_000L else 1_300L)
    }

    private fun startVoiceMonitoring() {
        if (!isEnabled() || listening || isSpeaking || awaitingCommand) return
        updateNotification("Manos libres activo · esperando voz")
        voiceMonitor.start()
    }

    private fun startRecognitionFromVoiceActivity() {
        if (!isEnabled() || listening || isSpeaking) return
        voiceMonitor.stop()
        ensureRecognizer()
        val engine = recognizer ?: run {
            handler.postDelayed(::startVoiceMonitoring, MONITOR_RESUME_MS)
            return
        }
        awaitingCommand = false
        updateNotification("Escuchando…")
        runCatching { engine.startListening(recognitionIntent(false)) }
            .onFailure { handler.postDelayed(::startVoiceMonitoring, MONITOR_RESUME_MS) }
    }

    private fun startCommandListening() {
        if (!isEnabled() || isSpeaking) return
        voiceMonitor.stop()
        handler.removeCallbacks(commandTimeout)
        runCatching { recognizer?.cancel() }
        listening = false
        awaitingCommand = true
        updateNotification("Dronnk activado · di tu orden")

        handler.postDelayed({
            if (!isEnabled() || !awaitingCommand || isSpeaking) return@postDelayed
            ensureRecognizer()
            val engine = recognizer ?: return@postDelayed
            runCatching { engine.startListening(recognitionIntent(true)) }
                .onSuccess { handler.postDelayed(commandTimeout, COMMAND_TIMEOUT_MS) }
                .onFailure {
                    awaitingCommand = false
                    handler.postDelayed(::startVoiceMonitoring, MONITOR_RESUME_MS)
                }
        }, 350L)
    }

    private fun findWakeWord(text: String): IntRange? {
        val lower = text.lowercase(Locale.getDefault())
        listOf("dronnk", "dronk", "dron", "drone").forEach { variant ->
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

            if (command.isBlank()) handler.postDelayed(::startVoiceMonitoring, MONITOR_RESUME_MS)
            else executeCommand(command)
            return
        }

        val withWakeWord = candidates.firstOrNull { findWakeWord(it) != null }
        if (withWakeWord != null) {
            val match = findWakeWord(withWakeWord) ?: run {
                handler.postDelayed(::startVoiceMonitoring, MONITOR_RESUME_MS)
                return
            }
            val rest = withWakeWord.substring(match.last + 1).trim(' ', ',', '.', ':', ';', '-')
            if (rest.isBlank()) startCommandListening() else executeCommand(rest)
            return
        }

        // El VAD puede activar el reconocedor después de que la primera sílaba ya haya sonado.
        // Si Android pierde "Dronnk" pero entiende una orden válida, la ejecutamos igualmente.
        val directCommand = candidates.firstOrNull { looksLikeSupportedCommand(it) }
        if (directCommand != null) {
            executeCommand(directCommand)
        } else {
            updateNotification("Manos libres activo · esperando voz")
            handler.postDelayed(::startVoiceMonitoring, MONITOR_RESUME_MS)
        }
    }

    private fun stripWakeWordPrefix(text: String): String {
        val match = findWakeWord(text) ?: return text.trim()
        if (match.first > 3) return text.trim()
        return text.substring(match.last + 1).trim(' ', ',', '.', ':', ';', '-')
    }

    private fun looksLikeSupportedCommand(text: String): Boolean {
        val q = text.lowercase(Locale.getDefault()).trim()
        return q.contains("linterna") || q.contains("volumen") || q.startsWith("llama") ||
            q.contains("whatsapp") || Regex("\\bwsp\\b").containsMatchIn(q) ||
            q.startsWith("pon ") || q.startsWith("reproduce") || q.startsWith("abre ") ||
            q.contains("pausa") || q.contains("reanuda") || q.contains("continúa") ||
            q.contains("continua") || q.contains("siguiente") || q.contains("anterior") ||
            q.contains("batería") || q.contains("bateria") || q.contains("desactiva manos libres")
    }

    private fun executeCommand(command: String) {
        awaitingCommand = false
        handler.removeCallbacks(commandTimeout)
        val cleanCommand = stripWakeWordPrefix(command)
        val q = cleanCommand.lowercase(Locale.getDefault()).trim()
        updateNotification("Procesando: $cleanCommand")

        when {
            (q.contains("apaga") || q.contains("desactiva")) && q.contains("linterna") -> setTorch(false)
            (q.contains("enciende") || q.contains("prende") || q.contains("activa")) && q.contains("linterna") -> setTorch(true)
            q.contains("sube") && q.contains("volumen") -> changeVolume(AudioManager.ADJUST_RAISE, "Subiendo el volumen.")
            q.contains("baja") && q.contains("volumen") -> changeVolume(AudioManager.ADJUST_LOWER, "Bajando el volumen.")
            q.contains("silencia") || q.contains("silencio") -> changeVolume(AudioManager.ADJUST_MUTE, "Silenciando.")
            q == "pausa" || q.contains("pausa la música") || q.contains("pausa la musica") -> mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE, "Pausando reproducción.")
            q == "reanuda" || q == "continúa" || q == "continua" || q.contains("reanuda la música") || q.contains("continúa la música") || q.contains("continua la musica") -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY, "Reanudando reproducción.")
            q.contains("siguiente canción") || q.contains("siguiente cancion") || q == "siguiente" -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, "Pasando a la siguiente canción.")
            q.contains("canción anterior") || q.contains("cancion anterior") || q == "anterior" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Volviendo a la canción anterior.")
            q.startsWith("llama a ") || q.startsWith("llamar a ") -> callContact(cleanCommand.substringAfter(" a ").trim())
            isWhatsAppChatCommand(q) -> openWhatsAppChat(cleanCommand)
            q.startsWith("pon ") || q.startsWith("reproduce ") || q.startsWith("reproducir ") -> playMusic(cleanCommand)
            q == "abre whatsapp" || q == "abre wsp" -> openPackage("com.whatsapp", "WhatsApp")
            q.startsWith("abre ") -> openApp(cleanCommand.substringAfter(" ").trim())
            q.contains("batería") || q.contains("bateria") -> {
                val manager = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
                speakAndResume("Tienes ${manager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)} por ciento de batería.")
            }
            q.contains("detén dronnk") || q.contains("deten dronnk") || q.contains("desactiva manos libres") -> disableAndStop()
            else -> handler.postDelayed(::startVoiceMonitoring, MONITOR_RESUME_MS)
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

    private fun mediaKey(keyCode: Int, response: String) {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        runCatching {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }.onSuccess { speakAndResume(response) }
            .onFailure { speakAndResume("No pude controlar la reproducción activa.") }
    }

    private fun playMusic(command: String) {
        val q = command.lowercase(Locale.getDefault())
        val query = command
            .replace(Regex("(?i)^(pon|reproduce|reproducir)\\s+"), "")
            .replace(Regex("(?i)\\s+(en|desde)\\s+(spotify|youtube music|youtube).*$"), "")
            .replace(Regex("(?i)^m[uú]sica\\s*"), "")
            .trim()

        if (q.contains("youtube music")) {
            openExternal(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=${Uri.encode(query)}")).setPackage("com.google.android.apps.youtube.music"),
                "Abriendo la búsqueda de $query en YouTube Music."
            )
            return
        }
        if (q.contains("youtube") && !q.contains("youtube music")) {
            playYouTubeVideo(query)
            return
        }
        val clean = query.ifBlank { "música" }
        openExternal(
            Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:${Uri.encode(clean)}")).setPackage("com.spotify.music"),
            "Abriendo la búsqueda de $clean en Spotify."
        )
    }

    private fun playYouTubeVideo(query: String) {
        val clean = query.ifBlank { "música" }
        updateNotification("Buscando $clean en YouTube…")
        serviceScope.launch {
            val result = withContext(Dispatchers.IO) { youtubeSearch.findFirstVideoId(clean) }
            result.onSuccess { videoId ->
                val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId")).setPackage("com.google.android.youtube")
                val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId"))
                val target = if (appIntent.resolveActivity(packageManager) != null) appIntent else fallback
                openExternal(target, "Reproduciendo $clean en YouTube.")
            }.onFailure {
                speakAndResume("No pude encontrar ese video en YouTube. Revisa la configuración de la API de YouTube.")
            }
        }
    }

    private fun callContact(target: String) {
        val direct = target.filter { it.isDigit() || it == '+' }
        if (direct.length >= 5 && direct.length >= target.length - 2) {
            placeDirectCall(direct, direct)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            speakAndResume("Necesito permiso de contactos. Abre Dronnk para concederlo.")
            return
        }
        val contact = findContact(target)
        if (contact == null) speakAndResume("No encontré a $target en tus contactos.")
        else placeDirectCall(contact.second, contact.first)
    }

    private fun placeDirectCall(number: String, label: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            speakAndResume("Necesito permiso de teléfono. Abre Dronnk y haz una llamada una vez para concederlo.")
            return
        }
        openExternal(Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(number)}")), "Llamando a $label.")
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
        voiceMonitor.stop()
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        val assistantComponent = ComponentName(this, DronnkVoiceInteractionService::class.java)
        val dronnkIsActiveAssistant = VoiceInteractionService.isActiveService(this, assistantComponent)

        if (dronnkIsActiveAssistant) {
            val launched = runCatching {
                startActivity(intent)
                true
            }.getOrDefault(false)
            if (launched) {
                speakAndResume(response)
                return
            }
        }

        if (DronnkVoiceInteractionService.launchExternal(intent)) {
            speakAndResume(response)
            return
        }

        postUnlockNotification(intent, response)
        speakAndResume("No pude abrir la aplicación automáticamente. Toca la notificación de Dronnk para completar la acción.")
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
            .setContentTitle("Dronnk necesita abrir una app")
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(2102, notification)
    }

    private fun speakAndResume(message: String) {
        voiceMonitor.stop()
        handler.removeCallbacks(commandTimeout)
        runCatching { recognizer?.cancel() }
        listening = false
        awaitingCommand = false
        isSpeaking = true
        updateNotification(message)

        val engine = tts
        if (engine == null) {
            isSpeaking = false
            handler.postDelayed(::startVoiceMonitoring, MONITOR_RESUME_MS)
            return
        }

        val result = engine.speak(message, TextToSpeech.QUEUE_FLUSH, null, TTS_UTTERANCE_ID)
        if (result == TextToSpeech.ERROR) {
            isSpeaking = false
            handler.postDelayed(::startVoiceMonitoring, MONITOR_RESUME_MS)
        }
    }

    private fun isEnabled(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    private fun disableAndStop() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply()
        handler.removeCallbacksAndMessages(null)
        voiceMonitor.stop()
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
        if (status != TextToSpeech.SUCCESS) return
        tts?.language = Locale("es", "PE")
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                if (utteranceId != TTS_UTTERANCE_ID) return
                isSpeaking = false
                handler.postDelayed({ if (isEnabled()) startVoiceMonitoring() }, MONITOR_RESUME_MS)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId != TTS_UTTERANCE_ID) return
                isSpeaking = false
                handler.postDelayed(::startVoiceMonitoring, MONITOR_RESUME_MS)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId != TTS_UTTERANCE_ID) return
                isSpeaking = false
                handler.postDelayed(::startVoiceMonitoring, MONITOR_RESUME_MS)
            }
        })
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        voiceMonitor.stop()
        runCatching { recognizer?.cancel() }
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        serviceScope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
