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
        val value: String = "",
        val reply: String = ""
    )

    private data class Turn(val user: String, val decision: Decision)

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val history = ArrayDeque<Turn>()
    private val allowedActions = setOf(
        "OPEN_APP", "CALL_CONTACT", "WHATSAPP_CHAT", "PLAY_YOUTUBE", "SPOTIFY_SEARCH",
        "TORCH_ON", "TORCH_OFF", "MEDIA_PAUSE", "MEDIA_PLAY", "MEDIA_NEXT", "MEDIA_PREVIOUS",
        "BATTERY", "NONE"
    )

    fun isConfigured(): Boolean = BuildConfig.GEMINI_API_KEY.isNotBlank()

    @Synchronized
    fun interpret(message: String): Result<Decision> = runCatching {
        require(BuildConfig.GEMINI_API_KEY.isNotBlank()) { "Gemini API key no configurada" }
        require(message.isNotBlank()) { "Mensaje vacío" }

        val system = """
            Eres Dronnk, un asistente Android en español. Debes interpretar la intención del usuario y decidir si hace falta ejecutar una acción del teléfono.
            Devuelve SOLO JSON válido, sin markdown ni texto adicional.
            Formato exacto: {"action":"...","value":"...","reply":"..."}

            Acciones permitidas:
            OPEN_APP, CALL_CONTACT, WHATSAPP_CHAT, PLAY_YOUTUBE, SPOTIFY_SEARCH,
            TORCH_ON, TORCH_OFF, MEDIA_PAUSE, MEDIA_PLAY, MEDIA_NEXT, MEDIA_PREVIOUS,
            BATTERY, NONE.

            Reglas:
            - Usa value solo para nombre de app, contacto o búsqueda cuando corresponda.
            - Para preguntas o conversación sin acción usa NONE y responde en reply.
            - Si el usuario habla de forma indirecta, infiere la acción más razonable. Ejemplo: “está muy oscuro” puede ser TORCH_ON.
            - No inventes acciones fuera de la lista.
            - No afirmes que una acción se ejecutó; solo decide qué acción debe ejecutar Dronnk.
            - Mantén reply breve y natural.
            - Usa el contexto reciente si el usuario responde con frases como “sí”, “esa”, “la anterior” o “hazlo”.
        """.trimIndent()

        val contents = JSONArray()
        history.forEach { turn ->
            contents.put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", turn.user)))
            })
            contents.put(JSONObject().apply {
                put("role", "model")
                put("parts", JSONArray().put(JSONObject().put(
                    "text",
                    JSONObject().apply {
                        put("action", turn.decision.action)
                        put("value", turn.decision.value)
                        put("reply", turn.decision.reply)
                    }.toString()
                )))
            })
        }
        contents.put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", message)))
        })

        val payload = JSONObject().apply {
            put("systemInstruction", JSONObject().put(
                "parts", JSONArray().put(JSONObject().put("text", system))
            ))
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
            val body = response.body?.string().orEmpty()
            val root = JSONObject(body)
            val text = root
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()

            val json = JSONObject(text)
            val action = json.optString("action", "NONE").uppercase().let {
                if (it in allowedActions) it else "NONE"
            }
            val decision = Decision(
                action = action,
                value = json.optString("value", "").trim(),
                reply = json.optString("reply", "").trim()
            )

            history.addLast(Turn(message, decision))
            while (history.size > 4) history.removeFirst()
            decision
        }
    }
}