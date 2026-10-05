package com.newslearn.bd.data.remote

import com.newslearn.bd.BuildConfig
import com.newslearn.bd.data.local.SessionStore
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalSerializationApi::class)
val ApiJson: Json = Json {
    namingStrategy = JsonNamingStrategy.SnakeCase
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

private class AuthInterceptor(private val session: SessionStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = session.accessToken ?: return chain.proceed(chain.request())
        return chain.proceed(chain.request().withBearer(token))
    }
}

/**
 * On a 401, exchanges the refresh token for a new pair once and retries the request.
 * If the refresh fails the session is cleared, which returns the UI to the sign-in screen.
 */
private class TokenAuthenticator(
    private val session: SessionStore,
    private val authApi: AuthApi,
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.priorResponse != null) return null // already retried once
        val failedToken = response.request.header("Authorization")?.removePrefix("Bearer ")

        synchronized(this) {
            // Another request may have refreshed while this one waited for the lock.
            val current = session.accessToken
            if (current != null && current != failedToken) {
                return response.request.withBearer(current)
            }
            val refreshToken = session.refreshToken ?: return null
            val refreshed = runCatching {
                authApi.refreshBlocking(RefreshRequest(refreshToken)).execute()
            }.getOrNull()
            val tokens = refreshed?.takeIf { it.isSuccessful }?.body()
            if (tokens == null) {
                // Only a definite rejection signs the user out; a network failure does not.
                if (refreshed != null && refreshed.code() == 401) session.clearBlocking()
                return null
            }
            session.saveBlocking(tokens.accessToken, tokens.refreshToken, tokens.user.email)
            return response.request.withBearer(tokens.accessToken)
        }
    }
}

private fun Request.withBearer(token: String): Request =
    newBuilder().header("Authorization", "Bearer $token").build()

class Network(session: SessionStore) {
    private val converter = ApiJson.asConverterFactory("application/json".toMediaType())

    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // Explanations and tutor answers wait on a language model.
        .readTimeout(90, TimeUnit.SECONDS)
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                        redactHeader("Authorization")
                    },
                )
            }
        }
        .build()

    val authApi: AuthApi = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(baseClient)
        .addConverterFactory(converter)
        .build()
        .create(AuthApi::class.java)

    val api: ApiService = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(
            baseClient.newBuilder()
                .addInterceptor(AuthInterceptor(session))
                .authenticator(TokenAuthenticator(session, authApi))
                .build(),
        )
        .addConverterFactory(converter)
        .build()
        .create(ApiService::class.java)
}
