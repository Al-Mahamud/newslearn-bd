package com.newslearn.bd.ui.quiz

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.newslearn.bd.data.remote.AnswerReviewDto
import com.newslearn.bd.data.remote.AttemptResultDto
import com.newslearn.bd.data.remote.QuestionDto
import com.newslearn.bd.data.remote.QuizDto
import com.newslearn.bd.data.repo.QuizRepository
import com.newslearn.bd.data.repo.userMessage
import com.newslearn.bd.ui.common.ContentCard
import com.newslearn.bd.ui.common.Panel
import com.newslearn.bd.ui.common.StateView
import com.newslearn.bd.ui.common.UiState
import com.newslearn.bd.ui.common.appViewModel
import com.newslearn.bd.ui.common.categoryLabel
import com.newslearn.bd.ui.theme.AppTheme
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

private val Letters = listOf("A", "B", "C", "D")

@Composable
fun QuizScreen(quizId: Int, onBack: () -> Unit, onOpenArticle: (Int) -> Unit) {
    val viewModel = appViewModel(key = "quiz-$quizId") { QuizViewModel(quizId, it.quiz) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            StateView(state, onRetry = viewModel::load) { session ->
                val result = session.result
                if (result != null) {
                    ResultList(session.quiz.title, result, onOpenArticle, onBack)
                } else {
                    QuestionPager(session, viewModel::select, viewModel::submit, onBack)
                }
            }
        }
    }
}

@Composable
private fun QuestionPager(
    session: QuizSession,
    onSelect: (Int, Int) -> Unit,
    onSubmit: () -> Unit,
    onLeave: () -> Unit,
) {
    val questions = session.quiz.questions
    var index by rememberSaveable { mutableIntStateOf(0) }
    val question = questions.getOrNull(index) ?: return
    val isLast = index == questions.lastIndex
    val unanswered = questions.size - session.answers.size

    Column(
        Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onLeave) { Icon(Icons.Default.Close, contentDescription = "Leave quiz") }
            // One segment per question: answered, the one on screen, and still to come.
            Row(
                Modifier.weight(1f).clearAndSetSemantics { },
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                questions.forEachIndexed { i, q ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    i == index -> MaterialTheme.colorScheme.onSurface
                                    q.id in session.answers -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.outline
                                },
                            ),
                    )
                }
            }
            session.secondsLeft?.let { left ->
                val urgent = left <= 30
                Surface(
                    shape = CircleShape,
                    color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceContainerLowest,
                    contentColor = if (urgent) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(Icons.Default.Timer, contentDescription = "Time left", modifier = Modifier.size(16.dp))
                        Text("%d:%02d".format(left / 60, left % 60), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Question ${index + 1} of ${questions.size}",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (question.category.isNotBlank()) {
                        Text(
                            "· ${categoryLabel(question.category)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(question.text, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold))
            }
            Options(question, selected = session.answers[question.id], enabled = !session.submitting) {
                onSelect(question.id, it)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            session.submitError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (isLast && unanswered > 0) {
                Text(
                    if (unanswered == 1) "1 question is not answered." else "$unanswered questions are not answered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (index > 0) {
                    OutlinedButton(
                        onClick = { index -= 1 },
                        modifier = Modifier.heightIn(min = 54.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) { Text("Back") }
                }
                Button(
                    onClick = { if (isLast) onSubmit() else index += 1 },
                    enabled = !session.submitting,
                    modifier = Modifier.weight(1f).heightIn(min = 54.dp),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Text(
                        when {
                            session.submitting -> "Checking…"
                            isLast -> "Finish and see answers"
                            question.id in session.answers -> "Next question"
                            else -> "Skip"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Options(question: QuestionDto, selected: Int?, enabled: Boolean, onSelect: (Int) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        question.options.forEachIndexed { optionIndex, option ->
            val isSelected = selected == optionIndex
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
                border = BorderStroke(
                    if (isSelected) 2.dp else 1.dp,
                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .selectable(
                        selected = isSelected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(optionIndex) },
                    ),
            ) {
                Row(
                    Modifier.heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier
                            .size(32.dp)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(10.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                        } else {
                            Text(
                                Letters.getOrElse(optionIndex) { "" },
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            )
                        }
                    }
                    Text(
                        option,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultList(
    title: String,
    result: AttemptResultDto,
    onOpenArticle: (Int) -> Unit,
    onDone: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.onPanelMuted)
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "${result.score} / ${result.total}",
                            style = MaterialTheme.typography.displayMedium,
                            color = AppTheme.colors.highlight,
                        )
                        Text(
                            "${result.percent}% correct",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                }
            }
        }
        itemsIndexed(result.review, key = { _, r -> r.questionId }) { index, review ->
            ReviewCard(index, review, onOpenArticle)
        }
        item {
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                shape = MaterialTheme.shapes.medium,
            ) { Text("Done") }
        }
    }
}

@Composable
private fun ReviewCard(index: Int, review: AnswerReviewDto, onOpenArticle: (Int) -> Unit) {
    val verdictColor = if (review.correct) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    ContentCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when {
                    review.correct -> "Correct"
                    review.selectedIndex == null -> "Not answered"
                    else -> "Incorrect"
                },
                style = MaterialTheme.typography.titleSmall,
                color = verdictColor,
            )
            Text("${index + 1}. ${review.text}", style = MaterialTheme.typography.titleMedium)
            review.options.forEachIndexed { optionIndex, option ->
                val isAnswer = optionIndex == review.correctIndex
                val isWrongPick = optionIndex == review.selectedIndex && !review.correct
                // Marks are spelled out so the result does not rely on colour alone.
                val mark = when {
                    isAnswer -> "✓  "
                    isWrongPick -> "✗  "
                    else -> "     "
                }
                Text(
                    mark + option,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (isAnswer) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = when {
                        isAnswer -> MaterialTheme.colorScheme.primary
                        isWrongPick -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            if (review.explanation.isNotBlank()) {
                Surface(color = MaterialTheme.colorScheme.background, shape = MaterialTheme.shapes.small) {
                    Text(
                        review.explanation,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                    )
                }
            }
            TextButton(onClick = { onOpenArticle(review.articleId) }) { Text("Read the article") }
        }
    }
}
