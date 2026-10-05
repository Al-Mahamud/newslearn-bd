package com.newslearn.bd.data.repo

import com.newslearn.bd.data.local.CacheDao
import com.newslearn.bd.data.local.CachedArticle
import com.newslearn.bd.data.local.CachedFeed
import com.newslearn.bd.data.local.Session
import com.newslearn.bd.data.local.SessionStore
import com.newslearn.bd.data.remote.AnswerDto
import com.newslearn.bd.data.remote.ApiJson
import com.newslearn.bd.data.remote.ApiService
import com.newslearn.bd.data.remote.ArticleDetailDto
import com.newslearn.bd.data.remote.ArticlePageDto
import com.newslearn.bd.data.remote.AttemptResultDto
import com.newslearn.bd.data.remote.AttemptSummaryDto
import com.newslearn.bd.data.remote.AuthApi
import com.newslearn.bd.data.remote.CategoryDto
import com.newslearn.bd.data.remote.CreateQuizRequest
import com.newslearn.bd.data.remote.CredentialsRequest
import com.newslearn.bd.data.remote.DigestDto
import com.newslearn.bd.data.remote.ExplainRequest
import com.newslearn.bd.data.remote.ExplainResponseDto
import com.newslearn.bd.data.remote.ProgressDto
import com.newslearn.bd.data.remote.QuizDto
import com.newslearn.bd.data.remote.RefreshRequest
import com.newslearn.bd.data.remote.ReviewRequest
import com.newslearn.bd.data.remote.SaveWordRequest
import com.newslearn.bd.data.remote.SearchResponseDto
import com.newslearn.bd.data.remote.SubmitQuizRequest
import com.newslearn.bd.data.remote.TutorRequest
import com.newslearn.bd.data.remote.TutorTurnDto
import com.newslearn.bd.data.remote.UpdateProfileRequest
import com.newslearn.bd.data.remote.UserDto
import com.newslearn.bd.data.remote.UserWordDto
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString

class AuthRepository(
    private val authApi: AuthApi,
    private val sessionStore: SessionStore,
    private val cache: CacheDao,
) {
    val session: StateFlow<Session?> get() = sessionStore.session

    suspend fun signIn(email: String, password: String): Result<Unit> = apiCall {
        val tokens = authApi.login(CredentialsRequest(email.trim(), password))
        sessionStore.save(tokens.accessToken, tokens.refreshToken, tokens.user.email)
    }

    suspend fun register(email: String, password: String, name: String): Result<Unit> = apiCall {
        val tokens = authApi.register(CredentialsRequest(email.trim(), password, name.trim()))
        sessionStore.save(tokens.accessToken, tokens.refreshToken, tokens.user.email)
    }

    suspend fun signOut() {
        // Best effort: the local session is cleared even if the server cannot be reached.
        sessionStore.refreshToken?.let { token -> apiCall { authApi.logout(RefreshRequest(token)) } }
        sessionStore.clear()
        // Cached feeds carry this account's saved/read flags.
        cache.clearFeeds()
        cache.clearArticles()
    }
}

/** A value plus whether it came from the offline cache rather than the server. */
data class Cached<T>(val value: T, val fromCache: Boolean)

data class FeedFilter(
    val category: String? = null,
    val exam: Boolean = false,
    val forYou: Boolean = false,
    val saved: Boolean = false,
    val query: String? = null,
) {
    val key: String get() = "c=$category|e=$exam|f=$forYou|s=$saved"
}

class NewsRepository(private val api: ApiService, private val cache: CacheDao) {

    suspend fun categories(): Result<List<CategoryDto>> = apiCall { api.categories() }

    /** A page of the feed. The first page falls back to the cached copy when offline. */
    suspend fun feed(filter: FeedFilter, cursor: String? = null): Result<Cached<ArticlePageDto>> {
        val cacheable = cursor == null && filter.query == null
        val result = apiCall {
            api.articles(
                category = filter.category,
                exam = filter.exam.takeIf { it },
                forYou = filter.forYou.takeIf { it },
                saved = filter.saved.takeIf { it },
                query = filter.query,
                cursor = cursor,
            )
        }
        result.onSuccess { page ->
            if (cacheable) {
                cache.putFeed(CachedFeed(filter.key, ApiJson.encodeToString(page), now()))
            }
        }
        return result.fold(
            onSuccess = { Result.success(Cached(it, fromCache = false)) },
            onFailure = { error ->
                val stored = if (cacheable && error.isOffline()) cache.feed(filter.key) else null
                val page = stored?.let {
                    runCatching { ApiJson.decodeFromString<ArticlePageDto>(it.json) }.getOrNull()
                }
                // A cached page cannot be continued: its cursor needs the network.
                if (page != null) Result.success(Cached(page.copy(nextCursor = null), fromCache = true))
                else Result.failure(error)
            },
        )
    }

    suspend fun article(id: Int): Result<Cached<ArticleDetailDto>> {
        val result = apiCall { api.article(id) }
        result.onSuccess { detail ->
            cache.putArticle(CachedArticle(id, ApiJson.encodeToString(detail), now()))
            cache.deleteArticlesOlderThan(now() - CACHE_TTL_MS)
        }
        return result.fold(
            onSuccess = { Result.success(Cached(it, fromCache = false)) },
            onFailure = { error ->
                val detail = if (error.isOffline()) {
                    cache.article(id)?.let {
                        runCatching { ApiJson.decodeFromString<ArticleDetailDto>(it.json) }.getOrNull()
                    }
                } else {
                    null
                }
                if (detail != null) Result.success(Cached(detail, fromCache = true)) else Result.failure(error)
            },
        )
    }

    suspend fun markRead(id: Int): Result<Unit> = apiCall { api.markRead(id) }

    suspend fun setSaved(id: Int, saved: Boolean): Result<Unit> = apiCall {
        if (saved) api.saveArticle(id) else api.unsaveArticle(id)
    }

    suspend fun search(query: String): Result<SearchResponseDto> = apiCall { api.search(query.trim()) }

    private fun Throwable.isOffline() = (this as? ApiException)?.offline == true
    private fun now() = System.currentTimeMillis()

    private companion object {
        const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000
    }
}

class VocabularyRepository(private val api: ApiService) {
    suspend fun words(status: String = "all"): Result<List<UserWordDto>> = apiCall { api.vocabulary(status) }

    suspend fun due(): Result<List<UserWordDto>> = apiCall { api.dueWords() }

    suspend fun setSaved(wordId: Int, saved: Boolean, articleId: Int? = null): Result<Unit> = apiCall {
        if (saved) {
            api.saveWord(wordId, SaveWordRequest(articleId))
            Unit
        } else {
            api.removeWord(wordId)
        }
    }

    suspend fun review(wordId: Int, remembered: Boolean): Result<UserWordDto> =
        apiCall { api.reviewWord(wordId, ReviewRequest(remembered)) }
}

class LearnRepository(private val api: ApiService) {
    suspend fun explain(sentence: String, articleId: Int? = null): Result<ExplainResponseDto> =
        apiCall { api.explain(ExplainRequest(sentence.trim(), articleId)) }

    suspend fun ask(articleId: Int, question: String, history: List<TutorTurnDto>): Result<String> =
        apiCall { api.askTutor(articleId, TutorRequest(question.trim(), history)).answer }

    /** [period] is "daily" or "weekly". */
    suspend fun digest(period: String): Result<DigestDto> = apiCall { api.digest(period) }
}

class QuizRepository(private val api: ApiService) {
    suspend fun daily(): Result<QuizDto> = apiCall { api.dailyQuiz() }

    suspend fun weekly(): Result<QuizDto> = apiCall { api.weeklyQuiz() }

    suspend fun practice(category: String?): Result<QuizDto> =
        apiCall { api.createQuiz(CreateQuizRequest(kind = "practice", category = category)) }

    suspend fun mock(): Result<QuizDto> = apiCall { api.createQuiz(CreateQuizRequest(kind = "mock")) }

    suspend fun forArticle(articleId: Int): Result<QuizDto> = apiCall { api.articleQuiz(articleId) }

    suspend fun quiz(id: Int): Result<QuizDto> = apiCall { api.quiz(id) }

    suspend fun submit(id: Int, answers: Map<Int, Int>, questionIds: List<Int>, seconds: Int): Result<AttemptResultDto> =
        apiCall {
            api.submitQuiz(
                id,
                SubmitQuizRequest(questionIds.map { AnswerDto(it, answers[it]) }, durationSeconds = seconds),
            )
        }

    suspend fun history(): Result<List<AttemptSummaryDto>> = apiCall { api.attempts() }
}

class ProfileRepository(private val api: ApiService) {
    suspend fun me(): Result<UserDto> = apiCall { api.me() }

    suspend fun progress(): Result<ProgressDto> = apiCall { api.progress() }

    suspend fun setLevel(level: String): Result<UserDto> =
        apiCall { api.updateProfile(UpdateProfileRequest(englishLevel = level)) }

    suspend fun setPreferredCategories(categories: List<String>): Result<UserDto> =
        apiCall { api.updateProfile(UpdateProfileRequest(preferredCategories = categories)) }
}
