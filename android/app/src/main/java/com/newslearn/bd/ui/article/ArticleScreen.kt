package com.newslearn.bd.ui.article

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.newslearn.bd.data.remote.ArticleDetailDto
import com.newslearn.bd.data.remote.FactDto
import com.newslearn.bd.data.remote.WordDto
import com.newslearn.bd.ui.common.ContentCard
import com.newslearn.bd.ui.common.ExamBadge
import com.newslearn.bd.ui.common.OfflineBanner
import com.newslearn.bd.ui.common.Panel
import com.newslearn.bd.ui.common.ProgressBar
import com.newslearn.bd.ui.common.SectionLabel
import com.newslearn.bd.ui.common.SegmentedTabs
import com.newslearn.bd.ui.common.Speaker
import com.newslearn.bd.ui.common.StateView
import com.newslearn.bd.ui.common.UiState
import com.newslearn.bd.ui.common.appViewModel
import com.newslearn.bd.ui.common.categoryLabel
import com.newslearn.bd.ui.common.relativeTime
import com.newslearn.bd.ui.common.rememberSpeaker
import com.newslearn.bd.ui.learn.ExplanationContent
import com.newslearn.bd.ui.theme.AppTheme

private val SentenceBreak = Regex("(?<=[.!?])\\s+")
private val TextScales = listOf(1f, 1.15f, 1.3f)
private const val BANGLA_TAB = 2

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
    val speaker = rememberSpeaker()
    val uriHandler = LocalUriHandler.current

    var tutorOpen by rememberSaveable { mutableStateOf(false) }
    var openWordId by rememberSaveable { mutableStateOf<Int?>(null) }
    var scaleIndex by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ArticleEvent.OpenQuiz -> onOpenQuiz(event.quizId)
                is ArticleEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }
    DisposableEffect(speaker) { onDispose { speaker.stop() } }

    val article = (state as? UiState.Success)?.data
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (article != null) {
                        TextButton(onClick = { scaleIndex = (scaleIndex + 1) % TextScales.size }) {
                            Text(
                                "Aa",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(onClick = viewModel::toggleSaved) {
                            Icon(
                                if (article.saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                contentDescription = if (article.saved) "Remove from saved" else "Save article",
                                tint = if (article.saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (article != null) {
                ActionBar(
                    questionCount = article.questionCount,
                    onQuiz = viewModel::startQuiz,
                    onTutor = { tutorOpen = true },
                    onOriginal = { uriHandler.openUri(article.url) },
                    // Articles from the print edition have no public page to open.
                    hasOriginal = article.url.startsWith("http"),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (offline) OfflineBanner()
            StateView(state, onRetry = viewModel::load) { detail ->
                ArticleContent(
                    article = detail,
                    speaker = speaker,
                    textScale = TextScales[scaleIndex],
                    onExplain = viewModel::explain,
                    onOpenWord = { openWordId = it.id },
                    onSaveAll = viewModel::saveAllWords,
                )
            }
        }
    }

    // Looked up by id so the sheet reflects the word's latest saved state.
    article?.vocabulary?.firstOrNull { it.id == openWordId }?.let { word ->
        ModalBottomSheet(onDismissRequest = { openWordId = null }) {
            WordSheet(word, speaker, onToggleSave = { viewModel.toggleWord(word) })
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
    speaker: Speaker,
    textScale: Float,
    onExplain: (String) -> Unit,
    onOpenWord: (WordDto) -> Unit,
    onSaveAll: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(1) } // Easy English first: it is why the reader is here
    val base = MaterialTheme.typography.bodyLarge
    val bodyStyle = base.copy(fontSize = base.fontSize * textScale, lineHeight = base.lineHeight * textScale)
    val spokenText = if (tab == 0) article.summary else article.easySummary

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (article.imageUrl != null) {
            AsyncImage(
                model = article.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
        }
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (article.examImportant) ExamBadge(article.examImportance)
                Text(
                    listOfNotNull(
                        categoryLabel(article.category),
                        article.source,
                        article.author?.takeIf { it.startsWith("Page ") }?.lowercase(),
                        relativeTime(article.publishedAt).takeIf { it.isNotEmpty() },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(article.title, style = MaterialTheme.typography.headlineSmall)

            SegmentedTabs(listOf("Summary", "Easy English", "বাংলা"), selected = tab, onSelect = {
                tab = it
                speaker.stop()
            })

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tab == BANGLA_TAB) {
                    Text(article.banglaSummary, style = bodyStyle)
                } else {
                    StudyText(spokenText, article.vocabulary, bodyStyle, onOpenWord, onExplain)
                }
                Text(
                    if (tab == BANGLA_TAB) "AI-written study notes. Open the original for the full story."
                    else "AI-written study notes. Tap a sentence to have it explained.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // The phone's voice reads English; the Bangla tab has nothing to play.
            if (speaker.available && tab != BANGLA_TAB) ListenPanel(spokenText, speaker)

            if (article.examReason.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Why it matters for your exams", style = MaterialTheme.typography.titleSmall)
                        Text(article.examReason, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (article.vocabulary.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Words to learn", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    val unsaved = article.vocabulary.count { !it.saved }
                    if (unsaved > 0) {
                        TextButton(onClick = onSaveAll) { Text(if (unsaved == 1) "Save 1" else "Save all $unsaved") }
                    }
                }
                // Two per row; an odd last word keeps its half-width so the grid stays even.
                article.vocabulary.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { word ->
                            WordTile(word, Modifier.weight(1f)) { onOpenWord(word) }
                        }
                        if (pair.size == 1) Column(Modifier.weight(1f)) {}
                    }
                }
            }

            if (article.facts.isNotEmpty()) {
                Text("Key facts", style = MaterialTheme.typography.titleMedium)
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    article.facts.forEach { FactRow(it) }
                }
            }
        }
    }
}

/**
 * Body text for studying: vocabulary words are marked and open their meaning when tapped;
 * tapping anywhere else in a sentence asks for that sentence to be explained.
 */
@Composable
private fun StudyText(
    text: String,
    words: List<WordDto>,
    style: TextStyle,
    onWord: (WordDto) -> Unit,
    onSentence: (String) -> Unit,
) {
    val currentOnWord by rememberUpdatedState(onWord)
    val currentOnSentence by rememberUpdatedState(onSentence)
    val mark = SpanStyle(background = AppTheme.colors.wordMark, fontWeight = FontWeight.Medium)

    val annotated = remember(text, words, mark) {
        // Longer entries first, so a phrase wins over a word inside it.
        val patterns = words.sortedByDescending { it.word.length }.map { word ->
            word to Regex("\\b" + Regex.escape(word.word) + "\\w*", RegexOption.IGNORE_CASE)
        }
        buildAnnotatedString {
            val sentences = text.split(SentenceBreak).filter { it.isNotBlank() }
            sentences.forEachIndexed { index, sentence ->
                val hits = mutableListOf<Pair<IntRange, WordDto>>()
                patterns.forEach { (word, regex) ->
                    regex.findAll(sentence).forEach { match ->
                        if (hits.none { it.first.first <= match.range.last && match.range.first <= it.first.last }) {
                            hits += match.range to word
                        }
                    }
                }
                var cursor = 0
                hits.sortedBy { it.first.first }.forEach { (range, word) ->
                    if (range.first > cursor) {
                        withLink(LinkAnnotation.Clickable("sentence") { currentOnSentence(sentence) }) {
                            append(sentence.substring(cursor, range.first))
                        }
                    }
                    withLink(LinkAnnotation.Clickable("word", TextLinkStyles(mark)) { currentOnWord(word) }) {
                        append(sentence.substring(range.first, range.last + 1))
                    }
                    cursor = range.last + 1
                }
                if (cursor < sentence.length) {
                    withLink(LinkAnnotation.Clickable("sentence") { currentOnSentence(sentence) }) {
                        append(sentence.substring(cursor))
                    }
                }
                if (index != sentences.lastIndex) append(" ")
            }
        }
    }
    Text(annotated, style = style)
}

@Composable
private fun ListenPanel(text: String, speaker: Speaker) {
    var rate by rememberSaveable { mutableFloatStateOf(1f) }
    val playing = speaker.speaking == text
    Panel(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 10.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledIconButton(
                onClick = { speaker.toggle(text, rate) },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = AppTheme.colors.highlight,
                    contentColor = AppTheme.colors.onHighlight,
                ),
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    if (playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (playing) "Stop" else "Listen to this summary",
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (playing) "Listening…" else "Listen to this summary", style = MaterialTheme.typography.labelLarge)
                ProgressBar(
                    fraction = if (playing) speaker.progress else 0f,
                    color = AppTheme.colors.highlight,
                    track = AppTheme.colors.panelTrack,
                    height = 4.dp,
                )
            }
            TextButton(
                onClick = {
                    rate = if (rate == 1f) 0.8f else 1f
                    if (playing) speaker.speak(text, rate)
                },
            ) {
                Text(
                    if (rate == 1f) "1x" else "0.8x",
                    color = AppTheme.colors.onPanelMuted,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun WordTile(word: WordDto, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ContentCard(modifier, onClick = onClick) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    word.word,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (word.saved) {
                    Icon(
                        Icons.Default.Bookmark,
                        contentDescription = "Saved",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            if (word.partOfSpeech.isNotBlank()) {
                Text(
                    word.partOfSpeech,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                word.meaningBn,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val DifficultyLabels = mapOf(1 to "easy", 2 to "medium", 3 to "hard")

/** Everything about one word, shown when it is tapped. */
@Composable
fun WordSheet(word: WordDto, speaker: Speaker, onToggleSave: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(word.word, style = MaterialTheme.typography.headlineLarge)
                Text(
                    listOfNotNull(word.partOfSpeech.takeIf { it.isNotBlank() }, DifficultyLabels[word.difficulty])
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (speaker.available) {
                FilledTonalIconButton(onClick = { speaker.speak(word.word, 0.9f) }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Hear ${word.word}")
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SectionLabel("Meaning")
            Text(word.meaningEn, style = MaterialTheme.typography.bodyLarge)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SectionLabel("বাংলা")
            Text(word.meaningBn, style = MaterialTheme.typography.titleLarge.copy(fontFamily = com.newslearn.bd.ui.theme.BodyFont, fontWeight = FontWeight.Medium))
        }
        if (word.exampleSentence.isNotBlank()) {
            Surface(color = MaterialTheme.colorScheme.background, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SectionLabel("Example", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(word.exampleSentence, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (word.contextSentence.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SectionLabel("In the news", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    word.contextSentence,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (word.saved) {
            OutlinedButton(onClick = onToggleSave, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("Saved — remove from my words")
            }
        } else {
            Button(onClick = onToggleSave, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("Save to my words")
            }
        }
    }
}

/** A saved word in a list (Saved screen). */
@Composable
fun WordCard(word: WordDto, onSpeak: () -> Unit, onToggleSave: () -> Unit, modifier: Modifier = Modifier) {
    ContentCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(word.word, style = MaterialTheme.typography.titleMedium)
                    if (word.partOfSpeech.isNotBlank()) {
                        Text(
                            word.partOfSpeech,
                            style = MaterialTheme.typography.bodySmall,
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
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(word.meaningEn, style = MaterialTheme.typography.bodyMedium)
                Text(
                    word.meaningBn,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.primary,
                )
                if (word.exampleSentence.isNotBlank()) {
                    Text(
                        "e.g. ${word.exampleSentence}",
                        style = MaterialTheme.typography.bodySmall,
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
    val isFigure = fact.kind == "number" || fact.kind == "date"
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionLabel(FactKindLabels[fact.kind] ?: fact.kind, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            fact.text,
            // Figures are what get asked in exams, so they are set large.
            style = if (isFigure) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
            color = if (isFigure) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
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
private fun ActionBar(
    questionCount: Int,
    onQuiz: () -> Unit,
    onTutor: () -> Unit,
    onOriginal: () -> Unit,
    hasOriginal: Boolean,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (questionCount > 0) {
                Button(onClick = onQuiz, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) {
                    Text("Quiz me · $questionCount")
                }
            }
            OutlinedButton(onClick = onTutor, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) {
                Text("Ask tutor")
            }
            if (hasOriginal) {
                OutlinedIconButton(onClick = onOriginal, modifier = Modifier.size(48.dp), shape = MaterialTheme.shapes.small) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Read the original article")
                }
            }
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
                    SectionLabel(
                        if (mine) "You" else "Tutor",
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
                shape = MaterialTheme.shapes.medium,
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
