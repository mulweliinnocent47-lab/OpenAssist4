package com.openassist.data.openrouter

import com.openassist.core.UserFacingErrors
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.HttpException
import retrofit2.converter.kotlinx.serialization.asConverterFactory

data class OpenRouterUsageSnapshot(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
)

data class OpenRouterChatResult(
    val choice: Choice,
    val usage: OpenRouterUsageSnapshot?,
)

class OpenRouterRepository(
    private val api: OpenRouterApi = Retrofit.Builder()
        .baseUrl("https://openrouter.ai/api/v1/")
        .client(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(90, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build(),
        )
        .addConverterFactory(
            Json { ignoreUnknownKeys = true }
                .asConverterFactory("application/json".toMediaType()),
        )
        .build()
        .create(OpenRouterApi::class.java),
) {
    /**
     * Sends one turn to the chat completions endpoint.
     *
     * @param tools Pass the full list of [ToolDefinition]s on every call.
     *              The list is omitted from the request body when empty so
     *              models that don't support tool-use still work correctly.
     */
    suspend fun chat(
        apiKey: String,
        model: String,
        messages: List<OpenRouterMessage>,
        tools: List<ToolDefinition> = emptyList(),
    ): Choice = chatCompletion(apiKey, model, messages, tools).choice

    suspend fun chatCompletion(
        apiKey: String,
        model: String,
        messages: List<OpenRouterMessage>,
        tools: List<ToolDefinition> = emptyList(),
    ): OpenRouterChatResult {
        require(apiKey.isNotBlank()) { "Add your OpenRouter API key in Settings before using cloud models." }
        val response = withBoundedRetry {
            api.chat(
                authorization = "Bearer $apiKey",
                request = ChatRequest(
                    model = model,
                    messages = messages,
                    tools = tools.ifEmpty { null },
                ),
            )
        }
        val choice = response.choices.firstOrNull()
            ?: error(UserFacingErrors.openRouterEmptyResponse())
        return OpenRouterChatResult(
            choice = choice,
            usage = response.usage?.let {
                OpenRouterUsageSnapshot(
                    promptTokens = it.promptTokens ?: 0,
                    completionTokens = it.completionTokens ?: 0,
                    totalTokens = it.totalTokens ?: 0,
                )
            },
        )
    }

    suspend fun availableModels(): List<ModelInfo> = withBoundedRetry { api.models().data }

    private suspend fun <T> withBoundedRetry(block: suspend () -> T): T {
        var attempt = 0
        var lastFailure: Throwable? = null
        while (attempt < MAX_ATTEMPTS) {
            try {
                return block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (http: HttpException) {
                if (!http.isRetryable()) throw http.toUserFacingError()
                lastFailure = http
            } catch (io: IOException) {
                lastFailure = io
            }
            attempt += 1
            if (attempt < MAX_ATTEMPTS) delay(RETRY_DELAYS_MS[attempt - 1])
        }
        throw (lastFailure?.toUserFacingError() ?: IllegalStateException("OpenRouter request failed."))
    }

    private fun HttpException.isRetryable(): Boolean = code() == 408 || code() == 429 || code() in 500..599

    private fun Throwable.toUserFacingError(): Throwable = when (this) {
        is HttpException -> IllegalStateException(
            when (code()) {
                401, 403 -> "OpenRouter rejected the API key. Check Settings and try again."
                404 -> "OpenRouter model was not found. Choose another model in Settings."
                408 -> "OpenRouter timed out before responding. Try again."
                429 -> "OpenRouter rate limit reached. Wait a moment or choose another model."
                in 500..599 -> "OpenRouter is temporarily unavailable. Try again later."
                else -> "OpenRouter request failed with HTTP ${code()}."
            },
            this,
        )
        is IOException -> IllegalStateException("Network connection to OpenRouter failed. Check connectivity and try again.", this)
        else -> this
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        val RETRY_DELAYS_MS = longArrayOf(500, 1_500)
    }
}
