package com.newslearn.bd.ui.profile

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
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.newslearn.bd.data.remote.ProgressDto
import com.newslearn.bd.data.remote.UserDto
import com.newslearn.bd.data.repo.AuthRepository
import com.newslearn.bd.data.repo.ProfileRepository
import com.newslearn.bd.data.repo.userMessage
import com.newslearn.bd.ui.common.CategoryLabels
import com.newslearn.bd.ui.common.StateView
import com.newslearn.bd.ui.common.UiState
import com.newslearn.bd.ui.common.appViewModel
import com.newslearn.bd.ui.common.categoryLabel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileData(val user: UserDto, val progress: ProgressDto)

class ProfileViewModel(
    private val profile: ProfileRepository,
    private val auth: AuthRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<UiState<ProfileData>>(UiState.Loading)
    val state: StateFlow<UiState<ProfileData>> = _state.asStateFlow()

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

    private fun applyUser(result: Result<UserDto>) = result.onSuccess { user ->
        _state.update { if (it is UiState.Success) UiState.Success(it.data.copy(user = user)) else it }
    }

    fun setLevel(level: String) {
        viewModelScope.launch { applyUser(profile.setLevel(level)) }
    }

    fun toggleCategory(slug: String) {
        val current = (_state.value as? UiState.Success)?.data?.user?.preferredCategories ?: return
        val updated = if (slug in current) current - slug else current + slug
        viewModelScope.launch { applyUser(profile.setPreferredCategories(updated)) }
    }

    fun signOut() {
        viewModelScope.launch { auth.signOut() }
    }
}

private val Levels = listOf("beginner" to "Beginner", "intermediate" to "Intermediate", "advanced" to "Advanced")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen() {
    val viewModel = appViewModel { ProfileViewModel(it.profile, it.auth) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LifecycleResumeEffect(viewModel) {
        viewModel.load()
        onPauseOrDispose { }
    }

    StateView(state, onRetry = viewModel::load) { data ->
        val progress = data.progress
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                data.user.displayName.ifBlank { data.user.email },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                StatCard("${progress.streakDays}", "Day streak", Modifier.weight(1f))
                StatCard("${progress.articlesRead}", "Articles read", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                StatCard("${progress.wordsLearned}/${progress.wordsSaved}", "Words learned", Modifier.weight(1f))
                StatCard("${progress.wordsDue}", "Words to review", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                StatCard("${progress.quizzesTaken}", "Quizzes taken", Modifier.weight(1f))
                StatCard("${progress.averageScorePercent}%", "Average score", Modifier.weight(1f))
            }

            if (progress.topics.isNotEmpty()) {
                SectionTitle("Accuracy by topic")
                progress.topics.forEach { topic ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(topic.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${topic.percent}% (${topic.correct}/${topic.answered})",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        LinearProgressIndicator(
                            progress = { topic.percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                progress.weakestTopic?.let {
                    Text(
                        "Practise more: ${categoryLabel(it)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
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

            OutlinedButton(onClick = viewModel::signOut, modifier = Modifier.padding(top = 16.dp)) {
                Text("Sign out")
            }
        }
    }
}

@Composable
private fun StatCard(value: String, label: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.Start) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
