package com.newslearn.bd.ui.article

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.newslearn.bd.data.remote.ArticleDetailDto
import com.newslearn.bd.data.remote.FactDto
import com.newslearn.bd.data.remote.WordDto
import com.newslearn.bd.ui.common.OfflineBanner
import com.newslearn.bd.ui.common.StateView
import com.newslearn.bd.ui.common.UiState
import com.newslearn.bd.ui.common.appViewModel
import com.newslearn.bd.ui.common.categoryLabel
import com.newslearn.bd.ui.common.relativeTime
import com.newslearn.bd.ui.learn.ExplanationContent
import com.newslearn.bd.ui.learn.rememberSpeaker

private val SentenceBreak = Regex("(?<=[.!?])\\s+")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleScreen(articleId: Int, onBack: () -> Unit, onOpenQuiz: (Int) -> Unit) {
    val viewModel = appViewModel(key = "article-$articleId") {
        ArticleViewModel(articleId, it.news, it.vocabulary, it.learn, it.quiz)
    }
    val state by viewModel.article.collectAsStateWithLifecycle()
    val offline by viewModel.offline.collectAsStateWithLifecycle()
    val explanation by viewModel.explanation.collectAsStateWithLifecycle()
    val tutor by viewModel.tutor.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tutorOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ArticleEvent.OpenQuiz -> onOpenQuiz(event.quizId)
                is ArticleEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }

    val article = (state as? UiState.Success)?.data
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (article != null) {
                        IconButton(onClick = viewModel::toggleSaved) {
                            Icon(
                                if (article.saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                contentDescription = if (article.saved) "Remove from saved" else "Save article",
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (offline) OfflineBanner()
            StateView(state, onRetry = viewModel::load) { detail ->
                ArticleContent(
                    article = detail,
                    onExplain = viewModel::explain,
                    onToggleWord = viewModel::toggleWord,
                    onStartQuiz = viewModel::startQuiz,
                    onAskTutor = { tutorOpen = true },
                )
            }
        }
    }

    explanation?.let { result ->
        ModalBottomSheet(onDismissRequest = viewModel::closeExplanation) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
            ) {
                when (result) {
                    UiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                    is UiState.Error -> Text(result.message, color = MaterialTheme.colorScheme.error)
                    is UiState.Success -> ExplanationContent(result.data)
                }
            }
        }
    }

    if (tutorOpen) {
        ModalBottomSheet(onDismissRequest = { tutorOpen = false }) {
            TutorSheet(tutor, onAsk = viewModel::ask)
        }
    }
}

@Composable
private fun ArticleContent(
    article: ArticleDetailDto,
    onExplain: (String) -> Unit,
    onToggleWord: (WordDto) -> Unit,
    onStartQuiz: () -> Unit,
    onAskTutor: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val speak = rememberSpeaker()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (article.imageUrl != null) {
            AsyncImage(
                model = article.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "${article.source} · ${categoryLabel(article.category)} · ${relativeTime(article.publishedAt)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(article.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

            Column {
                TabRow(selectedTabIndex = tab) {
                    listOf("Summary", "Easy English", "বাংলা").forEachIndexed { index, label ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
                    }
                }
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (tab) {
                        0 -> TappableSentences(article.summary, onExplain)
                        1 -> TappableSentences(article.easySummary, onExplain)
                        else -> Text(article.banglaSummary, style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(
                        if (tab == 2) "AI-generated study notes. Open the original for the full story."
                        else "AI-generated study notes. Tap a sentence to have it explained.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (article.examReason.isNotBlank()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Exam relevance ${article.examImportance}/100",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(article.examReason, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (article.vocabulary.isNotEmpty()) {
                SectionTitle("Words to learn")
                article.vocabulary.forEach { word ->
                    WordCard(word, onSpeak = { speak(word.word) }, onToggleSave = { onToggleWord(word) })
                }
            }

            if (article.facts.isNotEmpty()) {
                SectionTitle("Key facts")
                article.facts.forEach { FactRow(it) }
            }

            Button(onClick = { uriHandler.openUri(article.url) }, modifier = Modifier.fillMaxWidth()) {
                Text("Read the original at ${article.source}")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                if (article.questionCount > 0) {
                    FilledTonalButton(onClick = onStartQuiz, modifier = Modifier.weight(1f)) { Text("Take quiz") }
                }
                OutlinedButton(onClick = onAskTutor, modifier = Modifier.weight(1f)) { Text("Ask the tutor") }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

/** Body text in which every sentence can be tapped to ask for an explanation. */
@Composable
private fun TappableSentences(text: String, onSentence: (String) -> Unit) {
    val sentences = remember(text) { text.split(SentenceBreak).filter { it.isNotBlank() } }
    val annotated = remember(sentences, onSentence) {
        buildAnnotatedString {
            sentences.forEachIndexed { index, sentence ->
                withLink(LinkAnnotation.Clickable(tag = "sentence-$index") { onSentence(sentence) }) {
                    append(sentence)
                }
                if (index != sentences.lastIndex) append(" ")
            }
        }
    }
    Text(annotated, style = MaterialTheme.typography.bodyLarge)
}

@Composable
fun WordCard(word: WordDto, onSpeak: () -> Unit, onToggleSave: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(word.word, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (word.partOfSpeech.isNotBlank()) {
                        Text(
                            word.partOfSpeech,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onSpeak) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Hear ${word.word}")
                }
                IconButton(onClick = onToggleSave) {
                    Icon(
                        if (word.saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = if (word.saved) "Remove word" else "Save word",
                    )
                }
            }
            Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(word.meaningEn, style = MaterialTheme.typography.bodyLarge)
                Text(word.meaningBn, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                if (word.exampleSentence.isNotBlank()) {
                    Text(
                        "e.g. ${word.exampleSentence}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private val FactKindLabels = mapOf(
    "number" to "Number",
    "organization" to "Organisation",
    "person" to "Person",
    "place" to "Place",
    "date" to "Date",
    "fact" to "Fact",
)

@Composable
fun FactRow(fact: FactDto, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(
            FactKindLabels[fact.kind] ?: fact.kind,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(fact.text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        if (fact.detail.isNotBlank()) {
            Text(
                fact.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TutorSheet(state: TutorUiState, onAsk: (String) -> Unit) {
    var question by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Ask about this article", style = MaterialTheme.typography.titleMedium)
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState(), reverseScrolling = true),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.turns.isEmpty()) {
                Text(
                    "For example: “Explain this like I am a beginner” or “Why does this matter for the exam?”",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.turns.forEach { turn ->
                val mine = turn.role == "user"
                Column {
                    Text(
                        if (mine) "You" else "Tutor",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (mine) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    )
                    Text(turn.content, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (state.busy) CircularProgressIndicator()
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                placeholder = { Text("Your question") },
                maxLines = 3,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    onAsk(question)
                    question = ""
                },
                enabled = question.isNotBlank() && !state.busy,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}
