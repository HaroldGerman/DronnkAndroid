package com.german.dronnk.ai

import com.german.dronnk.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

class DronnkBrain {

    data class Decision(
        val action: String,
        val app: String = "",
        val value: String = "",
        val target: String = "",
        val message: String = "",
        val reply: String = ""
    )

    private data class Turn(val user: String, val decision: Decision)

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val history = ArrayDeque<Turn>()
    private val allowedActions = setOf(
        "OPEN_APP", "CALL_CONTACT", "CALL_IN_APP", "OPEN_CHAT", "PREPARE_MESSAGE",
        "PLAY_YOUTUBE", "SPOTIFY_SEARCH", "TORCH_ON", "TORCH_OFF",
        "MEDIA_PAUSE", "MEDIA_PLAY", "MEDIA_NEXT", "MEDIA_PREVIOUS", "BATTERY", "NONE"
    )

    fun isConfigured(): Boolean = BuildConfig.GEMINI_API_KEY.isNotBlank()

    @Synchronized
    fun interpret(message: String): Result<Decision> = runCatching {
        require(BuildConfig.GEMINI_API_KEY.isNotBlank()) { "Gemini API key no configurada" }
        require(message.isNotBlank()) { "Mensaje vacío" }

        val system = """
            Eres Dronnk, un asistente Android general. Interpreta órdenes naturales y decide qué herramienta debe usar Dronnk.
            Devuelve SOLO JSON válido, sin markdown ni texto extra.

            Formato exacto:
            {"action":"...","app":"...","value":"...","target":"...","message":"...","reply":"..."}

            Acciones permitidas:
            OPEN_APP, CALL_CONTACT, CALL_IN_APP, OPEN_CHAT, PREPARE_MESSAGE,
            PLAY_YOUTUBE, SPOTIFY_SEARCH, TORCH_ON, TORCH_OFF,
            MEDIA_PAUSE, MEDIA_PLAY, MEDIA_NEXT, MEDIA_PREVIOUS, BATTERY, NONE.

            Reglas:
            - OPEN_APP: app o value = nombre de la aplicación.
            - CALL_CONTACT: target = persona o número para llamada telefónica normal.
            - CALL_IN_APP: app = aplicación solicitada; target = persona.
            - OPEN_CHAT: app = aplicación; target = persona/chat.
            - PREPARE_MESSAGE: app = aplicación; target = destinatario; message = texto exacto que quiere comunicar.
            - Si no se menciona una app para un mensaje, app="default".
            - PLAY_YOUTUBE y SPOTIFY_SEARCH: value = búsqueda.
            - NONE: conversación o pregunta sin acción del teléfono.
            - “escríbele”, “dile”, “mándale”, “avísale”, “respóndele” significan PREPARE_MESSAGE.
            - IG/insta significa Instagram. FB puede ser Facebook/Messenger según contexto.
            - Conserva nombres propios como fueron reconocidos; Android hará coincidencia fonética con contactos.
            - Usa contexto reciente para “a ella”, “hazlo”, “la misma”, “respóndele”, etc.
            - No inventes acciones fuera de la lista y no afirmes que ya se ejecutaron.
            - reply debe ser breve.
        """.trimIndent()

        val contents = JSONArray()
        history.forEach { turn ->
            contents.put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", turn.user)))
            })
            contents.put(JSONObject().apply {
                put("role", "model")
                put("parts", JSONArray().put(JSONObject().put("text", JSONObject().apply {
                    put("action", turn.decision.action)
                    put("app", turn.decision.app)
                    put("value", turn.decision.value)
                    put("target", turn.decision.target)
                    put("message", turn.decision.message)
                    put("reply", turn.decision.reply)
                }.toString())))
            })
        }
        contents.put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", message)))
        })

        val payload = JSONObject().apply {
            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            put("contents", contents)
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
                put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
            })
        }

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent")
            .header("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Gemini HTTP ${response.code}")
            val root = JSONObject(response.body?.string().orEmpty())
            val text = root.getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                .getString("text").trim()
            val json = JSONObject(text)
            val action = json.optString("action", "NONE").uppercase().let { if (it in allowedActions) it else "NONE" }
            val decision = Decision(
                action = action,
                app = json.optString("app", "").trim(),
                value = json.optString("value", "").trim(),
                target = json.optString("target", "").trim(),
                message = json.optString("message", "").trim(),
                reply = json.optString("reply", "").trim()
            )
            history.addLast(Turn(message, decision))
            while (history.size > 6) history.removeFirst()
            decision
        }
    }
}
