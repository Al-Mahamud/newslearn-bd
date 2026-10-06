package com.newslearn.bd.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.newslearn.bd.data.remote.ProgressDto
import com.newslearn.bd.data.repo.FeedFilter
import com.newslearn.bd.data.repo.LearnRepository
import com.newslearn.bd.data.repo.ProfileRepository
import com.newslearn.bd.data.repo.userMessage
import com.newslearn.bd.ui.common.ContentCard
import com.newslearn.bd.ui.common.ExamBadge
import com.newslearn.bd.ui.common.LoadingView
import com.newslearn.bd.ui.common.MessageView
import com.newslearn.bd.ui.common.OfflineBanner
import com.newslearn.bd.ui.common.Panel
import com.newslearn.bd.ui.common.ProgressRing
import com.newslearn.bd.ui.common.Speaker
import com.newslearn.bd.ui.common.UiState
import com.newslearn.bd.ui.common.appViewModel
import com.newslearn.bd.ui.common.categoryLabel
import com.newslearn.bd.ui.common.rememberSpeaker
import com.newslearn.bd.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One of the day's most exam-relevant stories, with the reason it was chosen. */
data class Pick(
    val id: Int,
    val title: String,
    val source: String,
    val category: String,
    val summary: String,
    val score: Int,
    val reason: String,
    val factCount: Int,
)

data class TopPicks(val picks: List<Pick>, val fromThisWeek: Boolean)

data class TodayUiState(
    val picks: UiState<TopPicks> = UiState.Loading,
    val progress: ProgressDto? = null,
    val refreshing: Boolean = false,
)

class TodayViewModel(
    private val learn: LearnRepository,
    private val profile: ProfileRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(TodayUiState())
    val state: StateFlow<TodayUiState> = _state.asStateFlow()

    init {
        loadPicks()
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        loadPicks()
        loadProgress()
    }

    fun loadProgress() {
        viewModelScope.launch {
            profile.progress().onSuccess { progress -> _state.update { it.copy(progress = progress) } }
        }
    }

    private fun loadPicks() {
        viewModelScope.launch {
            val result = learn.digest("daily").mapCatching { daily ->
                // Early in the day there may be little news yet; the week's best fills in.
                if (daily.articleCount >= MIN_DAILY_PICKS) {
                    TopPicks(daily.toPicks(), fromThisWeek = false)
                } else {
                    TopPicks(learn.digest("weekly").getOrThrow().toPicks(), fromThisWeek = true)
                }
            }
            _state.update {
                it.copy(
                    refreshing = false,
                    picks = result.fold(
                        onSuccess = { picks -> UiState.Success(picks) },
                        onFailure = { error -> UiState.Error(error.userMessage()) },
                    ),
                )
            }
        }
    }

    private fun com.newslearn.bd.data.remote.DigestDto.toPicks(): List<Pick> =
        groups.flatMap { group ->
            group.articles.map { a ->
                Pick(a.id, a.title, a.source, group.category, a.summary, a.examImportance, a.examReason, a.facts.size)
            }
        }.sortedByDescending { it.score }.take(MAX_PICKS)

    private companion object {
        const val MIN_DAILY_PICKS = 3
        const val MAX_PICKS = 15
    }
}

/** null filter = the Top picks tab. */
private data class Tab(val label: String, val filter: FeedFilter?)

private val Tabs = listOf(
    Tab("Top picks", null),
    Tab("ICT", FeedFilter(category = "science_technology")),
    Tab("Bangladesh", FeedFilter(category = "bangladesh")),
    Tab("Economy", FeedFilter(category = "economy")),
    Tab("International", FeedFilter(category = "international")),
    Tab("Environment", FeedFilter(category = "environment")),
    Tab("Sports", FeedFilter(category = "sports")),
    Tab("For you", FeedFilter(forYou = true)),
    Tab("Latest", FeedFilter()),
)

private val HeaderDate = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)

@Composable
fun HomeScreen(onOpenArticle: (Int) -> Unit) {
    val today = appViewModel { TodayViewModel(it.learn, it.profile) }
    val feed = appViewModel { FeedViewModel(it.news, Tabs[1].filter ?: FeedFilter()) }
    val todayState by today.state.collectAsStateWithLifecycle()
    val feedState by feed.state.collectAsStateWithLifecycle()
    val speaker = rememberSpeaker()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    // The numbers in the summary change whenever the reader comes back from an article or quiz.
    LifecycleResumeEffect(today) {
        today.loadProgress()
        onPauseOrDispose { speaker.stop() }
    }

    Column(Modifier.fillMaxSize()) {
        if (searching) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search news") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { if (query.trim().length >= 2) feed.setFilter(FeedFilter(query = query.trim())) },
                ),
                trailingIcon = {
                    IconButton(
                        onClick = {
                            searching = false
                            query = ""
                            Tabs[tab].filter?.let(feed::setFilter)
                        },
                    ) { Icon(Icons.Default.Close, contentDescription = "Close search") }
                },
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp),
            )
        } else {
            Header(streak = todayState.progress?.streakDays, onSearch = { searching = true })
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(Tabs) { index, item ->
                    FilterChip(
                        selected = tab == index,
                        onClick = {
                            tab = index
                            item.filter?.let(feed::setFilter)
                        },
                        label = { Text(item.label) },
                        shape = CircleShape,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                            selectedContainerColor = MaterialTheme.colorScheme.tertiary,
                            selectedLabelColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                }
            }
        }

        val showFeed = searching || Tabs[tab].filter != null
        if (showFeed) {
            if (feedState.offline) OfflineBanner()
            ArticleFeed(
                state = feedState,
                emptyMessage = if (searching) "No news matches your search." else "No news here yet.",
                onRefresh = feed::refresh,
                onLoadMore = feed::loadMore,
                onOpenArticle = onOpenArticle,
                onToggleSave = feed::toggleSave,
            )
        } else {
            TopPicksList(todayState, speaker, onRefresh = today::refresh, onOpenArticle = onOpenArticle)
        }
    }
}

@Composable
private fun Header(streak: Int?, onSearch: () -> Unit) {
    val date = remember { LocalDate.now().format(HeaderDate) }
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(date, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Today's brief", style = MaterialTheme.typography.headlineMedium)
        }
        if (streak != null && streak > 0) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Default.LocalFireDepartment,
                        contentDescription = null,
                        tint = AppTheme.colors.warning,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("$streak", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    Text(
                        if (streak == 1) "day" else "days",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        IconButton(onClick = onSearch) { Icon(Icons.Default.Search, contentDescription = "Search") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopPicksList(
    state: TodayUiState,
    speaker: Speaker,
    onRefresh: () -> Unit,
    onOpenArticle: (Int) -> Unit,
) {
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Always present, even before its numbers arrive: an item added above the picks
            // later would be scrolled out of view, because the list keeps its place.
            item(key = "summary") { DailySummary(state.progress) }
            when (val picks = state.picks) {
                UiState.Loading -> item { LoadingView(Modifier.fillParentMaxHeight(0.5f)) }
                is UiState.Error -> item {
                    MessageView(picks.message, Modifier.fillParentMaxHeight(0.6f), "Try again", onRefresh)
                }
                is UiState.Success -> {
                    val list = picks.data.picks
                    if (list.isEmpty()) {
                        item {
                            MessageView(
                                "No analysed news yet. New stories appear here as they are processed.",
                                Modifier.fillParentMaxHeight(0.6f),
                            )
                        }
                    } else {
                        if (picks.data.fromThisWeek) {
                            item(key = "week-note") {
                                Text(
                                    "Little news analysed today so far. Showing this week's best.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        item(key = "hero-${list.first().id}") {
                            HeroPick(list.first(), list.size, speaker) { onOpenArticle(list.first().id) }
                        }
                        itemsIndexed(list.drop(1), key = { _, pick -> pick.id }) { index, pick ->
                            PickRow(rank = index + 2, pick = pick) { onOpenArticle(pick.id) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DailySummary(progress: ProgressDto?) {
    // Each of the three goals counts for a third of the ring.
    val articleGoal = progress?.dailyArticleGoal ?: 5
    val wordGoal = progress?.dailyWordGoal ?: 5
    val read = (progress?.articlesReadToday ?: 0).coerceAtMost(articleGoal)
    val words = (progress?.wordsSavedToday ?: 0).coerceAtMost(wordGoal)
    val quizDone = (progress?.quizzesToday ?: 0) > 0
    val fraction = (read.toFloat() / articleGoal + words.toFloat() / wordGoal + (if (quizDone) 1f else 0f)) / 3f
    val percent = (fraction * 100).toInt()

    Panel(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ProgressRing(
                fraction = if (progress == null) 0f else fraction,
                centerText = if (progress == null) "–" else "$percent%",
                caption = "of goal",
                description = "Daily goal $percent percent complete",
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Daily goal", style = MaterialTheme.typography.titleMedium)
                // A dash until the numbers load, rather than a zero that might be wrong.
                SummaryRow("Articles", if (progress == null) "–" else "$read of $articleGoal")
                SummaryRow("New words", if (progress == null) "–" else "$words of $wordGoal")
                SummaryRow("Quiz", if (progress == null) "–" else if (quizDone) "Done" else "Not yet")
                if (progress != null && progress.wordsDue > 0) {
                    Text(
                        "${progress.wordsDue} word${if (progress.wordsDue == 1) "" else "s"} due for review",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.highlight,
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.onPanelMuted)
        Text(value, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun HeroPick(pick: Pick, total: Int, speaker: Speaker, onClick: () -> Unit) {
    val playing = speaker.speaking == pick.summary
    ContentCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExamBadge(score = total, label = "Pick 1 of")
                Text(
                    "${categoryLabel(pick.category)} · ${pick.source}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Text(
                    "${pick.score}",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(pick.title, style = MaterialTheme.typography.titleLarge)
            Text(pick.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
            if (pick.reason.isNotBlank()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text("Why: ${pick.reason}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (pick.factCount > 0) "${pick.factCount} facts to remember" else "Tap to study",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (speaker.available) {
                    FilledIconButton(onClick = { speaker.toggle(pick.summary) }) {
                        Icon(
                            if (playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (playing) "Stop listening" else "Listen to summary",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PickRow(rank: Int, pick: Pick, onClick: () -> Unit) {
    ContentCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "$rank",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(28.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(pick.title, style = MaterialTheme.typography.titleSmall, maxLines = 3)
                Text(
                    "${categoryLabel(pick.category)} · score ${pick.score}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
