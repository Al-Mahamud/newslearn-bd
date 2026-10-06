package com.newslearn.bd.ui.article

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.newslearn.bd.data.remote.ArticleDetailDto
import com.newslearn.bd.data.remote.ExplainResponseDto
import com.newslearn.bd.data.remote.TutorTurnDto
import com.newslearn.bd.data.remote.WordDto
import com.newslearn.bd.data.repo.LearnRepository
import com.newslearn.bd.data.repo.NewsRepository
import com.newslearn.bd.data.repo.QuizRepository
import com.newslearn.bd.data.repo.VocabularyRepository
import com.newslearn.bd.data.repo.userMessage
import com.newslearn.bd.ui.common.UiState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TutorUiState(
    val turns: List<TutorTurnDto> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
)

sealed interface ArticleEvent {
    data class OpenQuiz(val quizId: Int) : ArticleEvent
    data class Message(val text: String) : ArticleEvent
}

class ArticleViewModel(
    private val articleId: Int,
    private val news: NewsRepository,
    private val vocabulary: VocabularyRepository,
    private val learn: LearnRepository,
    private val quiz: QuizRepository,
) : ViewModel() {
    private val _article = MutableStateFlow<UiState<ArticleDetailDto>>(UiState.Loading)
    val article: StateFlow<UiState<ArticleDetailDto>> = _article.asStateFlow()

    private val _offline = MutableStateFlow(false)
    val offline: StateFlow<Boolean> = _offline.asStateFlow()

    /** Null when the explanation sheet is closed. */
    private val _explanation = MutableStateFlow<UiState<ExplainResponseDto>?>(null)
    val explanation: StateFlow<UiState<ExplainResponseDto>?> = _explanation.asStateFlow()

    private val _tutor = MutableStateFlow(TutorUiState())
    val tutor: StateFlow<TutorUiState> = _tutor.asStateFlow()

    private val _events = MutableSharedFlow<ArticleEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<ArticleEvent> = _events.asSharedFlow()

    init {
        load()
    }

    fun load() {
        _article.value = UiState.Loading
        viewModelScope.launch {
            news.article(articleId).fold(
                onSuccess = { cached ->
                    _article.value = UiState.Success(cached.value)
                    _offline.value = cached.fromCache
                    if (!cached.fromCache && !cached.value.read) news.markRead(articleId)
                },
                onFailure = { _article.value = UiState.Error(it.userMessage()) },
            )
        }
    }

    private fun updateArticle(transform: (ArticleDetailDto) -> ArticleDetailDto) = _article.update {
        if (it is UiState.Success) UiState.Success(transform(it.data)) else it
    }

    fun toggleSaved() {
        val current = (_article.value as? UiState.Success)?.data ?: return
        val saved = !current.saved
        updateArticle { it.copy(saved = saved) }
        viewModelScope.launch {
            news.setSaved(articleId, saved).onFailure { error ->
                updateArticle { it.copy(saved = !saved) }
                _events.tryEmit(ArticleEvent.Message(error.userMessage()))
            }
        }
    }

    fun toggleWord(word: WordDto) {
        val saved = !word.saved
        setWordSaved(word.id, saved)
        viewModelScope.launch {
            vocabulary.setSaved(word.id, saved, articleId).onFailure { error ->
                setWordSaved(word.id, !saved)
                _events.tryEmit(ArticleEvent.Message(error.userMessage()))
            }
        }
    }

    /** Saves every word of this article that is neither saved nor marked as known. */
    fun saveAllWords() {
        val unsaved = (_article.value as? UiState.Success)?.data?.vocabulary.orEmpty()
            .filterNot { it.saved || it.known }
        unsaved.forEach(::toggleWord)
    }

    /** "I know it": the word is no longer highlighted or offered for review. */
    fun markKnown(word: WordDto) {
        val before = word
        setWordState(word.id, saved = false, known = true)
        viewModelScope.launch {
            vocabulary.markKnown(word.id).onFailure { error ->
                setWordState(word.id, saved = before.saved, known = before.known)
                _events.tryEmit(ArticleEvent.Message(error.userMessage()))
            }
        }
    }

    private fun setWordState(wordId: Int, saved: Boolean, known: Boolean) = updateArticle { article ->
        article.copy(
            vocabulary = article.vocabulary.map {
                if (it.id == wordId) it.copy(saved = saved, known = known) else it
            },
        )
    }

    private fun setWordSaved(wordId: Int, saved: Boolean) = updateArticle { article ->
        // Saving a word the reader had marked as known turns it back into a word to learn.
        article.copy(
            vocabulary = article.vocabulary.map {
                if (it.id == wordId) it.copy(saved = saved, known = if (saved) false else it.known) else it
            },
        )
    }

    fun explain(sentence: String) {
        _explanation.value = UiState.Loading
        viewModelScope.launch {
            _explanation.value = learn.explain(sentence, articleId).fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { UiState.Error(it.userMessage()) },
            )
        }
    }

    fun closeExplanation() {
        _explanation.value = null
    }

    fun ask(question: String) {
        val text = question.trim()
        if (text.isEmpty() || _tutor.value.busy) return
        val history = _tutor.value.turns
        _tutor.update { it.copy(turns = history + TutorTurnDto("user", text), busy = true, error = null) }
        viewModelScope.launch {
            learn.ask(articleId, text, history).fold(
                onSuccess = { answer ->
                    _tutor.update { it.copy(turns = it.turns + TutorTurnDto("assistant", answer), busy = false) }
                },
                onFailure = { error ->
                    // Drop the unanswered question so the history stays in user/assistant pairs.
                    _tutor.update { TutorUiState(turns = history, error = error.userMessage()) }
                },
            )
        }
    }

    fun startQuiz() {
        viewModelScope.launch {
            quiz.forArticle(articleId).fold(
                onSuccess = { _events.tryEmit(ArticleEvent.OpenQuiz(it.id)) },
                onFailure = { _events.tryEmit(ArticleEvent.Message(it.userMessage())) },
            )
        }
    }
}
