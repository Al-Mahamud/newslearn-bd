package com.newslearn.bd.ui.learn

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.newslearn.bd.data.remote.UserWordDto
import com.newslearn.bd.data.repo.VocabularyRepository
import com.newslearn.bd.data.repo.userMessage
import com.newslearn.bd.ui.common.ProgressBar
import com.newslearn.bd.ui.common.SectionLabel
import com.newslearn.bd.ui.common.Speaker
import com.newslearn.bd.ui.common.UiState
import com.newslearn.bd.ui.common.appViewModel
import com.newslearn.bd.ui.common.rememberSpeaker
import com.newslearn.bd.ui.theme.AppTheme
import com.newslearn.bd.ui.theme.BodyFont
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

/** Days until a word comes back once it reaches each box; mirrors the server's schedule. */
private val NextReviewDays = mapOf(1 to 1, 2 to 3, 3 to 7, 4 to 30, 5 to 90)
private const val BOXES = 5

private fun nextReviewLabel(box: Int): String {
    val days = NextReviewDays[(box + 1).coerceAtMost(BOXES)] ?: 1
    return if (days == 1) "tomorrow" else "in $days days"
}

@Composable
fun ReviewScreen(onBack: () -> Unit) {
    val viewModel = appViewModel { ReviewViewModel(it.vocabulary) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val speaker = rememberSpeaker()
    val colors = AppTheme.colors

    // The whole screen is the deep green panel: review is a focused mode, apart from browsing.
    Surface(color = colors.panel, contentColor = colors.onPanel, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val session = (state as? UiState.Success)?.data
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconButton(onClick = onBack, modifier = Modifier.padding(start = 0.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close review")
                }
                if (session != null && session.total > 0) {
                    ProgressBar(
                        fraction = session.done.toFloat() / session.total,
                        color = colors.highlight,
                        track = colors.panelTrack,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${(session.done + 1).coerceAtMost(session.total)} / ${session.total}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }

            when (val current = state) {
                UiState.Loading -> CenterMessage { CircularProgressIndicator(color = colors.highlight) }
                is UiState.Error -> CenterMessage {
                    Text(current.message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
                    PanelButton("Try again", viewModel::load)
                }
                is UiState.Success -> {
                    val entry = current.data.current
                    when {
                        current.data.total == 0 -> CenterMessage {
                            Text("Nothing to review", style = MaterialTheme.typography.headlineSmall)
                            Text(
                                "Save words from articles. Each one comes back here when it is time to review it.",
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.onPanelMuted,
                            )
                            PanelButton("Back", onBack)
                        }
                        entry == null -> CenterMessage {
                            Text(
                                "${current.data.remembered} of ${current.data.total}",
                                style = MaterialTheme.typography.displayMedium,
                                color = colors.highlight,
                            )
                            Text("remembered. Well done.", style = MaterialTheme.typography.bodyLarge)
                            PanelButton("Finish", onBack)
                        }
                        else -> Flashcard(
                            entry = entry,
                            session = current.data,
                            speaker = speaker,
                            onReveal = viewModel::reveal,
                            onAnswer = viewModel::answer,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Flashcard(
    entry: UserWordDto,
    session: ReviewSession,
    speaker: Speaker,
    onReveal: () -> Unit,
    onAnswer: (Boolean) -> Unit,
) {
    val colors = AppTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (entry.reviewCount == 0) "New word" else "Reviewed ${entry.reviewCount} time${if (entry.reviewCount == 1) "" else "s"}",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onPanelMuted,
            modifier = Modifier.weight(1f),
        )
        // One dot per stage of the review schedule.
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = "Stage ${entry.box} of $BOXES"
            },
        ) {
            repeat(BOXES) { index ->
                Box(
                    Modifier
                        .size(10.dp)
                        .background(if (index < entry.box) colors.highlight else colors.panelTrack, CircleShape),
                )
            }
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth().weight(1f),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(entry.word.word, style = MaterialTheme.typography.displayMedium)
                    if (entry.word.partOfSpeech.isNotBlank()) {
                        Text(
                            entry.word.partOfSpeech,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (speaker.available) {
                    FilledTonalIconButton(onClick = { speaker.speak(entry.word.word, 0.9f) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Hear the word")
                    }
                }
            }
            if (entry.word.contextSentence.isNotBlank()) {
                Surface(color = MaterialTheme.colorScheme.background, shape = MaterialTheme.shapes.small) {
                    Text(
                        entry.word.contextSentence,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }
            }
            if (session.revealed) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SectionLabel("Meaning")
                    Text(entry.word.meaningEn, style = MaterialTheme.typography.bodyLarge)
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SectionLabel("বাংলা")
                    Text(
                        entry.word.meaningBn,
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = BodyFont, fontWeight = FontWeight.Medium),
                    )
                }
                if (entry.word.exampleSentence.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SectionLabel("Example", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(entry.word.exampleSentence, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                Text(
                    "Say the meaning to yourself, then check.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    session.error?.let { Text(it, color = colors.highlight, style = MaterialTheme.typography.bodyMedium) }

    if (session.revealed) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { onAnswer(false) },
                modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, colors.onPanelMuted),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onPanel),
            ) {
                AnswerLabel("Forgot", "again today", colors.onPanelMuted)
            }
            Button(
                onClick = { onAnswer(true) },
                modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = colors.highlight, contentColor = colors.onHighlight),
            ) {
                AnswerLabel("Knew it", nextReviewLabel(entry.box), colors.onHighlight)
            }
        }
    } else {
        PanelButton("Show meaning", onReveal, Modifier.fillMaxWidth().heightIn(min = 64.dp))
    }
}

@Composable
private fun AnswerLabel(title: String, subtitle: String, subtitleColor: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor)
    }
}

@Composable
private fun PanelButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(containerColor = colors.highlight, contentColor = colors.onHighlight),
    ) { Text(label) }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CenterMessage(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}
