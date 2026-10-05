package com.newslearn.bd.data.repo

import com.newslearn.bd.data.remote.ApiJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.HttpException
import java.io.IOException

/** A failure with a message that is safe to show to the reader. */
class ApiException(message: String, val code: Int? = null, val offline: Boolean = false) :
    Exception(message)

/** Runs a network call and converts every failure into a [Result]; repositories never throw. */
suspend fun <T> apiCall(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        Result.failure(ApiException(e.serverMessage() ?: fallbackMessage(e.code()), e.code()))
    } catch (e: IOException) {
        Result.failure(ApiException("No connection. Check your internet and try again.", offline = true))
    } catch (e: SerializationException) {
        Result.failure(ApiException("The server sent something unexpected. Please update the app."))
    }

/** FastAPI reports errors as {"detail": "..."}; validation errors use a list instead. */
private fun HttpException.serverMessage(): String? = runCatching {
    val body = response()?.errorBody()?.string().orEmpty()
    val detail = (ApiJson.parseToJsonElement(body) as? JsonObject)?.get("detail")
    (detail as? JsonPrimitive)?.takeIf { it.isString }?.content
}.getOrNull()

private fun fallbackMessage(code: Int): String = when (code) {
    401 -> "Please sign in again."
    404 -> "Not found."
    422 -> "Please check what you entered."
    429 -> "Daily limit reached. Try again tomorrow."
    in 500..599 -> "The server is having trouble. Please try again."
    else -> "Something went wrong ($code)."
}

fun Throwable.userMessage(): String = (this as? ApiException)?.message ?: "Something went wrong."
