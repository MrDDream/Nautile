package com.nautile.app.ai

import com.nautile.i18n.tr

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** The AI services the assistant can use. */
enum class AiProviderKind(
    val label: String,
    /** Default model, editable (providers rename models often: use « Charger la liste »). */
    val defaultModel: String,
    val defaultBaseUrl: String? = null,
    val needsKey: Boolean = true,
    val editableUrl: Boolean = false,
    val keyHint: String = "",
) {
    CLAUDE("Claude (Anthropic)", "claude-opus-5-5", keyHint = "sk-ant-…"),
    OPENAI("OpenAI", "gpt-5", "https://api.openai.com/v1", keyHint = "sk-…"),
    GEMINI("Google Gemini", "gemini-2.5-flash", "https://generativelanguage.googleapis.com/v1beta", keyHint = "AIza…"),
    GROK("xAI Grok", "grok-4", "https://api.x.ai/v1", keyHint = "xai-…"),
    MISTRAL("Mistral", "mistral-large-latest", "https://api.mistral.ai/v1"),
    LOCAL(tr("Local (Ollama, LM Studio…)"), "llama3.1", "http://192.168.1.10:11434/v1", needsKey = false, editableUrl = true),
    CUSTOM(tr("Autre (compatible OpenAI)"), "", "https://openrouter.ai/api/v1", editableUrl = true),
}

data class AiConfig(val kind: AiProviderKind, val apiKey: String?, val model: String, val baseUrl: String?)

interface AiProvider {
    fun complete(system: String, user: String): String
    fun models(): List<String>
}

class AiException(message: String) : Exception(message)

object AiProviders {
    fun create(c: AiConfig): AiProvider = when (c.kind) {
        AiProviderKind.CLAUDE -> ClaudeProvider(c.apiKey ?: throw AiException(tr("Clé API manquante")), c.model)
        AiProviderKind.GEMINI -> GeminiProvider(c.baseUrl ?: c.kind.defaultBaseUrl!!, c.apiKey ?: throw AiException(tr("Clé API manquante")), c.model)
        else -> OpenAiCompatibleProvider(c.baseUrl ?: c.kind.defaultBaseUrl ?: throw AiException("URL manquante"), c.apiKey, c.model, c.kind.label)
    }
}

/** Claude through the official Anthropic SDK. */
private class ClaudeProvider(private val key: String, private val model: String) : AiProvider {
    private fun <T> call(block: (com.anthropic.client.AnthropicClient) -> T): T {
        val client = AnthropicOkHttpClient.builder().apiKey(key).build()
        try {
            return block(client)
        } catch (e: UnauthorizedException) {
            throw AiException(tr("Clé API refusée par Anthropic"))
        } catch (e: RateLimitException) {
            throw AiException(tr("Trop de requêtes : réessayez dans un instant"))
        } catch (e: AnthropicServiceException) {
            throw AiException(tr("Erreur Anthropic (%1\$s) : %2\$s", e.statusCode(), e.message))
        } finally {
            client.close()
        }
    }

    override fun complete(system: String, user: String): String = call { client ->
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(system)
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.MEDIUM).build())
            // Server-side fallback when the model declines (routes by refusal category).
            .addBeta("server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .addUserMessage(user)
            .build()
        val response = client.beta().messages().create(params)
        if (response.stopReason().map { it.toString() }.orElse("") == "refusal") throw AiException(tr("La demande a été refusée par le modèle."))
        response.content().mapNotNull { b -> b.text().map { it.text() }.orElse(null) }.joinToString("\n")
    }

    override fun models(): List<String> = call { client -> client.models().list().autoPager().map { it.id() }.toList() }
}

private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()
private val json = Json { ignoreUnknownKeys = true }
private val JSON_TYPE = "application/json".toMediaType()

private fun send(request: Request, provider: String): JsonObject {
    val response = try {
        http.newCall(request).execute()
    } catch (e: java.io.IOException) {
        throw AiException("$provider injoignable : ${e.message}")
    }
    response.use { r ->
        val body = r.body?.string().orEmpty()
        if (!r.isSuccessful) {
            val detail = runCatching {
                val o = json.parseToJsonElement(body).jsonObject
                (o["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.content ?: o["error"]?.jsonPrimitive?.content
            }.getOrNull() ?: body.take(200)
            throw AiException(
                when (r.code) {
                    401, 403 -> tr("%1\$s : clé API refusée", provider)
                    404 -> tr("%1\$s : modèle ou adresse introuvable (%2\$s)", provider, detail)
                    429 -> tr("%1\$s : quota ou limite atteint (%2\$s)", provider, detail)
                    else -> "$provider (${r.code}) : $detail"
                },
            )
        }
        return json.parseToJsonElement(body).jsonObject
    }
}

/** OpenAI's chat completions API, also spoken by Grok, Mistral, OpenRouter, Ollama, LM Studio… */
private class OpenAiCompatibleProvider(baseUrl: String, private val key: String?, private val model: String, private val name: String) : AiProvider {
    private val base = baseUrl.trimEnd('/')

    private fun request(path: String) = Request.Builder().url(base + path).apply {
        if (!key.isNullOrBlank()) header("Authorization", "Bearer $key")
    }

    override fun complete(system: String, user: String): String {
        if (model.isBlank()) throw AiException(tr("Choisissez un modèle"))
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
        }.toString()
        val o = send(request("/chat/completions").post(body.toRequestBody(JSON_TYPE)).build(), name)
        return o["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
            ?: throw AiException(tr("%1\$s : réponse vide", name))
    }

    override fun models(): List<String> =
        send(request("/models").get().build(), name)["data"]?.jsonArray
            ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }?.sorted().orEmpty()
}

/** Google's Gemini API (generateContent). */
private class GeminiProvider(baseUrl: String, private val key: String, private val model: String) : AiProvider {
    private val base = baseUrl.trimEnd('/')

    override fun complete(system: String, user: String): String {
        val body = buildJsonObject {
            put("system_instruction", buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", system) }) }) })
            put("contents", buildJsonArray {
                add(buildJsonObject { put("role", "user"); put("parts", buildJsonArray { add(buildJsonObject { put("text", user) }) }) })
            })
        }.toString()
        val name = model.removePrefix("models/")
        val o = send(
            Request.Builder().url("$base/models/$name:generateContent").header("x-goog-api-key", key)
                .post(body.toRequestBody(JSON_TYPE)).build(),
            "Gemini",
        )
        val parts = o["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
        return parts?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }?.joinToString("")?.takeIf { it.isNotBlank() }
            ?: throw AiException(tr("Gemini : réponse vide (filtrée ?)"))
    }

    override fun models(): List<String> =
        send(Request.Builder().url("$base/models?pageSize=200").header("x-goog-api-key", key).get().build(), "Gemini")["models"]?.jsonArray
            ?.filter { m -> m.jsonObject["supportedGenerationMethods"]?.jsonArray?.any { it.jsonPrimitive.content == "generateContent" } == true }
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content?.removePrefix("models/") }?.sorted().orEmpty()
}
