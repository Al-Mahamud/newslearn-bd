package com.newslearn.bd.ui.learn

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newslearn.bd.data.remote.ExplainResponseDto
import java.util.Locale

@Composable
fun Labelled(label: String, text: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The result of "Explain this sentence". */
@Composable
fun ExplanationContent(explanation: ExplainResponseDto, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "“${explanation.sentence}”",
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Labelled("Simple English", explanation.simpleEnglish)
        Labelled("বাংলা", explanation.bangla)
        if (explanation.words.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Difficult words",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                explanation.words.forEach { word ->
                    Column {
                        Text(word.text, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${word.meaningEn} · ${word.meaningBn}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (explanation.grammarNote.isNotBlank()) Labelled("Grammar", explanation.grammarNote)
        if (explanation.example.isNotBlank()) Labelled("Example", explanation.example)
    }
}

/** Speaks English text with the phone's text-to-speech engine; silent if none is installed. */
@Composable
fun rememberSpeaker(): (String) -> Unit {
    val context = LocalContext.current.applicationContext
    val ready = remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(context) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale.US
                ready.value = engine
            }
        }
        onDispose {
            ready.value = null
            engine?.shutdown()
        }
    }
    return { text -> ready.value?.speak(text, TextToSpeech.QUEUE_FLUSH, null, text) }
}
