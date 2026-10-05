package com.newslearn.bd.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.newslearn.bd.data.remote.ArticleCardDto
import com.newslearn.bd.data.repo.FeedFilter
import com.newslearn.bd.data.repo.NewsRepository
import com.newslearn.bd.data.repo.userMessage
import com.newslearn.bd.ui.common.CategoryLabels
import com.newslearn.bd.ui.common.LoadingView
import com.newslearn.bd.ui.common.MessageView
import com.newslearn.bd.ui.common.OfflineBanner
import com.newslearn.bd.ui.common.appViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FeedUiState(
    val filter: FeedFilter = FeedFilter(),
    val items: List<ArticleCardDto> = emptyList(),
    val nextCursor: String? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val offline: Boolean = false,
    val error: String? = null,
)

/** Drives any list of articles: the home feed, search results and the saved list. */
class FeedViewModel(private val news: NewsRepository, initialFilter: FeedFilter = FeedFilter()) :
    ViewModel() {
    private val _state = MutableStateFlow(FeedUiState(filter = initialFilter))
    val state: StateFlow<FeedUiState> = _state.asStateFlow()
    private var loadJob: Job? = null

    init {
        load()
    }

    fun setFilter(filter: FeedFilter) {
        if (filter == _state.value.filter) return
        _state.update { FeedUiState(filter = filter) }
        load()
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        load()
    }

    private fun load() {
        // A newer request (filter change, refresh) replaces one still in flight.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val filter = _state.value.filter
            news.feed(filter).fold(
                onSuccess = { page ->
                    _state.update {
                        it.copy(
                            items = page.value.items,
                            nextCursor = page.value.nextCursor,
                            offline = page.fromCache,
                            loading = false,
                            refreshing = false,
                            error = null,
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(loading = false, refreshing = false, error = error.userMessage())
                    }
                },
            )
        }
    }

    fun loadMore() {
        val current = _state.value
        val cursor = current.nextCursor ?: return
        if (current.loadingMore || current.loading || current.refreshing) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val result = news.feed(current.filter, cursor)
            _state.update { state ->
                // Ignore a page that arrives after the filter changed.
                if (state.filter != current.filter) return@update state
                val page = result.getOrNull()?.value
                if (page == null) {
                    state.copy(loadingMore = false)
                } else {
                    val known = state.items.mapTo(HashSet()) { it.id }
                    state.copy(
                        items = state.items + page.items.filterNot { it.id in known },
                        nextCursor = page.nextCursor,
                        loadingMore = false,
                    )
                }
            }
        }
    }

    fun toggleSave(article: ArticleCardDto) {
        val saved = !article.saved
        setSavedLocally(article.id, saved)
        viewModelScope.launch {
            // Undo the optimistic change if the server did not accept it.
            news.setSaved(article.id, saved).onFailure { setSavedLocally(article.id, !saved) }
        }
    }

    private fun setSavedLocally(id: Int, saved: Boolean) = _state.update { state ->
        state.copy(items = state.items.map { if (it.id == id) it.copy(saved = saved) else it })
    }
}

private data class FeedChip(val label: String, val filter: FeedFilter)

private val Chips: List<FeedChip> = buildList {
    add(FeedChip("All", FeedFilter()))
    add(FeedChip("For you", FeedFilter(forYou = true)))
    add(FeedChip("Exam important", FeedFilter(exam = true)))
    CategoryLabels.filterKeys { it != "other" }.forEach { (slug, label) ->
        add(FeedChip(label, FeedFilter(category = slug)))
    }
}

@Composable
fun HomeScreen(onOpenArticle: (Int) -> Unit) {
    val viewModel = appViewModel { FeedViewModel(it.news) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        if (searching) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search news") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        if (query.trim().length >= 2) viewModel.setFilter(FeedFilter(query = query.trim()))
                    },
                ),
                trailingIcon = {
                    IconButton(
                        onClick = {
                            searching = false
                            query = ""
                            viewModel.setFilter(FeedFilter())
                        },
                    ) { Icon(Icons.Default.Close, contentDescription = "Close search") }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item {
                    IconButton(onClick = { searching = true }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                }
                items(Chips) { chip ->
                    FilterChip(
                        selected = state.filter == chip.filter,
                        onClick = { viewModel.setFilter(chip.filter) },
                        label = { Text(chip.label) },
                    )
                }
            }
        }
        if (state.offline) OfflineBanner()
        ArticleFeed(
            state = state,
            emptyMessage = if (state.filter.query != null) "No news matches your search." else "No news here yet.",
            onRefresh = viewModel::refresh,
            onLoadMore = viewModel::loadMore,
            onOpenArticle = onOpenArticle,
            onToggleSave = viewModel::toggleSave,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleFeed(
    state: FeedUiState,
    emptyMessage: String,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenArticle: (Int) -> Unit,
    onToggleSave: (ArticleCardDto) -> Unit,
) {
    when {
        state.loading -> LoadingView()
        state.error != null && state.items.isEmpty() -> MessageView(state.error, actionLabel = "Try again", onAction = onRefresh)
        else -> {
            val listState = rememberLazyListState()
            val nearEnd by remember {
                derivedStateOf {
                    val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    last >= listState.layoutInfo.totalItemsCount - 4
                }
            }
            LaunchedEffect(nearEnd, state.nextCursor) {
                if (nearEnd && state.nextCursor != null) onLoadMore()
            }
            PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                if (state.items.isEmpty()) {
                    // Scrollable so that pull-to-refresh still works on an empty list.
                    LazyColumn(Modifier.fillMaxSize()) {
                        item { MessageView(emptyMessage, Modifier.fillParentMaxSize()) }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(state.items, key = { it.id }) { article ->
                            ArticleCard(
                                article = article,
                                onClick = { onOpenArticle(article.id) },
                                onToggleSave = { onToggleSave(article) },
                            )
                        }
                        if (state.loadingMore) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
