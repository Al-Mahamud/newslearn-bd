package com.newslearn.bd.ui.learn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.newslearn.bd.data.remote.UserWordDto
import com.newslearn.bd.data.repo.VocabularyRepository
import com.newslearn.bd.data.repo.userMessage
import com.newslearn.bd.ui.common.BackTopBar
import com.newslearn.bd.ui.common.MessageView
import com.newslearn.bd.ui.common.StateView
import com.newslearn.bd.ui.common.UiState
import com.newslearn.bd.ui.common.appViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReviewSession(
    val queue: List<UserWordDto>,
    val total: Int,
    val revealed: Boolean = false,
    val remembered: Int = 0,
    val error: String? = null,
) {
    val current: UserWordDto? get() = queue.firstOrNull()
    val done: Int get() = total - queue.size
}

class ReviewViewModel(private val vocabulary: VocabularyRepository) : ViewModel() {
    private val _state = MutableStateFlow<UiState<ReviewSession>>(UiState.Loading)
    val state: StateFlow<UiState<ReviewSession>> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = vocabulary.due().fold(
                onSuccess = { UiState.Success(ReviewSession(queue = it, total = it.size)) },
                onFailure = { UiState.Error(it.userMessage()) },
            )
        }
    }

    private fun updateSession(transform: (ReviewSession) -> ReviewSession) = _state.update {
        if (it is UiState.Success) UiState.Success(transform(it.data)) else it
    }

    fun reveal() = updateSession { it.copy(revealed = true) }

    fun answer(remembered: Boolean) {
        val word = (_state.value as? UiState.Success)?.data?.current ?: return
        viewModelScope.launch {
            vocabulary.review(word.word.id, remembered).fold(
                onSuccess = {
                    updateSession { session ->
                        session.copy(
                            queue = session.queue.drop(1),
                            revealed = false,
                            remembered = session.remembered + if (remembered) 1 else 0,
                            error = null,
                        )
                    }
                },
                // The card stays so the answer can be sent again.
                onFailure = { error -> updateSession { it.copy(error = error.userMessage()) } },
            )
        }
    }
}

@Composable
fun ReviewScreen(onBack: () -> Unit) {
    val viewModel = appViewModel { ReviewViewModel(it.vocabulary) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val speak = rememberSpeaker()

    Scaffold(topBar = { BackTopBar("Review words", onBack) }) { padding ->
        StateView(state, onRetry = viewModel::load, modifier = Modifier.padding(padding)) { session ->
            val entry = session.current
            when {
                session.total == 0 -> MessageView(
                    "No words are due. Save words from articles and they will appear here when it is time to review them.",
                    Modifier.padding(padding),
                )
                entry == null -> MessageView(
                    "Done. You remembered ${session.remembered} of ${session.total}.",
                    Modifier.padding(padding),
                    actionLabel = "Back",
                    onAction = onBack,
                )
                else -> Column(
                    Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    LinearProgressIndicator(
                        progress = { session.done.toFloat() / session.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${session.done + 1} of ${session.total}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    entry.word.word,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { speak(entry.word.word) }) {
                                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Hear the word")
                                }
                            }
                            if (entry.word.contextSentence.isNotBlank()) {
                                Text(
                                    entry.word.contextSentence,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (session.revealed) {
                                Labelled("Meaning", entry.word.meaningEn)
                                Labelled("বাংলা", entry.word.meaningBn)
                                if (entry.word.exampleSentence.isNotBlank()) {
                                    Labelled("Example", entry.word.exampleSentence)
                                }
                            }
                        }
                    }
                    session.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (session.revealed) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { viewModel.answer(false) }, modifier = Modifier.weight(1f)) {
                                Text("I forgot")
                            }
                            Button(onClick = { viewModel.answer(true) }, modifier = Modifier.weight(1f)) {
                                Text("I knew it")
                            }
                        }
                    } else {
                        FilledTonalButton(onClick = viewModel::reveal, modifier = Modifier.fillMaxWidth()) {
                            Text("Show meaning")
                        }
                    }
                }
            }
        }
    }
}
