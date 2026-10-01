package com.german.dronnk.ui

import android.Manifest
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListening()
        else reply("Necesito permiso de micrófono para escucharte. También puedes escribirme.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        createInterface()
        configureSpeechRecognition()
        reply("Jarvis en línea. Dime qué necesitas.", speak = false)
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
            text = "Asistente personal"
            textSize = 15f
            setTextColor(Color.rgb(127, 211, 255))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(16))
        })
        status = TextView(this).apply {
            text = "● EN LÍNEA"
            textSize = 13f
            setTextColor(Color.rgb(112, 240, 184))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(18))
        }
        root.addView(status)
        conversation = TextView(this).apply {
            textSize = 18f
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
        root.addView(TextView(this).apply {
            text = "Prueba: abre WhatsApp · abre la cámara · Wi-Fi · busca restaurantes · navega a Miraflores · llama al 999… · pon alarma a las 7:30 · qué hora es · batería"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(14), 0, 0)
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
                        ?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(::execute)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.let { conversation.text = "Tú: $it" }
                }
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
        } else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
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
        conversation.text = "Tú: $command"
        when {
            q == "hola" || q.contains("hola jarvis") -> reply("Hola. Estoy listo.")
            q.contains("qué hora") || q.contains("que hora") -> reply(
                "Son las ${SimpleDateFormat("h:mm a", Locale("es", "PE")).format(Date())}."
            )
            q.contains("batería") || q.contains("bateria") -> {
                val battery = getSystemService(BATTERY_SERVICE) as BatteryManager
                reply("Tienes ${battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)} por ciento de batería.")
            }
            q.contains("configuración") || q.contains("configuracion") || q == "ajustes" ->
                launch(Intent(Settings.ACTION_SETTINGS), "Abriendo configuración.")
            q.contains("wifi") || q.contains("wi-fi") ->
                launch(Intent(Settings.ACTION_WIFI_SETTINGS), "Abriendo Wi-Fi.")
            q.contains("bluetooth") ->
                launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "Abriendo Bluetooth.")
            q.contains("cámara") || q.contains("camara") ->
                launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "Abriendo cámara.")
            q.startsWith("llama a ") || q.startsWith("llamar a ") -> prepareCall(command)
            q.startsWith("navega a ") || q.startsWith("llévame a ") || q.startsWith("llevame a ") -> {
                val place = command.substringAfter(" a ").trim()
                launch(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(place)}")), "Abriendo navegación a $place.")
            }
            q.startsWith("busca ") || q.startsWith("buscar ") -> searchWeb(command.substringAfter(" ").trim())
            q.contains("alarma") -> setAlarm(command)
            q.startsWith("abre ") || q.startsWith("abrir ") -> openApp(command.substringAfter(" ").trim())
            else -> reply("Todavía no tengo una acción local para “$command”. Puedo abrir apps y ajustes, buscar, navegar, preparar llamadas, crear alarmas y consultar tu dispositivo.")
        }
    }

    private fun prepareCall(command: String) {
        val digits = command.substringAfter(" a ").filter { it.isDigit() || it == '+' }
        if (digits.length < 5) reply("Dime el número completo.")
        else launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits")), "Preparando llamada a $digits.")
    }

    private fun searchWeb(query: String) {
        val searchIntent = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
        if (canHandle(searchIntent)) launch(searchIntent, "Buscando $query.")
        else launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")), "Buscando $query.")
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

    @Suppress("DEPRECATION")
    private fun openApp(name: String) {
        val normalized = name.lowercase(Locale.getDefault())
        val app = packageManager.getInstalledApplications(0).firstOrNull {
            packageManager.getApplicationLabel(it).toString().lowercase(Locale.getDefault()).contains(normalized)
        }
        val launchIntent = app?.let { packageManager.getLaunchIntentForPackage(it.packageName) }
        if (launchIntent != null && app != null) launch(launchIntent, "Abriendo ${packageManager.getApplicationLabel(app)}.")
        else reply("No encontré una app llamada $name.")
    }

    private fun launch(intent: Intent, message: String) {
        if (canHandle(intent)) {
            reply(message)
            startActivity(intent)
        } else reply("No encontré una aplicación compatible para esa acción.")
    }

    private fun canHandle(intent: Intent) = intent.resolveActivity(packageManager) != null

    private fun reply(message: String, speak: Boolean = true) {
        conversation.text = "${conversation.text}\n\nJarvis: $message"
        status.text = "● EN LÍNEA"
        if (speak) tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "jarvis-response")
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
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
