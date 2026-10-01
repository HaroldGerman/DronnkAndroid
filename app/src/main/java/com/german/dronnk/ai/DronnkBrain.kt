package com.german.dronnk.ai

import com.german.dronnk.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class DronnkBrain {

    data class Decision(
        val action: String,
        val value: String = "",
        val reply: String = ""
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun isConfigured(): Boolean = BuildConfig.GEMINI_API_KEY.isNotBlank()

    fun interpret(message: String): Result<Decision> = runCatching {
        require(BuildConfig.GEMINI_API_KEY.isNotBlank()) { "Gemini API key no configurada" }

        val system = """
            Eres Dronnk, un asistente Android en español. Interpreta la orden del usuario y devuelve SOLO JSON válido, sin markdown.
            Formato exacto: {"action":"...","value":"...","reply":"..."}
            Acciones permitidas:
            OPEN_APP, CALL_CONTACT, WHATSAPP_CHAT, PLAY_YOUTUBE, SPOTIFY_SEARCH,
            TORCH_ON, TORCH_OFF, MEDIA_PAUSE, MEDIA_PLAY, MEDIA_NEXT, MEDIA_PREVIOUS,
            BATTERY, NONE.
            value contiene el nombre de app, contacto o búsqueda cuando corresponda.
            reply debe ser breve y natural. No inventes acciones fuera de la lista.
            Si es una pregunta o conversación sin acción del teléfono usa NONE y responde en reply.
        """.trimIndent()

        val payload = JSONObject().apply {
            put("systemInstruction", JSONObject().put(
                "parts", JSONArray().put(JSONObject().put("text", system))
            ))
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", message)))
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.2)
                put("responseMimeType", "application/json")
            })
        }

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent?key=${BuildConfig.GEMINI_API_KEY}")
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
            Decision(
                action = json.optString("action", "NONE").uppercase(),
                value = json.optString("value", "").trim(),
                reply = json.optString("reply", "").trim()
            )
        }
    }
}
