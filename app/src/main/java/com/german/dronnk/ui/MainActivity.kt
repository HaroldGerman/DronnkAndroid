package com.german.dronnk.ui

import android.Manifest
import android.app.AlertDialog
import android.app.SearchManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
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

    private val bg = Color.rgb(7, 5, 6)
    private val surface = Color.rgb(20, 14, 16)
    private val surface2 = Color.rgb(34, 18, 22)
    private val scarlet = Color.rgb(215, 38, 56)
    private val scarletSoft = Color.rgb(255, 70, 90)
    private val muted = Color.rgb(180, 163, 168)

    private lateinit var contentHost: LinearLayout
    private var statusView: TextView? = null
    private var conversationView: TextView? = null
    private var inputView: EditText? = null
    private var listenButton: Button? = null

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var listening = false
    private var pendingContactCommand: String? = null
    private var activeTab = "Inicio"
    private val conversationLog = mutableListOf<Pair<String, String>>()
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else reply("Necesito permiso de micrófono para escucharte. También puedes escribirme.")
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) setTorch(true) else reply("Necesito permiso de cámara para controlar la linterna.")
    }

    private val contactsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val command = pendingContactCommand
        pendingContactCommand = null
        if (granted && command != null) execute(command, appendUser = false)
        else if (!granted) reply("Necesito permiso de contactos para buscar personas por nombre.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        createShell()
        configureSpeechRecognition()
        showHome()
        reply("Dronnk en línea. Dime qué necesitas.", speak = false)
        checkForUpdates(false)
    }

    private fun createShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        root.addView(createHeader())
        contentHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        root.addView(contentHost, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(createBottomNav())
        setContentView(root)
    }

    private fun createHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(18), dp(20), dp(10))
        addView(TextView(this@MainActivity).apply {
            text = "DRONNK"
            textSize = 30f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.18f
            gravity = Gravity.CENTER
        })
        addView(TextView(this@MainActivity).apply {
            text = "Asistente personal · v${BuildConfig.VERSION_NAME}"
            textSize = 12f
            setTextColor(scarletSoft)
            gravity = Gravity.CENTER
        })
    }

    private fun createBottomNav(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(10))
            setBackgroundColor(Color.rgb(11, 8, 9))
        }
        listOf("Inicio", "Herramientas", "Conversación", "Ajustes").forEach { label ->
            nav.addView(Button(this).apply {
                text = label
                textSize = 10f
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = rounded(if (label == "Inicio") surface2 else surface, 14f)
                setOnClickListener {
                    activeTab = label
                    when (label) {
                        "Inicio" -> showHome()
                        "Herramientas" -> showTools()
                        "Conversación" -> showConversation()
                        else -> showSettings()
                    }
                    refreshNav(nav)
                }
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        return nav
    }

    private fun refreshNav(nav: LinearLayout) {
        for (i in 0 until nav.childCount) {
            val button = nav.getChildAt(i) as Button
            val selected = button.text.toString() == activeTab
            button.background = rounded(if (selected) surface2 else surface, 14f)
            button.setTextColor(if (selected) scarletSoft else Color.WHITE)
        }
    }

    private fun showHome() {
        activeTab = "Inicio"
        clearContent()
        contentHost.gravity = Gravity.CENTER_HORIZONTAL
        contentHost.addView(TextView(this).apply {
            text = "◉"
            textSize = 104f
            gravity = Gravity.CENTER
            setTextColor(scarlet)
        })
        statusView = TextView(this).apply {
            text = "● EN LÍNEA"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(scarletSoft)
        }
        contentHost.addView(statusView)
        contentHost.addView(TextView(this).apply {
            text = "Hola, ¿en qué puedo ayudarte?"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(18), 0, dp(18))
        })
        listenButton = Button(this).apply {
            text = "🎙  Toca para hablar"
            textSize = 16f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = roundedStroke(surface2, scarlet, 28f, 2)
            setOnClickListener { toggleListening() }
        }
        contentHost.addView(listenButton, LinearLayout.LayoutParams(-1, dp(62)).apply { setMargins(dp(12), 0, dp(12), dp(14)) })
        contentHost.addView(TextView(this).apply {
            text = "Prueba: “abre WhatsApp y entra al chat de Mirella”, “pon Dash Berlin en Spotify”, “llama a mamá” o “llévame a Miraflores”."
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setPadding(dp(12), dp(8), dp(12), 0)
        })
    }

    private fun showTools() {
        clearContent()
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(sectionTitle("Herramientas"))
        val tools = listOf(
            "📱 Abrir aplicaciones" to "abre WhatsApp",
            "💬 WhatsApp" to "abre WhatsApp",
            "☎ Llamadas" to "llama a mamá",
            "🎵 Spotify" to "pon Dash Berlin en Spotify",
            "📷 Cámara" to "abre cámara",
            "🔦 Linterna" to "enciende la linterna",
            "🔊 Volumen" to "sube el volumen",
            "Wi‑Fi" to "wifi",
            "Bluetooth" to "bluetooth",
            "📍 Navegación" to "llévame a Miraflores",
            "⏰ Alarmas" to "pon alarma a las 7:30",
            "🔋 Batería" to "batería"
        )
        tools.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { (label, command) ->
                row.addView(toolCard(label) { execute(command) }, LinearLayout.LayoutParams(0, dp(92), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            list.addView(row)
        }
        scroll.addView(list)
        contentHost.addView(scroll, LinearLayout.LayoutParams(-1, -1))
    }

    private fun showConversation() {
        clearContent()
        contentHost.addView(sectionTitle("Conversación"))
        conversationView = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(surface, 18f)
        }
        refreshConversation()
        contentHost.addView(ScrollView(this).apply { addView(conversationView) }, LinearLayout.LayoutParams(-1, 0, 1f))
        inputView = EditText(this).apply {
            hint = "Escribe una orden…"
            setHintTextColor(muted)
            setTextColor(Color.WHITE)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = rounded(surface2, 18f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { submitText(); true } else false
            }
        }
        contentHost.addView(inputView, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(10) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listenButton = Button(this).apply {
            text = "🎙 HABLAR"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(surface2, 18f)
            setOnClickListener { toggleListening() }
        }
        val send = Button(this).apply {
            text = "ENVIAR"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(scarlet, 18f)
            setOnClickListener { submitText() }
        }
        row.addView(listenButton, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(5) })
        row.addView(send, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(5) })
        contentHost.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    private fun showSettings() {
        clearContent()
        contentHost.addView(sectionTitle("Ajustes"))
        addSetting("Voz y lenguaje", "Español (Perú)") { launch(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS), "Abriendo ajustes de voz.") }
        addSetting("Permisos", "Micrófono, cámara y contactos") {
            launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")), "Abriendo permisos de Dronnk.")
        }
        addSetting("Apariencia", "Negro + escarlata") { reply("El tema escarlata de Dronnk está activo.", false) }
        addSetting("Actualizaciones", "Versión ${BuildConfig.VERSION_NAME}") { checkForUpdates(true) }
        addSetting("Acerca de", "Dronnk Assistant") { reply("Dronnk es tu asistente personal para Android.", false) }
    }

    private fun addSetting(title: String, subtitle: String, action: () -> Unit) {
        contentHost.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, 18f)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { action() }
            addView(TextView(this@MainActivity).apply { text = title; textSize = 16f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
            addView(TextView(this@MainActivity).apply { text = subtitle; textSize = 12f; setTextColor(muted) })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
    }

    private fun toolCard(label: String, action: () -> Unit): View = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = rounded(surface, 18f)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setOnClickListener { action() }
    }

    private fun sectionTitle(value: String): View = TextView(this).apply {
        text = value
        textSize = 24f
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(4), dp(4), 0, dp(14))
    }

    private fun clearContent() {
        contentHost.removeAllViews()
        statusView = null
        conversationView = null
        inputView = null
        listenButton = null
    }

    private fun configureSpeechRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { listening = true; statusView?.text = "● ESCUCHANDO"; listenButton?.text = "■ DETENER" }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { statusView?.text = "Procesando…" }
                override fun onError(error: Int) { resetListening(); if (error != SpeechRecognizer.ERROR_CLIENT && error != SpeechRecognizer.ERROR_NO_MATCH) reply("No pude entenderte. Inténtalo otra vez.", false) }
                override fun onResults(results: Bundle?) { resetListening(); results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(::execute) }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
    }

    private fun toggleListening() {
        if (listening) { recognizer?.stopListening(); resetListening(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening()
        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
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
        inputView?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { inputView?.setText(""); execute(it) }
    }

    private fun execute(command: String, appendUser: Boolean = true) {
        val q = command.lowercase(Locale.getDefault()).trim()
        if (appendUser) appendConversation("Tú", command)

        when {
            isWhatsAppChatCommand(q) -> openWhatsAppChat(command)
            q == "hola" || q.contains("hola dronnk") -> reply("Hola. Estoy listo.")
            q.contains("qué hora") || q.contains("que hora") -> reply("Son las ${SimpleDateFormat("h:mm a", Locale("es", "PE")).format(Date())}.")
            q.contains("qué fecha") || q.contains("que fecha") || q.contains("qué día") || q.contains("que dia") -> reply("Hoy es ${SimpleDateFormat("EEEE d 'de' MMMM", Locale("es", "PE")).format(Date())}.")
            q.contains("batería") || q.contains("bateria") -> {
                val battery = getSystemService(BATTERY_SERVICE) as BatteryManager
                reply("Tienes ${battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)} por ciento de batería.")
            }
            q.contains("enciende la linterna") || q.contains("prende la linterna") -> requestTorchOn()
            q.contains("apaga la linterna") -> setTorch(false)
            q.contains("sube el volumen") -> changeVolume(AudioManager.ADJUST_RAISE, "Subiendo el volumen.")
            q.contains("baja el volumen") -> changeVolume(AudioManager.ADJUST_LOWER, "Bajando el volumen.")
            q.contains("silencia") || q.contains("silencio") -> changeVolume(AudioManager.ADJUST_MUTE, "Silenciando el audio multimedia.")
            q.contains("configuración") || q.contains("configuracion") || q == "ajustes" -> launch(Intent(Settings.ACTION_SETTINGS), "Abriendo configuración.")
            q.contains("wifi") || q.contains("wi-fi") -> launch(Intent(Settings.ACTION_WIFI_SETTINGS), "Abriendo Wi-Fi.")
            q.contains("bluetooth") -> launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "Abriendo Bluetooth.")
            q.contains("cámara") || q.contains("camara") -> launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "Abriendo cámara.")
            q.startsWith("llama a ") || q.startsWith("llamar a ") -> prepareCall(command)
            q.startsWith("mensaje a ") || q.startsWith("mensaje al ") -> prepareSms(command)
            q.startsWith("pon ") || q.startsWith("reproduce ") || q.startsWith("reproducir ") -> playExternalMusic(command)
            q.startsWith("navega a ") || q.startsWith("llévame a ") || q.startsWith("llevame a ") -> navigate(command)
            q.startsWith("busca ") || q.startsWith("buscar ") -> searchWeb(command.substringAfter(" ").trim())
            q.contains("alarma") -> setAlarm(command)
            q.startsWith("crea evento ") || q.startsWith("crear evento ") -> createCalendarEvent(command)
            q.contains("actualización") || q.contains("actualizacion") || q == "actualiza" -> checkForUpdates(true)
            q == "abre wsp" || q == "abre whatsapp" || q == "abrir whatsapp" -> openPackage("com.whatsapp", "WhatsApp")
            q.startsWith("abre ") || q.startsWith("abrir ") -> openApp(command.substringAfter(" ").trim())
            else -> reply("No entendí esa orden todavía. Prueba con abrir una app, entrar a un chat de WhatsApp, llamar a un contacto, reproducir algo en Spotify, navegar, usar la linterna o crear una alarma.")
        }
    }

    private fun isWhatsAppChatCommand(q: String): Boolean {
        val mentionsWhatsApp = q.contains("whatsapp") || Regex("\\bwsp\\b").containsMatchIn(q)
        val mentionsChat = q.contains("chat") || q.contains("conversación") || q.contains("conversacion") || q.contains("mensaje") || q.contains("mándale") || q.contains("mandale")
        val genericChat = q.startsWith("abre el chat de ") || q.startsWith("entra al chat de ") || q.startsWith("ve al chat de ")
        return genericChat || (mentionsWhatsApp && mentionsChat)
    }

    private fun extractWhatsAppContact(command: String): String {
        val patterns = listOf(
            Regex("(?i)(?:chat|conversaci[oó]n)\\s+(?:de|con)\\s+(.+?)(?:\\s+en\\s+(?:whatsapp|wsp))?$"),
            Regex("(?i)(?:whatsapp|wsp)\\s+(?:a|con)\\s+(.+)$"),
            Regex("(?i)(?:mensaje|m[aá]ndale|mandale)\\s+(?:por\\s+)?(?:whatsapp|wsp)?\\s*(?:a)?\\s+(.+)$")
        )
        patterns.forEach { pattern -> pattern.find(command.trim())?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let { return it } }
        return ""
    }

    private fun openWhatsAppChat(command: String) {
        val name = extractWhatsAppContact(command)
        if (name.isBlank()) { openPackage("com.whatsapp", "WhatsApp"); return }
        withContactPermission(command) {
            val contact = findContact(name)
            if (contact == null) { reply("No encontré a $name en tus contactos."); return@withContactPermission }
            val phone = normalizeWhatsAppNumber(contact.second)
            if (phone.isBlank()) { reply("El contacto ${contact.first} no tiene un número válido para WhatsApp."); return@withContactPermission }
            val uri = Uri.parse("https://wa.me/$phone")
            val normal = Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp")
            val business = Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp.w4b")
            when {
                canHandle(normal) -> launch(normal, "Abriendo el chat de ${contact.first} en WhatsApp.")
                canHandle(business) -> launch(business, "Abriendo el chat de ${contact.first} en WhatsApp Business.")
                else -> reply("No encontré WhatsApp instalado.")
            }
        }
    }

    private fun normalizeWhatsAppNumber(raw: String): String {
        val hadPlus = raw.trim().startsWith("+")
        var digits = raw.filter(Char::isDigit)
        if (!hadPlus && digits.length == 9) digits = "51$digits"
        return digits
    }

    private fun prepareCall(command: String) {
        val target = command.substringAfter(" a ").trim()
        val directNumber = target.filter { it.isDigit() || it == '+' }
        if (directNumber.length >= 5 && directNumber.length >= target.length - 2) {
            launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$directNumber")), "Preparando llamada.")
            return
        }
        withContactPermission(command) {
            val contact = findContact(target)
            if (contact == null) reply("No encontré a $target en tus contactos.")
            else launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(contact.second)}")), "Preparando llamada a ${contact.first}.")
        }
    }

    private fun prepareSms(command: String) {
        val after = command.substringAfter(" a ").substringAfter(" al ").trim()
        val number = after.takeWhile { it.isDigit() || it == '+' }
        if (number.length >= 5) { openSms(number, after.drop(number.length).trim()); return }
        val lower = after.lowercase(Locale.getDefault())
        val split = lower.indexOf(" diciendo ").takeIf { it >= 0 } ?: lower.indexOf(" mensaje ").takeIf { it >= 0 }
        val name = if (split != null) after.substring(0, split).trim() else after
        val body = if (split != null) after.substring(split).substringAfter(' ').trim() else ""
        withContactPermission(command) {
            val contact = findContact(name)
            if (contact == null) reply("No encontré a $name en tus contactos.") else openSms(contact.second, body)
        }
    }

    private fun withContactPermission(command: String, action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) action()
        else { pendingContactCommand = command; contactsPermission.launch(Manifest.permission.READ_CONTACTS) }
    }

    private fun findContact(name: String): Pair<String, String>? {
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0) to cursor.getString(1)
        }
        return null
    }

    private fun openSms(number: String, message: String) {
        launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")).apply {
            if (message.isNotBlank()) putExtra("sms_body", message)
        }, "Preparando mensaje.")
    }

    private fun playExternalMusic(command: String) {
        val q = command.lowercase(Locale.getDefault())
        val provider = when {
            q.contains("youtube music") -> "youtube_music"
            q.contains("youtube") -> "youtube"
            else -> "spotify"
        }
        val query = command
            .replace(Regex("(?i)^(pon|reproduce|reproducir)\\s+"), "")
            .replace(Regex("(?i)\\s+(en|desde)\\s+(spotify|youtube music|youtube).*$"), "")
            .replace(Regex("(?i)^m[uú]sica\\s*"), "")
            .trim()

        when (provider) {
            "spotify" -> playOnSpotify(query)
            "youtube_music" -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=${Uri.encode(query)}")).setPackage("com.google.android.apps.youtube.music")
                if (canHandle(intent)) launch(intent, "Buscando $query en YouTube Music.")
                else launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=${Uri.encode(query)}")), "Buscando $query en YouTube Music.")
            }
            else -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")).setPackage("com.google.android.youtube")
                if (canHandle(intent)) launch(intent, "Buscando $query en YouTube.")
                else launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")), "Buscando $query en YouTube.")
            }
        }
    }

    private fun playOnSpotify(query: String) {
        val clean = query.ifBlank { "música" }
        val playIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage("com.spotify.music")
            putExtra(SearchManager.QUERY, clean)
        }
        if (canHandle(playIntent)) {
            launch(playIntent, "Reproduciendo $clean en Spotify.")
            return
        }
        val searchIntent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:${Uri.encode(clean)}")).setPackage("com.spotify.music")
        if (canHandle(searchIntent)) launch(searchIntent, "Spotify no aceptó reproducción directa; abriendo la búsqueda de $clean.")
        else launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/${Uri.encode(clean)}")), "Abriendo Spotify para buscar $clean.")
    }

    private fun navigate(command: String) {
        val place = command.substringAfter(" a ").trim()
        val maps = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(place)}"))
        if (canHandle(maps)) launch(maps, "Abriendo navegación a $place.")
        else launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(place)}")), "Buscando $place en mapas.")
    }

    private fun requestTorchOn() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) setTorch(true)
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun setTorch(enabled: Boolean) {
        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        val cameraId = runCatching {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
        if (cameraId == null) { reply("No encontré una linterna disponible."); return }
        runCatching { cameraManager.setTorchMode(cameraId, enabled) }
            .onSuccess { reply(if (enabled) "Linterna encendida." else "Linterna apagada.") }
            .onFailure { reply("No pude cambiar la linterna.") }
    }

    private fun changeVolume(direction: Int, message: String) {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        runCatching { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI) }
            .onSuccess { reply(message) }
            .onFailure { reply("No pude cambiar el volumen.") }
    }

    private fun searchWeb(query: String) {
        if (query.isBlank()) { reply("Dime qué quieres buscar."); return }
        val intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
        if (canHandle(intent)) launch(intent, "Buscando $query.")
        else launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")), "Buscando $query.")
    }

    private fun setAlarm(command: String) {
        val match = Regex("(\\d{1,2})(?::(\\d{2}))?").find(command)
        val hour = match?.groupValues?.getOrNull(1)?.toIntOrNull()
        val minute = match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
        if (hour == null || hour !in 0..23 || minute !in 0..59) { reply("Dime una hora, por ejemplo: pon alarma a las 7:30."); return }
        launch(Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, "Dronnk")
        }, "Preparando alarma para las %d:%02d.".format(hour, minute))
    }

    private fun createCalendarEvent(command: String) {
        val title = command.substringAfter("evento ").trim().ifBlank { "Evento de Dronnk" }
        launch(Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
        }, "Preparando el evento “$title”.")
    }

    private fun openPackage(packageName: String, label: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) launch(intent, "Abriendo $label.") else reply("No encontré $label instalado.")
    }

    private fun openApp(name: String) {
        if (name.isBlank()) { reply("Dime qué aplicación quieres abrir."); return }
        val aliases = mapOf("wsp" to "whatsapp", "yt" to "youtube", "spoty" to "spotify")
        val normalized = aliases[name.lowercase(Locale.getDefault())] ?: name.lowercase(Locale.getDefault())
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = packageManager.queryIntentActivities(launcherIntent, 0).firstOrNull {
            it.loadLabel(packageManager).toString().lowercase(Locale.getDefault()).contains(normalized)
        }
        val launchIntent = match?.activityInfo?.packageName?.let(packageManager::getLaunchIntentForPackage)
        if (launchIntent != null && match != null) launch(launchIntent, "Abriendo ${match.loadLabel(packageManager)}.")
        else reply("No encontré una app llamada $name.")
    }

    private fun checkForUpdates(showIfCurrent: Boolean) {
        statusView?.text = "Buscando actualización…"
        uiScope.launch {
            val result = AppUpdateManager.checkLatest(this@MainActivity)
            statusView?.text = "● EN LÍNEA"
            result.onSuccess { release ->
                if (release == null) { if (showIfCurrent) reply("Ya tienes la versión más reciente de Dronnk.", false); return@onSuccess }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Dronnk ${release.versionName} disponible")
                    .setMessage("Hay una nueva versión lista para instalar.")
                    .setNegativeButton("Después", null)
                    .setPositiveButton("Actualizar") { _, _ -> AppUpdateManager.startDownload(this@MainActivity, release) }
                    .show()
            }.onFailure { if (showIfCurrent) reply("No pude comprobar actualizaciones en este momento.", false) }
        }
    }

    private fun launch(intent: Intent, message: String) {
        if (canHandle(intent)) { reply(message); startActivity(intent) } else reply("No encontré una aplicación compatible para esa acción.")
    }

    private fun canHandle(intent: Intent) = intent.resolveActivity(packageManager) != null

    private fun reply(message: String, speak: Boolean = true) {
        appendConversation("Dronnk", message)
        statusView?.text = "● EN LÍNEA"
        if (speak) tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "dronnk-response")
    }

    private fun appendConversation(author: String, message: String) { conversationLog += author to message; refreshConversation() }
    private fun refreshConversation() { conversationView?.text = conversationLog.joinToString("\n\n") { (author, message) -> "$author: $message" } }
    private fun resetListening() { listening = false; statusView?.text = "● EN LÍNEA"; listenButton?.text = "🎙  Toca para hablar" }

    override fun onInit(result: Int) { if (result == TextToSpeech.SUCCESS) tts?.language = Locale("es", "PE") }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        uiScope.cancel()
        super.onDestroy()
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius.toInt()).toFloat() }
    private fun roundedStroke(color: Int, strokeColor: Int, radius: Float, strokeWidth: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius.toInt()).toFloat(); setStroke(dp(strokeWidth), strokeColor) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
