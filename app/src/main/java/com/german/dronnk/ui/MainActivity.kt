package com.german.dronnk.ui

import android.Manifest
import android.app.AlarmClockInfo
import android.app.SearchManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.provider.AlarmClock
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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var statusText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var input: EditText
    private lateinit var listenButton: Button
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var listening = false

    private val audioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListening() else respond("Necesito permiso de micrófono para escucharte. También puedes escribirme.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        buildUi()
        setupSpeechRecognizer()
        respond("Jarvis en línea. Dime qué necesitas.", speak = false)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
            setBackgroundColor(Color.rgb(7, 11, 18))
        }

        val title = TextView(this).apply {
            text = "JARVIS"
            textSize = 34f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val subtitle = TextView(this).apply {
            text = "Asistente personal"
            textSize = 15f
            setTextColor(Color.rgb(127, 211, 255))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, dp(24))
        }
        statusText = TextView(this).apply {
            text = "● EN LÍNEA"
            textSize = 13f
            setTextColor(Color.rgb(112, 240, 184))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(18))
        }
        transcriptText = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(17, 26, 39))
            minHeight = dp(150)
        }

        val scroll = ScrollView(this).apply {
            addView(transcriptText)
        }
        root.addView(title)
        root.addView(subtitle)
        root.addView(statusText)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        input = EditText(this).apply {
            hint = "Escribe una orden…"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.rgb(25, 35, 50))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    submitTypedCommand()
                    true
                } else false
            }
        }
        root.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listenButton = Button(this).apply {
            text = "🎙 HABLAR"
            setOnClickListener { toggleListening() }
        }
        val sendButton = Button(this).apply {
            text = "ENVIAR"
            setOnClickListener { submitTypedCommand() }
        }
        buttons.addView(listenButton, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(6) })
        buttons.addView(sendButton, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginStart = dp(6) })
        root.addView(buttons, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        val examples = TextView(this).apply {
            text = "Prueba: “abre WhatsApp”, “abre la cámara”, “configuración”, “busca restaurantes en Lima”, “llama al 999…”, “navega a Miraflores”, “pon alarma a las 7:30”, “qué hora es”, “cuánta batería tengo”."
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(14), 0, 0)
        }
        root.addView(examples)
        setContentView(root)
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusText.text = "Voz no disponible en este dispositivo"
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    listening = true
                    statusText.text = "● ESCUCHANDO"
                    listenButton.text = "■ DETENER"
                }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    statusText.text = "Procesando…"
                }
                override fun onError(error: Int) {
                    resetListeningUi()
                    if (error != SpeechRecognizer.ERROR_CLIENT && error != SpeechRecognizer.ERROR_NO_MATCH) {
                        respond("No pude entenderte. Inténtalo otra vez.", speak = false)
                    }
                }
                override fun onResults(results: Bundle?) {
                    resetListeningUi()
                    val command = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!command.isNullOrBlank()) execute(command)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                        transcriptText.text = "Tú: $it"
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
    }

    private fun toggleListening() {
        if (listening) {
            speechRecognizer?.stopListening()
            resetListeningUi()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else startListening()
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-PE")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun submitTypedCommand() {
        val command = input.text.toString().trim()
        if (command.isNotEmpty()) {
            input.setText("")
            execute(command)
        }
    }

    private fun execute(raw: String) {
        val command = raw.trim()
        val q = command.lowercase(Locale.getDefault())
        transcriptText.text = "Tú: $command"

        when {
            q == "hola" || q.contains("hola jarvis") -> respond("Hola. Estoy listo.")
            q.contains("qué hora") || q.contains("que hora") -> {
                val now = SimpleDateFormat("h:mm a", Locale("es", "PE")).format(Date())
                respond("Son las $now.")
            }
            q.contains("batería") || q.contains("bateria") -> {
                val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
                respond("Tienes ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)} por ciento de batería.")
            }
            q.contains("configuración") || q.contains("configuracion") || q == "ajustes" -> {
                launch(Intent(Settings.ACTION_SETTINGS), "Abriendo configuración.")
            }
            q.contains("wifi") || q.contains("wi-fi") -> launch(Intent(Settings.ACTION_WIFI_SETTINGS), "Abriendo Wi-Fi.")
            q.contains("bluetooth") -> launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "Abriendo Bluetooth.")
            q.contains("cámara") || q.contains("camara") -> launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "Abriendo cámara.")
            q.startsWith("llama a ") || q.startsWith("llamar a ") -> {
                val target = command.substringAfter(" a ").trim()
                val digits = target.filter { it.isDigit() || it == '+' }
                if (digits.length >= 5) launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits")), "Preparando llamada a $digits.")
                else respond("Dime el número completo. Abriré el marcador para que tú confirmes la llamada.")
            }
            q.startsWith("navega a ") || q.startsWith("llévame a ") || q.startsWith("llevame a ") -> {
                val place = command.substringAfter(" a ").trim()
                launch(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(place)}")), "Abriendo navegación a $place.")
            }
            q.startsWith("busca ") || q.startsWith("buscar ") -> {
                val query = command.substringAfter(" ").trim()
                val intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
                if (!canHandle(intent)) {
                    launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")), "Buscando $query.")
                } else launch(intent, "Buscando $query.")
            }
            q.contains("alarma") -> setAlarm(command)
            q.startsWith("abre ") || q.startsWith("abrir ") -> openApp(command.substringAfter(" ").trim())
            else -> respond("Todavía no tengo una acción local para “$command”. Puedo abrir apps y ajustes, buscar, navegar, preparar llamadas, crear alarmas y consultar el dispositivo.")
        }
    }

    private fun setAlarm(command: String) {
        val match = Regex("(\\d{1,2})(?::(\\d{2}))?").find(command)
        val hour = match?.groupValues?.getOrNull(1)?.toIntOrNull()
        val minute = match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
        if (hour == null || hour !in 0..23 || minute !in 0..59) {
            respond("Dime una hora, por ejemplo: pon alarma a las 7:30.")
            return
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis")
        }
        launch(intent, "Preparando alarma para las %d:%02d.".format(hour, minute))
    }

    private fun openApp(name: String) {
        val normalized = name.lowercase(Locale.getDefault())
        val apps = packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        val match = apps.firstOrNull {
            packageManager.getApplicationLabel(it).toString().lowercase(Locale.getDefault()).contains(normalized)
        }
        val intent = match?.let { packageManager.getLaunchIntentForPackage(it.packageName) }
        if (intent != null) launch(intent, "Abriendo ${packageManager.getApplicationLabel(match)}.")
        else respond("No encontré una app llamada $name.")
    }

    private fun launch(intent: Intent, message: String) {
        if (canHandle(intent)) {
            respond(message)
            startActivity(intent)
        } else respond("No encontré una aplicación compatible para esa acción.")
    }

    private fun canHandle(intent: Intent): Boolean = intent.resolveActivity(packageManager) != null

    private fun respond(message: String, speak: Boolean = true) {
        transcriptText.text = "${transcriptText.text}\n\nJarvis: $message"
        statusText.text = "● EN LÍNEA"
        if (speak) tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "jarvis-response")
    }

    private fun resetListeningUi() {
        listening = false
        statusText.text = "● EN LÍNEA"
        listenButton.text = "🎙 HABLAR"
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale("es", "PE")
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
