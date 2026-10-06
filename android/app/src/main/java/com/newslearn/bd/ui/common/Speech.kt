package com.newslearn.bd.ui.common

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Reads English text aloud with the phone's text-to-speech engine and reports how far it
 * has got. Silent, with [available] false, on a phone that has no engine installed.
 */
@Stable
class Speaker(context: Context) {
    var available by mutableStateOf(false)
        private set

    /** The text currently being read, or null when silent. */
    var speaking by mutableStateOf<String?>(null)
        private set

    /** 0..1 through the current text. */
    var progress by mutableFloatStateOf(0f)
        private set

    private var engine: TextToSpeech? = null
    private var length = 1

    // Callbacks arrive on a binder thread; Compose state may be written from any thread.
    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            progress = (end.toFloat() / length).coerceIn(0f, 1f)
        }

        override fun onDone(utteranceId: String?) = finished()

        @Deprecated("Deprecated in the platform; still the only callback some engines use")
        override fun onError(utteranceId: String?) = finished()

        override fun onStop(utteranceId: String?, interrupted: Boolean) = finished()

        private fun finished() {
            speaking = null
            progress = 0f
        }
    }

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            val tts = engine
            if (status == TextToSpeech.SUCCESS && tts != null) {
                tts.language = Locale.US
                tts.setOnUtteranceProgressListener(listener)
                available = true
            }
        }
    }

    /** Starts reading [text], replacing whatever was being read. [rate] 1.0 is normal speed. */
    fun speak(text: String, rate: Float = 1f) {
        val tts = engine?.takeIf { available } ?: return
        if (text.isBlank()) return
        length = text.length
        progress = 0f
        speaking = text
        tts.setSpeechRate(rate)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), text.hashCode().toString())
    }

    fun stop() {
        engine?.stop()
        speaking = null
        progress = 0f
    }

    /** Reads [text] if it is not already being read, otherwise stops. */
    fun toggle(text: String, rate: Float = 1f) {
        if (speaking == text) stop() else speak(text, rate)
    }

    fun release() {
        engine?.stop()
        engine?.shutdown()
        engine = null
    }
}

@Composable
fun rememberSpeaker(): Speaker {
    val context = LocalContext.current
    val speaker = remember { Speaker(context) }
    DisposableEffect(speaker) {
        onDispose { speaker.release() }
    }
    return speaker
}
