package com.newslearn.bd.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.sessionDataStore by preferencesDataStore(name = "session")

data class Session(val accessToken: String, val refreshToken: String, val email: String)

/**
 * The signed-in session. Held in memory for the network layer (which runs on OkHttp
 * threads and cannot suspend) and persisted so the user stays signed in.
 */
class SessionStore(context: Context) {
    private val store = context.applicationContext.sessionDataStore

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    val accessToken: String? get() = _session.value?.accessToken
    val refreshToken: String? get() = _session.value?.refreshToken

    init {
        // A single small read at startup, so the first screen knows whether to show sign-in.
        _session.value = runBlocking {
            val prefs = store.data.first()
            val access = prefs[ACCESS]
            val refresh = prefs[REFRESH]
            if (access != null && refresh != null) Session(access, refresh, prefs[EMAIL].orEmpty()) else null
        }
    }

    suspend fun save(accessToken: String, refreshToken: String, email: String) {
        store.edit {
            it[ACCESS] = accessToken
            it[REFRESH] = refreshToken
            it[EMAIL] = email
        }
        _session.value = Session(accessToken, refreshToken, email)
    }

    suspend fun clear() {
        store.edit { it.clear() }
        _session.value = null
    }

    fun saveBlocking(accessToken: String, refreshToken: String, email: String) =
        runBlocking { save(accessToken, refreshToken, email) }

    fun clearBlocking() = runBlocking { clear() }

    private companion object {
        val ACCESS = stringPreferencesKey("access_token")
        val REFRESH = stringPreferencesKey("refresh_token")
        val EMAIL = stringPreferencesKey("email")
    }
}
