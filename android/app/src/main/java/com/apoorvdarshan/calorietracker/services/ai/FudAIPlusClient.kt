package com.apoorvdarshan.calorietracker.services.ai

import com.apoorvdarshan.calorietracker.data.PreferencesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

data class PlusUsageItem(
    val used: Int,
    val limit: Int,
    val remaining: Int
)

data class PlusUsageSnapshot(
    val food: PlusUsageItem,
    val speech: PlusUsageItem,
    val coach: PlusUsageItem,
    val global: PlusUsageItem
)

object FudAIPlusClient {
    private const val ENDPOINT = "https://fud-ai.app/api/gemini"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    suspend fun generateContent(
        client: OkHttpClient,
        prefs: PreferencesStore,
        task: String,
        geminiBody: JSONObject
    ): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("task", task)
            .put("body", geminiBody)
            .toString()
            .toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("Content-Type", "application/json")
            .addHeader("X-FudAI-Platform", "android")
            .addHeader("X-FudAI-Install-ID", prefs.installId())
            .post(body)
            .build()
        execute(request, client)
    }

    suspend fun usage(client: OkHttpClient, prefs: PreferencesStore): PlusUsageSnapshot = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("X-FudAI-Platform", "android")
            .addHeader("X-FudAI-Install-ID", prefs.installId())
            .get()
            .build()
        val raw = execute(request, client)
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: throw AiError.InvalidResponse
        PlusUsageSnapshot(
            food = json.item("food"),
            speech = json.item("speech"),
            coach = json.item("coach"),
            global = json.item("global")
        )
    }

    private fun JSONObject.item(name: String): PlusUsageItem {
        val item = optJSONObject(name) ?: return PlusUsageItem(0, 0, 0)
        return PlusUsageItem(
            used = item.optInt("used"),
            limit = item.optInt("limit"),
            remaining = item.optInt("remaining")
        )
    }

    private fun execute(request: Request, client: OkHttpClient): String {
        try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (response.isSuccessful) return raw
                val message = runCatching {
                    JSONObject(raw).optString("error").takeIf { it.isNotBlank() }
                }.getOrNull()
                throw AiError.Api(message ?: "Fud AI Plus failed with HTTP ${response.code}.")
            }
        } catch (io: IOException) {
            throw AiError.Network(io)
        }
    }
}
