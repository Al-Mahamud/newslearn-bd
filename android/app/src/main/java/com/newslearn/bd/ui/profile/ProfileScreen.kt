package com.newslearn.bd.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.newslearn.bd.data.remote.DayActivityDto
import com.newslearn.bd.data.remote.ProgressDto
import com.newslearn.bd.data.remote.TopicAccuracyDto
import com.newslearn.bd.data.remote.UserDto
import com.newslearn.bd.data.repo.AuthRepository
import com.newslearn.bd.data.repo.ProfileRepository
import com.newslearn.bd.data.repo.QuizRepository
import com.newslearn.bd.data.repo.userMessage
import com.newslearn.bd.ui.common.CategoryLabels
import com.newslearn.bd.ui.common.ContentCard
import com.newslearn.bd.ui.common.Panel
import com.newslearn.bd.ui.common.ProgressBar
import com.newslearn.bd.ui.common.StateView
import com.newslearn.bd.ui.common.UiState
import com.newslearn.bd.ui.common.appViewModel
import com.newslearn.bd.ui.common.categoryLabel
import com.newslearn.bd.ui.theme.AppTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

data class ProfileData(val user: UserDto, val progress: ProgressDto, val startingQuiz: Boolean = false)

class ProfileViewModel(
    private val profile: ProfileRepository,
    private val auth: AuthRepository,
    private val quiz: QuizRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<UiState<ProfileData>>(UiState.Loading)
    val state: StateFlow<UiState<ProfileData>> = _state.asStateFlow()

    /** Emits the id of a practice quiz that is ready to open. */
    private val _openQuiz = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val openQuiz: SharedFlow<Int> = _openQuiz.asSharedFlow()

    fun load() {
        viewModelScope.launch {
            val user = async { profile.me() }
            val progress = async { profile.progress() }
            val loaded = user.await().mapCatching { ProfileData(it, progress.await().getOrThrow()) }
            loaded.fold(
                onSuccess = { _state.value = UiState.Success(it) },
                onFailure = { error ->
                    _state.update { if (it is UiState.Success) it else UiState.Error(error.userMessage()) }
                },
            )
        }
    }

    private fun updateData(transform: (ProfileData) -> ProfileData) = _state.update {
        if (it is UiState.Success) UiState.Success(transform(it.data)) else it
    }

    private fun applyUser(result: Result<UserDto>) = result.onSuccess { user -> updateData { it.copy(user = user) } }

    fun setLevel(level: String) {
        viewModelScope.launch { applyUser(profile.setLevel(level)) }
    }

    fun toggleCategory(slug: String) {
        val current = (_state.value as? UiState.Success)?.data?.user?.preferredCategories ?: return
        val updated = if (slug in current) current - slug else current + slug
        viewModelScope.launch { applyUser(profile.setPreferredCategories(updated)) }
    }

    fun practise(category: String) {
        val data = (_state.value as? UiState.Success)?.data ?: return
        if (data.startingQuiz) return
        updateData { it.copy(startingQuiz = true) }
        viewModelScope.launch {
            val result = quiz.practice(category)
            updateData { it.copy(startingQuiz = false) }
            result.onSuccess { _openQuiz.tryEmit(it.id) }
        }
    }

    fun signOut() {
        viewModelScope.launch { auth.signOut() }
    }
}

private val Levels = listOf("beginner" to "Beginner", "intermediate" to "Intermediate", "advanced" to "Advanced")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(onOpenQuiz: (Int) -> Unit) {
    val viewModel = appViewModel { ProfileViewModel(it.profile, it.auth, it.quiz) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LifecycleResumeEffect(viewModel) {
        viewModel.load()
        onPauseOrDispose { }
    }
    LaunchedEffect(viewModel) {
        viewModel.openQuiz.collect { onOpenQuiz(it) }
    }

    StateView(state, onRetry = viewModel::load) { data ->
        val progress = data.progress
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column {
                Text("Your progress", style = MaterialTheme.typography.headlineMedium)
                Text(
                    data.user.displayName.ifBlank { data.user.email },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            StreakPanel(progress.streakDays, progress.last7Days)

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                StatCard("${progress.articlesRead}", "articles read", Modifier.weight(1f))
                StatCard("${progress.wordsLearned}", "words learned", Modifier.weight(1f))
                StatCard(
                    if (progress.quizzesTaken == 0) "–" else "${progress.averageScorePercent}%",
                    "quiz average",
                    Modifier.weight(1f),
                )
            }
            Text(
                "${progress.wordsSaved} words saved · ${progress.wordsDue} due for review · ${progress.quizzesTaken} quizzes taken",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (progress.topics.isNotEmpty()) TopicAccuracy(progress.topics)

            progress.weakestTopic?.let { weakest ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Weakest: ${categoryLabel(weakest)}", style = MaterialTheme.typography.titleSmall)
                            Text("A practice set on this topic", style = MaterialTheme.typography.bodyMedium)
                        }
                        Button(
                            onClick = { viewModel.practise(weakest) },
                            enabled = !data.startingQuiz,
                            shape = MaterialTheme.shapes.small,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary,
                                contentColor = MaterialTheme.colorScheme.surface,
                            ),
                            modifier = Modifier.heightIn(min = 44.dp),
                        ) { Text(if (data.startingQuiz) "Starting…" else "Practise") }
                    }
                }
            }

            SectionTitle("My English level")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Levels.forEach { (slug, label) ->
                    FilterChip(
                        selected = data.user.englishLevel == slug,
                        onClick = { viewModel.setLevel(slug) },
                        label = { Text(label) },
                    )
                }
            }

            SectionTitle("Topics for my “For you” feed")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CategoryLabels.filterKeys { it != "other" }.forEach { (slug, label) ->
                    FilterChip(
                        selected = slug in data.user.preferredCategories,
                        onClick = { viewModel.toggleCategory(slug) },
                        label = { Text(label) },
                    )
                }
            }

            OutlinedButton(onClick = viewModel::signOut, modifier = Modifier.padding(top = 12.dp)) {
                Text("Sign out")
            }
        }
    }
}

@Composable
private fun StreakPanel(streak: Int, days: List<DayActivityDto>) {
    val colors = AppTheme.colors
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$streak", style = MaterialTheme.typography.displayMedium)
                Text(
                    "day streak",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onPanelMuted,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            if (days.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    days.forEachIndexed { index, day ->
                        val active = day.articlesRead + day.quizzesTaken > 0
                        val isToday = index == days.lastIndex
                        val name = runCatching {
                            LocalDate.parse(day.day).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                        }.getOrDefault("")
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.clearAndSetSemantics {
                                contentDescription = "$name: ${if (active) "studied" else "no study"}"
                            },
                        ) {
                            Text(
                                name,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal,
                                ),
                                color = if (isToday) colors.onPanel else colors.onPanelMuted,
                            )
                            // Filled: studied. Ring: today, not yet. Dim: missed.
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .then(
                                        when {
                                            active -> Modifier.background(colors.highlight, CircleShape)
                                            isToday -> Modifier.border(3.dp, colors.highlight, CircleShape)
                                            else -> Modifier.background(colors.panelTrack, CircleShape)
                                        },
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopicAccuracy(topics: List<TopicAccuracyDto>) {
    ContentCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Accuracy by topic", style = MaterialTheme.typography.titleMedium)
            topics.sortedByDescending { it.percent }.forEach { topic ->
                val weak = topic.percent < 60
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(categoryLabel(topic.category), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${topic.percent}%  (${topic.correct}/${topic.answered})",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (weak) AppTheme.colors.warning else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    ProgressBar(
                        fraction = topic.percent / 100f,
                        color = if (weak) AppTheme.colors.warning else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCard(value: String, label: String, modifier: Modifier = Modifier) {
    ContentCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
}
