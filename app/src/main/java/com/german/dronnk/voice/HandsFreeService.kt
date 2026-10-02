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
import com.german.dronnk.ai.DronnkBrain
import com.german.dronnk.apps.AppResolver
import com.german.dronnk.communication.CommunicationRouter
import com.german.dronnk.contacts.ContactMatcher
import com.german.dronnk.media.MediaRouter
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
        private const val MONITOR_RESUME_MS = 650L
        private const val TTS_UTTERANCE_ID = "hands-free-response"
    }

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var listening = false
    private var awaitingCommand = false
    private var isSpeaking = false

    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    private val brain by lazy { DronnkBrain() }
    private val youtubeSearch by lazy { YouTubeSearchClient(this) }
    private val contacts by lazy { ContactMatcher(this) }
    private val apps by lazy { AppResolver(this) }
    private val mediaRouter by lazy { MediaRouter(this) }
    private val communicationRouter by lazy { CommunicationRouter(this) }

    private val voiceMonitor by lazy {
        VoiceActivityMonitor(this) {
            handler.post {
                if (isEnabled() && !isSpeaking && !listening) {
                    handler.postDelayed(::startRecognitionFromVoiceActivity, 220L)
                }
            }
        }
    }

    private val commandTimeout = Runnable {
        if (!isEnabled() || !awaitingCommand) return@Runnable
        awaitingCommand = false
        runCatching { recognizer?.cancel() }
        listening = false
        updateNotification("Manos libres activo · esperando voz")
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
        handler.postDelayed(::startVoiceMonitoring, 300L)
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
            updateNotification("Reconocimiento de voz no disponible")
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
                    handler.postDelayed(::startVoiceMonitoring, MONITOR_RESUME_MS)
                }

                override fun onResults(results: Bundle?) {
                    listening = false
                    handler.removeCallbacks(commandTimeout)
                    if (!isEnabled()) return
                    val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        .orEmpty().map(String::trim).filter(String::isNotBlank)
                    if (candidates.isEmpty()) {
                        awaitingCommand = false
                        handler.postDelayed(::startVoiceMonitoring, MONITOR_RESUME_MS)
                    } else {
                        processRecognitionCandidates(candidates)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    if (partial.isNotBlank() && findWakeWord(partial) != null) {
                        updateNotification("Dronnk detectado · termina la orden")
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
    }

    private fun recognitionIntent(commandMode: Boolean) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-PE")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-PE")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, if (commandMode) 1500L else 1900L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, if (commandMode) 1000L else 1300L)
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
        }, 320L)
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
            executeCommand(stripWakeWordPrefix(candidates.first()))
            return
        }

        val withWakeWord = candidates.firstOrNull { findWakeWord(it) != null }
        if (withWakeWord != null) {
            val match = findWakeWord(withWakeWord) ?: return
            val rest = withWakeWord.substring(match.last + 1).trim(' ', ',', '.', ':', ';', '-')
            if (rest.isBlank()) startCommandListening() else executeCommand(rest)
            return
        }

        executeCommand(candidates.first())
    }

    private fun stripWakeWordPrefix(text: String): String {
        val match = findWakeWord(text) ?: return text.trim()
        if (match.first > 3) return text.trim()
        return text.substring(match.last + 1).trim(' ', ',', '.', ':', ';', '-')
    }

    private fun executeCommand(command: String) {
        val clean = stripWakeWordPrefix(command)
        val q = clean.lowercase(Locale.getDefault()).trim()
        if (q.isBlank()) {
            handler.postDelayed(::startVoiceMonitoring, MONITOR_RESUME_MS)
            return
        }

        when {
            (q.contains("apaga") || q.contains("desactiva")) && q.contains("linterna") -> setTorch(false)
            (q.contains("enciende") || q.contains("prende") || q.contains("activa")) && q.contains("linterna") -> setTorch(true)
            q.contains("sube") && q.contains("volumen") -> changeVolume(AudioManager.ADJUST_RAISE, "Subiendo el volumen.")
            q.contains("baja") && q.contains("volumen") -> changeVolume(AudioManager.ADJUST_LOWER, "Bajando el volumen.")
            q.contains("silencia") || q.contains("silencio") -> changeVolume(AudioManager.ADJUST_MUTE, "Silenciando.")
            q == "pausa" || q.contains("pausa la música") || q.contains("pausa la musica") -> mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE, "Pausando reproducción.")
            q == "reanuda" || q == "continúa" || q == "continua" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY, "Reanudando reproducción.")
            q.contains("siguiente") -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, "Siguiente canción.")
            q.contains("anterior") -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Canción anterior.")
            q.contains("batería") || q.contains("bateria") -> battery()
            q.contains("detén dronnk") || q.contains("deten dronnk") || q.contains("desactiva manos libres") -> disableAndStop()
            else -> handleIntelligentCommand(clean)
        }
    }

    private fun handleIntelligentCommand(text: String) {
        if (!brain.isConfigured()) {
            speakAndResume("La IA de Dronnk no está configurada.")
            return
        }
        voiceMonitor.stop()
        updateNotification("Pensando…")
        serviceScope.launch {
            val result = withContext(Dispatchers.IO) { brain.interpret(text) }
            result.onSuccess(::executeBrainDecision)
                .onFailure { speakAndResume("No pude interpretar esa orden ahora mismo.") }
        }
    }

    private fun executeBrainDecision(decision: DronnkBrain.Decision) {
        when (decision.action) {
            "OPEN_APP" -> openApp(decision.app.ifBlank { decision.value })
            "CALL_CONTACT" -> callContact(decision.target.ifBlank { decision.value })
            "CALL_IN_APP" -> callInApp(decision.app, decision.target)
            "OPEN_CHAT" -> openChat(decision.app, decision.target)
            "PREPARE_MESSAGE" -> prepareMessage(decision.app, decision.target, decision.message)
            "PLAY_MEDIA" -> playMedia(decision.app, decision.value)
            "TORCH_ON" -> setTorch(true)
            "TORCH_OFF" -> setTorch(false)
            "MEDIA_PAUSE" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE, decision.reply.ifBlank { "Pausando reproducción." })
            "MEDIA_PLAY" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY, decision.reply.ifBlank { "Reanudando reproducción." })
            "MEDIA_NEXT" -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, decision.reply.ifBlank { "Siguiente canción." })
            "MEDIA_PREVIOUS" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, decision.reply.ifBlank { "Canción anterior." })
            "BATTERY" -> battery()
            else -> if (decision.reply.isNotBlank()) speakAndResume(decision.reply) else handler.postDelayed(::startVoiceMonitoring, MONITOR_RESUME_MS)
        }
    }

    private fun playMedia(requestedApp: String, query: String) {
        val app = requestedApp.ifBlank { "youtube" }
        if (app.lowercase(Locale.getDefault()).contains("youtube") && !app.lowercase(Locale.getDefault()).contains("music")) {
            playYouTubeVideo(query)
            return
        }
        val plan = mediaRouter.plan(query.ifBlank { "música" }, app)
        if (plan == null) {
            speakAndResume("No encontré una aplicación llamada $app.")
            return
        }
        val response = if (plan.directPlaybackRequested) {
            "Reproduciendo ${query.ifBlank { "música" }} en ${plan.appLabel}."
        } else {
            "Abriendo ${plan.appLabel} para ${query.ifBlank { "música" }}."
        }
        openExternal(plan.intent, response)
    }

    private fun playYouTubeVideo(query: String) {
        val clean = query.ifBlank { "música" }
        serviceScope.launch {
            val result = withContext(Dispatchers.IO) { youtubeSearch.findFirstVideoId(clean) }
            result.onSuccess { videoId ->
                val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId")).setPackage("com.google.android.youtube")
                val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId"))
                val target = if (appIntent.resolveActivity(packageManager) != null) appIntent else fallback
                openExternal(target, "Reproduciendo $clean en YouTube.")
            }.onFailure { speakAndResume("No pude encontrar $clean en YouTube.") }
        }
    }

    private fun callContact(target: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            speakAndResume("Necesito permiso de contactos.")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            speakAndResume("Necesito permiso de teléfono.")
            return
        }

        val direct = target.filter { it.isDigit() || it == '+' }
        val numberAndName = if (direct.length >= 5 && direct.length >= target.length - 2) {
            direct to target
        } else {
            val match = contacts.findBest(target)
            if (match == null) {
                speakAndResume("No encontré un contacto parecido a $target.")
                return
            }
            match.phone to match.name
        }

        openExternal(
            Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(numberAndName.first)}")),
            "Llamando a ${numberAndName.second}."
        )
    }

    private fun callInApp(appName: String, target: String) {
        val plan = communicationRouter.openChat(appName, target)
        if (plan == null) {
            speakAndResume("No encontré $appName o no pude resolver a $target.")
            return
        }
        openExternal(
            plan.intent,
            "Abriendo ${plan.appLabel} con ${plan.targetLabel}. Si la app ofrece llamada, usa el botón de llamada del chat."
        )
    }

    private fun openChat(appName: String, target: String) {
        val plan = communicationRouter.openChat(appName, target)
        if (plan == null) {
            speakAndResume("No encontré $appName o no pude resolver a $target.")
            return
        }
        val message = if (plan.targetResolved) {
            "Abriendo el chat de ${plan.targetLabel} en ${plan.appLabel}."
        } else {
            "Abriendo ${plan.appLabel} para buscar a ${plan.targetLabel}."
        }
        openExternal(plan.intent, message)
    }

    private fun prepareMessage(appName: String, target: String, message: String) {
        val plan = communicationRouter.prepareMessage(appName, target, message)
        if (plan == null) {
            speakAndResume("No encontré ${appName.ifBlank { "la aplicación de mensajes" }} o no pude resolver a $target.")
            return
        }
        val response = when {
            plan.targetResolved && plan.messagePrepared -> "Abriendo ${plan.appLabel} con el mensaje para ${plan.targetLabel} ya escrito."
            plan.messagePrepared -> "Abriendo ${plan.appLabel} con el mensaje preparado para ${plan.targetLabel}."
            else -> "Abriendo ${plan.appLabel} para continuar con ${plan.targetLabel}."
        }
        openExternal(plan.intent, response)
    }

    private fun openApp(name: String) {
        val match = apps.find(name)
        val intent = match?.packageName?.let(packageManager::getLaunchIntentForPackage)
        if (intent == null) speakAndResume("No encontré una app llamada $name.")
        else openExternal(intent, "Abriendo ${match.label}.")
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

    private fun battery() {
        val manager = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
        speakAndResume("Tienes ${manager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)} por ciento de batería.")
    }

    private fun openExternal(intent: Intent, response: String) {
        voiceMonitor.stop()
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val assistantComponent = ComponentName(this, DronnkVoiceInteractionService::class.java)
        val active = VoiceInteractionService.isActiveService(this, assistantComponent)
        if (active && runCatching { startActivity(intent); true }.getOrDefault(false)) {
            speakAndResume(response)
            return
        }
        if (DronnkVoiceInteractionService.launchExternal(intent)) {
            speakAndResume(response)
            return
        }
        speakAndResume("Entendí la acción, pero Android no permitió abrir la aplicación automáticamente.")
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
        if (engine.speak(message, TextToSpeech.QUEUE_FLUSH, null, TTS_UTTERANCE_ID) == TextToSpeech.ERROR) {
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
            val channel = NotificationChannel(CHANNEL_ID, "Dronnk manos libres", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Mantiene Dronnk disponible por voz."
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
            override fun onError(utteranceId: String?, errorCode: Int) = onError(utteranceId)
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
