package com.newslearn.bd.ui.saved

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.newslearn.bd.data.remote.UserWordDto
import com.newslearn.bd.data.repo.FeedFilter
import com.newslearn.bd.data.repo.VocabularyRepository
import com.newslearn.bd.data.repo.userMessage
import com.newslearn.bd.ui.article.WordCard
import com.newslearn.bd.ui.common.MessageView
import com.newslearn.bd.ui.common.OfflineBanner
import com.newslearn.bd.ui.common.StateView
import com.newslearn.bd.ui.common.UiState
import com.newslearn.bd.ui.common.appViewModel
import com.newslearn.bd.ui.home.ArticleFeed
import com.newslearn.bd.ui.home.FeedViewModel
import com.newslearn.bd.ui.learn.rememberSpeaker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SavedWordsViewModel(private val vocabulary: VocabularyRepository) : ViewModel() {
    private val _state = MutableStateFlow<UiState<List<UserWordDto>>>(UiState.Loading)
    val state: StateFlow<UiState<List<UserWordDto>>> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            vocabulary.words().fold(
                onSuccess = { _state.value = UiState.Success(it) },
                // Keep showing the list already on screen if a refresh fails.
                onFailure = { error ->
                    _state.update { if (it is UiState.Success) it else UiState.Error(error.userMessage()) }
                },
            )
        }
    }

    fun remove(wordId: Int) {
        viewModelScope.launch {
            vocabulary.setSaved(wordId, saved = false).onSuccess {
                _state.update { state ->
                    if (state is UiState.Success) UiState.Success(state.data.filterNot { it.word.id == wordId }) else state
                }
            }
        }
    }
}

@Composable
fun SavedScreen(onOpenArticle: (Int) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Articles") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Words") })
        }
        if (tab == 0) SavedArticles(onOpenArticle) else SavedWords()
    }
}

@Composable
private fun SavedArticles(onOpenArticle: (Int) -> Unit) {
    val viewModel = appViewModel(key = "saved-feed") { FeedViewModel(it.news, FeedFilter(saved = true)) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Articles may have been saved or removed elsewhere since this tab was last shown.
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    if (state.offline) OfflineBanner()
    ArticleFeed(
        state = state,
        emptyMessage = "Articles you save will appear here.",
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onOpenArticle = onOpenArticle,
        onToggleSave = viewModel::toggleSave,
    )
}

@Composable
private fun SavedWords() {
    val viewModel = appViewModel { SavedWordsViewModel(it.vocabulary) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val speak = rememberSpeaker()

    LifecycleResumeEffect(viewModel) {
        viewModel.load()
        onPauseOrDispose { }
    }
    StateView(state, onRetry = viewModel::load) { words ->
        if (words.isEmpty()) {
            MessageView("Words you save from articles will appear here.")
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(words, key = { it.word.id }) { entry ->
                    WordCard(
                        word = entry.word,
                        onSpeak = { speak(entry.word.word) },
                        onToggleSave = { viewModel.remove(entry.word.id) },
                    )
                }
            }
        }
    }
}
