package com.newslearn.bd.ui.learn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newslearn.bd.data.remote.ExplainResponseDto
import com.newslearn.bd.ui.common.SectionLabel

@Composable
fun Labelled(label: String, text: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionLabel(label)
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
                SectionLabel("Difficult words")
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
