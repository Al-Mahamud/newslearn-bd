package com.newslearn.bd.ui.quiz

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.newslearn.bd.data.remote.AnswerReviewDto
import com.newslearn.bd.data.remote.AttemptResultDto
import com.newslearn.bd.data.remote.QuizDto
import com.newslearn.bd.data.repo.QuizRepository
import com.newslearn.bd.data.repo.userMessage
import com.newslearn.bd.ui.common.BackTopBar
import com.newslearn.bd.ui.common.StateView
import com.newslearn.bd.ui.common.UiState
import com.newslearn.bd.ui.common.appViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class QuizSession(
    val quiz: QuizDto,
    /** question id -> chosen option */
    val answers: Map<Int, Int> = emptyMap(),
    val secondsLeft: Int? = null,
    val submitting: Boolean = false,
    val submitError: String? = null,
    val result: AttemptResultDto? = null,
)

class QuizViewModel(private val quizId: Int, private val quizzes: QuizRepository) : ViewModel() {
    private val _state = MutableStateFlow<UiState<QuizSession>>(UiState.Loading)
    val state: StateFlow<UiState<QuizSession>> = _state.asStateFlow()

    private var startedAt = 0L
    private var timer: Job? = null

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            quizzes.quiz(quizId).fold(
                onSuccess = { quiz ->
                    startedAt = System.currentTimeMillis()
                    _state.value = UiState.Success(QuizSession(quiz, secondsLeft = quiz.timeLimitSeconds))
                    if (quiz.timeLimitSeconds != null) startTimer()
                },
                onFailure = { _state.value = UiState.Error(it.userMessage()) },
            )
        }
    }

    private fun session(): QuizSession? = (_state.value as? UiState.Success)?.data

    private fun updateSession(transform: (QuizSession) -> QuizSession) = _state.update {
        if (it is UiState.Success) UiState.Success(transform(it.data)) else it
    }

    private fun startTimer() {
        timer?.cancel()
        timer = viewModelScope.launch {
            while (true) {
                delay(1000)
                val left = session()?.secondsLeft ?: return@launch
                if (left <= 1) {
                    updateSession { it.copy(secondsLeft = 0) }
                    submit() // time is up: hand in whatever has been answered
                    return@launch
                }
                updateSession { it.copy(secondsLeft = left - 1) }
            }
        }
    }

    fun select(questionId: Int, option: Int) = updateSession {
        if (it.result != null || it.submitting) it else it.copy(answers = it.answers + (questionId to option))
    }

    fun submit() {
        val current = session() ?: return
        if (current.submitting || current.result != null) return
        timer?.cancel()
        updateSession { it.copy(submitting = true, submitError = null) }
        viewModelScope.launch {
            val seconds = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
            quizzes.submit(quizId, current.answers, current.quiz.questions.map { it.id }, seconds).fold(
                onSuccess = { result -> updateSession { it.copy(submitting = false, result = result) } },
                onFailure = { error ->
                    updateSession { it.copy(submitting = false, submitError = error.userMessage()) }
                },
            )
        }
    }
}

@Composable
fun QuizScreen(quizId: Int, onBack: () -> Unit, onOpenArticle: (Int) -> Unit) {
    val viewModel = appViewModel(key = "quiz-$quizId") { QuizViewModel(quizId, it.quiz) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val title = (state as? UiState.Success)?.data?.quiz?.title ?: "Quiz"

    Scaffold(topBar = { BackTopBar(title, onBack) }) { padding ->
        StateView(state, onRetry = viewModel::load, modifier = Modifier.padding(padding)) { session ->
            val result = session.result
            if (result != null) {
                ResultList(result, onOpenArticle, onBack, Modifier.padding(padding))
            } else {
                QuestionList(session, viewModel::select, viewModel::submit, Modifier.padding(padding))
            }
        }
    }
}

@Composable
private fun QuestionList(
    session: QuizSession,
    onSelect: (Int, Int) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val questions = session.quiz.questions
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${session.answers.size} of ${questions.size} answered",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                session.secondsLeft?.let { left ->
                    Text(
                        "%d:%02d left".format(left / 60, left % 60),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (left <= 30) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        itemsIndexed(questions, key = { _, q -> q.id }) { index, question ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "${index + 1}. ${question.text}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    question.options.forEachIndexed { optionIndex, option ->
                        val selected = session.answers[question.id] == optionIndex
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = { onSelect(question.id, optionIndex) },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
                            Text(option, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                session.submitError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = onSubmit, enabled = !session.submitting, modifier = Modifier.fillMaxWidth()) {
                    Text(if (session.submitting) "Submitting…" else "Submit answers")
                }
            }
        }
    }
}

@Composable
private fun ResultList(
    result: AttemptResultDto,
    onOpenArticle: (Int) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${result.score} / ${result.total}",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("${result.percent}% correct", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        itemsIndexed(result.review, key = { _, r -> r.questionId }) { index, review ->
            ReviewCard(index, review, onOpenArticle)
        }
        item {
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}

@Composable
private fun ReviewCard(index: Int, review: AnswerReviewDto, onOpenArticle: (Int) -> Unit) {
    val verdictColor = if (review.correct) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    OutlinedCard(border = BorderStroke(1.dp, verdictColor), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when {
                    review.correct -> "Correct"
                    review.selectedIndex == null -> "Not answered"
                    else -> "Incorrect"
                },
                style = MaterialTheme.typography.labelLarge,
                color = verdictColor,
                fontWeight = FontWeight.SemiBold,
            )
            Text("${index + 1}. ${review.text}", style = MaterialTheme.typography.titleMedium)
            review.options.forEachIndexed { optionIndex, option ->
                val isAnswer = optionIndex == review.correctIndex
                val isWrongPick = optionIndex == review.selectedIndex && !review.correct
                // Marks are spelled out so the result does not rely on colour alone.
                val mark = when {
                    isAnswer -> "✓ "
                    isWrongPick -> "✗ "
                    else -> "   "
                }
                Text(
                    mark + option,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isAnswer) FontWeight.SemiBold else FontWeight.Normal,
                    color = when {
                        isAnswer -> MaterialTheme.colorScheme.primary
                        isWrongPick -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            if (review.explanation.isNotBlank()) {
                Text(
                    review.explanation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { onOpenArticle(review.articleId) }) { Text("Read the article") }
        }
    }
}
