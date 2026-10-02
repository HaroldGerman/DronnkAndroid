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
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val history = ArrayDeque<Turn>()
    private val allowedActions = setOf(
        "OPEN_APP", "CALL_CONTACT", "CALL_IN_APP", "OPEN_CHAT", "PREPARE_MESSAGE",
        "PLAY_MEDIA", "TORCH_ON", "TORCH_OFF",
        "MEDIA_PAUSE", "MEDIA_PLAY", "MEDIA_NEXT", "MEDIA_PREVIOUS", "BATTERY", "NONE"
    )

    fun isConfigured(): Boolean = BuildConfig.GEMINI_API_KEY.isNotBlank()

    @Synchronized
    fun interpret(message: String): Result<Decision> = runCatching {
        require(message.isNotBlank()) { "Mensaje vacío" }

        deterministicDecision(message)?.let { decision ->
            remember(message, decision)
            return@runCatching decision
        }

        require(BuildConfig.GEMINI_API_KEY.isNotBlank()) { "Gemini API key no configurada" }

        val system = """
            Eres Dronnk, el cerebro de un asistente Android general. Convierte lenguaje natural en UNA acción estructurada.
            No estás especializado en YouTube: debes respetar siempre la aplicación que el usuario mencione.

            Acciones:
            OPEN_APP: app = aplicación.
            CALL_CONTACT: target = persona/número para llamada normal.
            CALL_IN_APP: app = aplicación y target = persona.
            OPEN_CHAT: app = aplicación y target = persona/chat.
            PREPARE_MESSAGE: app = aplicación, target = destinatario, message = texto exacto a comunicar.
            PLAY_MEDIA: app = aplicación solicitada, value = canción/artista/podcast/video/búsqueda.
            TORCH_ON, TORCH_OFF, MEDIA_PAUSE, MEDIA_PLAY, MEDIA_NEXT, MEDIA_PREVIOUS, BATTERY.
            NONE: conversación o pregunta sin acción del teléfono; responde en reply.

            Reglas estrictas:
            - Si el usuario dice TushNH, conserva app="TushNH". Nunca lo sustituyas por YouTube.
            - Si dice Spotify, YouTube, Instagram, WhatsApp, Messenger, Facebook, Lifonk u otra app, conserva esa app.
            - “pon/reproduce X en Y” siempre es PLAY_MEDIA con value=X y app=Y.
            - “escríbele/dile/mándale/avísale a X por Y que Z” es PREPARE_MESSAGE con target=X, app=Y, message=Z.
            - No corrijas nombres propios; Android hará coincidencia fonética con contactos.
            - Usa el contexto reciente para pronombres y continuaciones.
            - No afirmes que una acción ya ocurrió.
        """.trimIndent()

        val contents = JSONArray()
        history.forEach { turn ->
            contents.put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", turn.user)))
            })
            contents.put(JSONObject().apply {
                put("role", "model")
                put("parts", JSONArray().put(JSONObject().put("text", decisionJson(turn.decision).toString())))
            })
        }
        contents.put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", message)))
        })

        val schema = JSONObject().apply {
            put("type", "object")
            put("additionalProperties", false)
            put("properties", JSONObject().apply {
                put("action", JSONObject().apply {
                    put("type", "string")
                    put("enum", JSONArray(allowedActions.toList()))
                })
                put("app", JSONObject().put("type", "string"))
                put("value", JSONObject().put("type", "string"))
                put("target", JSONObject().put("type", "string"))
                put("message", JSONObject().put("type", "string"))
                put("reply", JSONObject().put("type", "string"))
            })
            put("required", JSONArray(listOf("action", "app", "value", "target", "message", "reply")))
        }

        val payload = JSONObject().apply {
            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            put("contents", contents)
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
                put("responseJsonSchema", schema)
                put("thinkingConfig", JSONObject().put("thinkingLevel", "medium"))
                put("temperature", 0.1)
            })
        }

        var lastError: Throwable? = null
        repeat(2) { attempt ->
            try {
                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent")
                    .header("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Gemini HTTP ${response.code}: ${response.body?.string().orEmpty().take(300)}")
                    val root = JSONObject(response.body?.string().orEmpty())
                    val text = root.getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                        .getString("text").trim()
                    val decision = parseDecision(JSONObject(text))
                    remember(message, decision)
                    return@runCatching decision
                }
            } catch (t: Throwable) {
                lastError = t
                if (attempt == 0) Thread.sleep(180)
            }
        }
        throw lastError ?: IllegalStateException("Gemini no devolvió una decisión")
    }

    private fun deterministicDecision(message: String): Decision? {
        val text = message.trim()

        Regex("(?i)^(?:pon|reproduce|reproducir)\\s+(.+?)\\s+(?:en|desde)\\s+(.+?)\\s*$")
            .find(text)?.let { m ->
                return Decision(
                    action = "PLAY_MEDIA",
                    value = m.groupValues[1].trim(),
                    app = m.groupValues[2].trim()
                )
            }

        Regex("(?i)^(?:escr[ií]bele|dile|m[aá]ndale|av[ií]sale)\\s+a\\s+(.+?)\\s+(?:por|en)\\s+(.+?)\\s+(?:que|diciendo)\\s+(.+)$")
            .find(text)?.let { m ->
                return Decision(
                    action = "PREPARE_MESSAGE",
                    target = m.groupValues[1].trim(),
                    app = m.groupValues[2].trim(),
                    message = m.groupValues[3].trim()
                )
            }

        return null
    }

    private fun parseDecision(json: JSONObject): Decision {
        val action = json.optString("action", "NONE").uppercase().let {
            if (it in allowedActions) it else "NONE"
        }
        return Decision(
            action = action,
            app = json.optString("app", "").trim(),
            value = json.optString("value", "").trim(),
            target = json.optString("target", "").trim(),
            message = json.optString("message", "").trim(),
            reply = json.optString("reply", "").trim()
        )
    }

    private fun decisionJson(d: Decision) = JSONObject().apply {
        put("action", d.action)
        put("app", d.app)
        put("value", d.value)
        put("target", d.target)
        put("message", d.message)
        put("reply", d.reply)
    }

    private fun remember(user: String, decision: Decision) {
        history.addLast(Turn(user, decision))
        while (history.size > 6) history.removeFirst()
    }
}
