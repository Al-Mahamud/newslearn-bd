package com.newslearn.bd.ui.learn

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.newslearn.bd.data.remote.ExplainResponseDto
import com.newslearn.bd.data.repo.LearnRepository
import com.newslearn.bd.data.repo.VocabularyRepository
import com.newslearn.bd.data.repo.userMessage
import com.newslearn.bd.ui.common.ContentCard
import com.newslearn.bd.ui.common.UiState
import com.newslearn.bd.ui.common.appViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LearnViewModel(
    private val vocabulary: VocabularyRepository,
    private val learn: LearnRepository,
) : ViewModel() {
    /** Null until the count is known (or when it cannot be fetched). */
    private val _dueCount = MutableStateFlow<Int?>(null)
    val dueCount: StateFlow<Int?> = _dueCount.asStateFlow()

    private val _explanation = MutableStateFlow<UiState<ExplainResponseDto>?>(null)
    val explanation: StateFlow<UiState<ExplainResponseDto>?> = _explanation.asStateFlow()

    fun refreshDue() {
        viewModelScope.launch {
            _dueCount.value = vocabulary.due().getOrNull()?.size
        }
    }

    fun explain(sentence: String) {
        _explanation.value = UiState.Loading
        viewModelScope.launch {
            _explanation.value = learn.explain(sentence).fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { UiState.Error(it.userMessage()) },
            )
        }
    }
}

@Composable
fun LearnScreen(onReview: () -> Unit, onDigest: (String) -> Unit) {
    val viewModel = appViewModel { LearnViewModel(it.vocabulary, it.learn) }
    val dueCount by viewModel.dueCount.collectAsStateWithLifecycle()
    val explanation by viewModel.explanation.collectAsStateWithLifecycle()
    var sentence by rememberSaveable { mutableStateOf("") }

    // Refreshed on every return to this tab, e.g. after a review session.
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshDue()
        onPauseOrDispose { }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Words and revision", style = MaterialTheme.typography.headlineMedium)

        HubCard(
            title = "Review your words",
            subtitle = when (val due = dueCount) {
                null -> "Practise the words you saved"
                0 -> "Nothing due right now"
                1 -> "1 word is due"
                else -> "$due words are due"
            },
            onClick = onReview,
        )
        HubCard("Today's current affairs", "Today's news by topic, with facts to remember") { onDigest("daily") }
        HubCard("This week's current affairs", "The week's most exam-relevant news") { onDigest("weekly") }

        Text(
            "Explain a sentence",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 12.dp),
        )
        OutlinedTextField(
            value = sentence,
            onValueChange = { sentence = it.take(1000) },
            placeholder = { Text("Type or paste an English sentence") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { viewModel.explain(sentence) },
            enabled = sentence.trim().length >= 3 && explanation != UiState.Loading,
        ) { Text("Explain") }

        when (val result = explanation) {
            null -> Unit
            UiState.Loading -> CircularProgressIndicator()
            is UiState.Error -> Text(result.message, color = MaterialTheme.colorScheme.error)
            is UiState.Success -> ExplanationContent(result.data)
        }
    }
}

@Composable
fun HubCard(title: String, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ContentCard(modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
