package com.german.dronnk.ui

import android.Manifest
import android.app.AlertDialog
import android.app.SearchManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.german.dronnk.BuildConfig
import com.german.dronnk.update.AppUpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var status: TextView
    private lateinit var conversation: TextView
    private lateinit var input: EditText
    private lateinit var listenButton: Button
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var listening = false
    private var torchEnabled = false
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListening()
        else reply("Necesito permiso de micrófono para escucharte. También puedes escribirme.")
    }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) setTorch(true)
        else reply("Necesito permiso de cámara para controlar la linterna.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        createInterface()
        configureSpeechRecognition()
        reply("Jarvis en línea. Dime qué necesitas.", speak = false)
        checkForUpdates(showIfCurrent = false)
    }

    private fun createInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
            setBackgroundColor(Color.rgb(7, 11, 18))
        }

        root.addView(TextView(this).apply {
            text = "JARVIS"
            textSize = 34f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = "Asistente personal · v${BuildConfig.VERSION_NAME}"
            textSize = 14f
            setTextColor(Color.rgb(127, 211, 255))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(14))
        })

        status = TextView(this).apply {
            text = "● EN LÍNEA"
            textSize = 13f
            setTextColor(Color.rgb(112, 240, 184))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(14))
        }
        root.addView(status)

        conversation = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(17, 26, 39))
        }
        val scroll = ScrollView(this).apply { addView(conversation) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        input = EditText(this).apply {
            hint = "Escribe una orden…"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setBackgroundColor(Color.rgb(25, 35, 50))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    submitText()
                    true
                } else false
            }
        }
        root.addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listenButton = Button(this).apply {
            text = "🎙 HABLAR"
            setOnClickListener { toggleListening() }
        }
        val send = Button(this).apply {
            text = "ENVIAR"
            setOnClickListener { submitText() }
        }
        buttons.addView(listenButton, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(6) })
        buttons.addView(send, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginStart = dp(6) })
        root.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val updateButton = Button(this).apply {
            text = "BUSCAR ACTUALIZACIÓN"
            setOnClickListener { checkForUpdates(showIfCurrent = true) }
        }
        root.addView(updateButton, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })

        root.addView(TextView(this).apply {
            text = "Prueba: abre WhatsApp · enciende la linterna · sube el volumen · Wi-Fi · busca restaurantes · navega a Miraflores · llama al 999… · mensaje al 999… hola · pon alarma a las 7:30 · crea evento reunión · batería"
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(12), 0, 0)
        })

        setContentView(root)
    }

    private fun configureSpeechRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "Voz no disponible"
            return
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    listening = true
                    status.text = "● ESCUCHANDO"
                    listenButton.text = "■ DETENER"
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { status.text = "Procesando…" }

                override fun onError(error: Int) {
                    resetListening()
                    if (error != SpeechRecognizer.ERROR_CLIENT && error != SpeechRecognizer.ERROR_NO_MATCH) {
                        reply("No pude entenderte. Inténtalo otra vez.", speak = false)
                    }
                }

                override fun onResults(results: Bundle?) {
                    resetListening()
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?.let(::execute)
                }

                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
    }

    private fun toggleListening() {
        if (listening) {
            recognizer?.stopListening()
            resetListening()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListening() {
        recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-PE")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        })
    }

    private fun submitText() {
        input.text.toString().trim().takeIf { it.isNotEmpty() }?.let {
            input.setText("")
            execute(it)
        }
    }

    private fun execute(command: String) {
        val q = command.lowercase(Locale.getDefault()).trim()
        appendConversation("Tú", command)

        when {
            q == "hola" || q.contains("hola jarvis") -> reply("Hola. Estoy listo.")

            q.contains("qué hora") || q.contains("que hora") -> reply(
                "Son las ${SimpleDateFormat("h:mm a", Locale("es", "PE")).format(Date())}."
            )

            q.contains("qué fecha") || q.contains("que fecha") || q.contains("qué día") || q.contains("que dia") -> reply(
                "Hoy es ${SimpleDateFormat("EEEE d 'de' MMMM", Locale("es", "PE")).format(Date())}."
            )

            q.contains("batería") || q.contains("bateria") -> {
                val battery = getSystemService(BATTERY_SERVICE) as BatteryManager
                reply("Tienes ${battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)} por ciento de batería.")
            }

            q.contains("enciende la linterna") || q.contains("prende la linterna") -> requestTorchOn()
            q.contains("apaga la linterna") -> setTorch(false)

            q.contains("sube el volumen") -> changeVolume(AudioManager.ADJUST_RAISE, "Subiendo el volumen.")
            q.contains("baja el volumen") -> changeVolume(AudioManager.ADJUST_LOWER, "Bajando el volumen.")
            q.contains("silencia") || q.contains("silencio") -> changeVolume(AudioManager.ADJUST_MUTE, "Silenciando el audio multimedia.")

            q.contains("configuración") || q.contains("configuracion") || q == "ajustes" ->
                launch(Intent(Settings.ACTION_SETTINGS), "Abriendo configuración.")

            q.contains("wifi") || q.contains("wi-fi") ->
                launch(Intent(Settings.ACTION_WIFI_SETTINGS), "Abriendo Wi-Fi.")

            q.contains("bluetooth") ->
                launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "Abriendo Bluetooth.")

            q.contains("cámara") || q.contains("camara") ->
                launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "Abriendo cámara.")

            q.startsWith("llama a ") || q.startsWith("llamar a ") -> prepareCall(command)
            q.startsWith("mensaje a ") || q.startsWith("mensaje al ") -> prepareSms(command)

            q.startsWith("navega a ") || q.startsWith("llévame a ") || q.startsWith("llevame a ") -> {
                val place = command.substringAfter(" a ").trim()
                launch(
                    Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(place)}")),
                    "Abriendo navegación a $place."
                )
            }

            q.startsWith("busca ") || q.startsWith("buscar ") -> searchWeb(command.substringAfter(" ").trim())
            q.contains("alarma") -> setAlarm(command)
            q.startsWith("crea evento ") || q.startsWith("crear evento ") -> createCalendarEvent(command)
            q.contains("actualización") || q.contains("actualizacion") || q == "actualiza" -> checkForUpdates(showIfCurrent = true)
            q.startsWith("abre ") || q.startsWith("abrir ") -> openApp(command.substringAfter(" ").trim())

            else -> reply(
                "Todavía no tengo una acción local para “$command”. Puedo controlar acciones del teléfono, abrir apps y ajustes, usar cámara y linterna, buscar, navegar, preparar llamadas y mensajes, crear alarmas y eventos, controlar volumen y consultar el dispositivo."
            )
        }
    }

    private fun requestTorchOn() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            setTorch(true)
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setTorch(enabled: Boolean) {
        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        val cameraId = runCatching {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()

        if (cameraId == null) {
            reply("No encontré una linterna disponible en este dispositivo.")
            return
        }

        runCatching { cameraManager.setTorchMode(cameraId, enabled) }
            .onSuccess {
                torchEnabled = enabled
                reply(if (enabled) "Linterna encendida." else "Linterna apagada.")
            }
            .onFailure { reply("No pude cambiar el estado de la linterna.") }
    }

    private fun changeVolume(direction: Int, message: String) {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        runCatching {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        }.onSuccess { reply(message) }
            .onFailure { reply("No pude cambiar el volumen.") }
    }

    private fun prepareCall(command: String) {
        val digits = command.substringAfter(" a ").filter { it.isDigit() || it == '+' }
        if (digits.length < 5) {
            reply("Dime el número completo.")
        } else {
            launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits")), "Preparando llamada a $digits.")
        }
    }

    private fun prepareSms(command: String) {
        val after = command.substringAfter(" a ").substringAfter(" al ").trim()
        val number = after.takeWhile { it.isDigit() || it == '+' }
        val message = after.drop(number.length).trim()

        if (number.length < 5) {
            reply("Dime un número válido para el mensaje.")
            return
        }

        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
            if (message.isNotBlank()) putExtra("sms_body", message)
        }
        launch(intent, "Preparando mensaje para $number.")
    }

    private fun searchWeb(query: String) {
        if (query.isBlank()) {
            reply("Dime qué quieres buscar.")
            return
        }

        val searchIntent = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
        if (canHandle(searchIntent)) {
            launch(searchIntent, "Buscando $query.")
        } else {
            launch(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")),
                "Buscando $query."
            )
        }
    }

    private fun setAlarm(command: String) {
        val match = Regex("(\\d{1,2})(?::(\\d{2}))?").find(command)
        val hour = match?.groupValues?.getOrNull(1)?.toIntOrNull()
        val minute = match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0

        if (hour == null || hour !in 0..23 || minute !in 0..59) {
            reply("Dime una hora, por ejemplo: pon alarma a las 7:30.")
            return
        }

        launch(Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis")
        }, "Preparando alarma para las %d:%02d.".format(hour, minute))
    }

    private fun createCalendarEvent(command: String) {
        val title = command.substringAfter("evento ").trim().ifBlank { "Evento de Jarvis" }
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
        }
        launch(intent, "Preparando el evento “$title”.")
    }

    private fun openApp(name: String) {
        if (name.isBlank()) {
            reply("Dime qué aplicación quieres abrir.")
            return
        }

        val normalized = name.lowercase(Locale.getDefault())
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = packageManager.queryIntentActivities(launcherIntent, 0).firstOrNull { info ->
            info.loadLabel(packageManager).toString().lowercase(Locale.getDefault()).contains(normalized)
        }
        val launchIntent = match?.activityInfo?.packageName?.let(packageManager::getLaunchIntentForPackage)

        if (launchIntent != null && match != null) {
            launch(launchIntent, "Abriendo ${match.loadLabel(packageManager)}.")
        } else {
            reply("No encontré una app llamada $name.")
        }
    }

    private fun checkForUpdates(showIfCurrent: Boolean) {
        status.text = "Buscando actualización…"
        uiScope.launch {
            val result = AppUpdateManager.checkLatest(this@MainActivity)
            status.text = "● EN LÍNEA"

            result.onSuccess { release ->
                if (release == null) {
                    if (showIfCurrent) reply("Ya tienes la versión más reciente de Jarvis.", speak = false)
                    return@onSuccess
                }

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Jarvis ${release.versionName} disponible")
                    .setMessage("Hay una nueva versión lista para instalar.")
                    .setNegativeButton("Después", null)
                    .setPositiveButton("Actualizar") { _, _ ->
                        AppUpdateManager.startDownload(this@MainActivity, release)
                    }
                    .show()
            }.onFailure {
                if (showIfCurrent) reply("No pude comprobar actualizaciones en este momento.", speak = false)
            }
        }
    }

    private fun launch(intent: Intent, message: String) {
        if (canHandle(intent)) {
            reply(message)
            startActivity(intent)
        } else {
            reply("No encontré una aplicación compatible para esa acción.")
        }
    }

    private fun canHandle(intent: Intent) = intent.resolveActivity(packageManager) != null

    private fun reply(message: String, speak: Boolean = true) {
        appendConversation("Jarvis", message)
        status.text = "● EN LÍNEA"
        if (speak) tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "jarvis-response")
    }

    private fun appendConversation(author: String, message: String) {
        val current = conversation.text?.toString().orEmpty()
        conversation.text = if (current.isBlank()) "$author: $message" else "$current\n\n$author: $message"
    }

    private fun resetListening() {
        listening = false
        status.text = "● EN LÍNEA"
        listenButton.text = "🎙 HABLAR"
    }

    override fun onInit(result: Int) {
        if (result == TextToSpeech.SUCCESS) tts?.language = Locale("es", "PE")
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        uiScope.cancel()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
