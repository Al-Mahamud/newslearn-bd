package com.newslearn.bd.ui.quiz

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.newslearn.bd.data.remote.AttemptSummaryDto
import com.newslearn.bd.data.remote.QuizDto
import com.newslearn.bd.data.repo.QuizRepository
import com.newslearn.bd.data.repo.userMessage
import com.newslearn.bd.ui.common.CategoryLabels
import com.newslearn.bd.ui.common.appViewModel
import com.newslearn.bd.ui.common.relativeTime
import com.newslearn.bd.ui.learn.HubCard
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class QuizHubState(val starting: Boolean = false, val history: List<AttemptSummaryDto> = emptyList())

sealed interface QuizHubEvent {
    data class Open(val quizId: Int) : QuizHubEvent
    data class Message(val text: String) : QuizHubEvent
}

class QuizHubViewModel(private val quiz: QuizRepository) : ViewModel() {
    private val _state = MutableStateFlow(QuizHubState())
    val state: StateFlow<QuizHubState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<QuizHubEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<QuizHubEvent> = _events.asSharedFlow()

    fun refreshHistory() {
        viewModelScope.launch {
            quiz.history().onSuccess { attempts -> _state.update { it.copy(history = attempts) } }
        }
    }

    fun startDaily() = start { quiz.daily() }
    fun startWeekly() = start { quiz.weekly() }
    fun startMock() = start { quiz.mock() }
    fun startPractice(category: String?) = start { quiz.practice(category) }

    private fun start(request: suspend () -> Result<QuizDto>) {
        if (_state.value.starting) return
        _state.update { it.copy(starting = true) }
        viewModelScope.launch {
            val result = request()
            _state.update { it.copy(starting = false) }
            result.fold(
                onSuccess = { _events.tryEmit(QuizHubEvent.Open(it.id)) },
                onFailure = { _events.tryEmit(QuizHubEvent.Message(it.userMessage())) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuizHubScreen(onOpenQuiz: (Int) -> Unit) {
    val viewModel = appViewModel { QuizHubViewModel(it.quiz) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is QuizHubEvent.Open -> onOpenQuiz(event.quizId)
                is QuizHubEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshHistory()
        onPauseOrDispose { }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Quiz", style = MaterialTheme.typography.headlineMedium)
            if (state.starting) LinearProgressIndicator(Modifier.fillMaxWidth())

            HubCard("Daily quiz", "10 questions from today's news", onClick = viewModel::startDaily)
            HubCard("Weekly quiz", "20 questions from this week's news", onClick = viewModel::startWeekly)
            HubCard("Mock exam", "30 questions across all topics, timed", onClick = viewModel::startMock)

            SectionTitle("Practise a topic")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { viewModel.startPractice(null) }, label = { Text("All topics") })
                CategoryLabels.filterKeys { it != "other" }.forEach { (slug, label) ->
                    AssistChip(onClick = { viewModel.startPractice(slug) }, label = { Text(label) })
                }
            }

            if (state.history.isNotEmpty()) {
                SectionTitle("Recent results")
                state.history.take(10).forEach { attempt ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(attempt.quizTitle, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            Text(
                                relativeTime(attempt.submittedAt),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "${attempt.score}/${attempt.total} · ${attempt.percent}%",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp),
    )
}
