package com.newslearn.bd.ui.learn

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.newslearn.bd.data.remote.DigestDto
import com.newslearn.bd.data.repo.LearnRepository
import com.newslearn.bd.data.repo.userMessage
import com.newslearn.bd.ui.article.FactRow
import com.newslearn.bd.ui.common.BackTopBar
import com.newslearn.bd.ui.common.MessageView
import com.newslearn.bd.ui.common.StateView
import com.newslearn.bd.ui.common.UiState
import com.newslearn.bd.ui.common.appViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DigestViewModel(private val period: String, private val learn: LearnRepository) : ViewModel() {
    private val _state = MutableStateFlow<UiState<DigestDto>>(UiState.Loading)
    val state: StateFlow<UiState<DigestDto>> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = learn.digest(period).fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { UiState.Error(it.userMessage()) },
            )
        }
    }
}

private val RevisionTitles = listOf(
    "number" to "Numbers",
    "organization" to "Organisations",
    "person" to "People",
    "place" to "Places",
    "date" to "Dates",
    "fact" to "Other facts",
)

@Composable
fun DigestScreen(period: String, onBack: () -> Unit, onOpenArticle: (Int) -> Unit) {
    val viewModel = appViewModel(key = "digest-$period") { DigestViewModel(period, it.learn) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val title = if (period == "weekly") "This week" else "Today"

    Scaffold(topBar = { BackTopBar("$title — current affairs", onBack) }) { padding ->
        StateView(state, onRetry = viewModel::load, modifier = Modifier.padding(padding)) { digest ->
            if (digest.articleCount == 0) {
                MessageView("No news has been processed for this period yet.", Modifier.padding(padding))
                return@StateView
            }
            Column(Modifier.padding(padding).fillMaxSize()) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("By topic") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Quick revision") })
                }
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (tab == 0) {
                        digest.groups.forEach { group ->
                            item(key = "group-${group.category}") { GroupTitle(group.label) }
                            items(group.articles, key = { it.id }) { article ->
                                Card(Modifier.fillMaxWidth().clickable { onOpenArticle(article.id) }) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            article.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(article.summary, style = MaterialTheme.typography.bodyMedium)
                                        if (article.examReason.isNotBlank()) {
                                            Text(
                                                article.examReason,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.secondary,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        RevisionTitles.forEach { (kind, label) ->
                            val facts = digest.revision[kind].orEmpty()
                            if (facts.isNotEmpty()) {
                                item(key = "kind-$kind") { GroupTitle(label) }
                                items(facts) { FactRow(it) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}
