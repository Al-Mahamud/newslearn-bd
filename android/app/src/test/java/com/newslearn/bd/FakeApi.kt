package com.newslearn.bd

import com.newslearn.bd.data.remote.ApiJson
import com.newslearn.bd.data.remote.ApiService
import com.newslearn.bd.data.remote.ArticleDetailDto
import com.newslearn.bd.data.remote.ArticlePageDto
import com.newslearn.bd.data.remote.AttemptResultDto
import com.newslearn.bd.data.remote.AttemptSummaryDto
import com.newslearn.bd.data.remote.CategoryDto
import com.newslearn.bd.data.remote.CheckAnswerDto
import com.newslearn.bd.data.remote.CheckAnswerRequest
import com.newslearn.bd.data.remote.CreateQuizRequest
import com.newslearn.bd.data.remote.DigestDto
import com.newslearn.bd.data.remote.ExplainRequest
import com.newslearn.bd.data.remote.ExplainResponseDto
import com.newslearn.bd.data.remote.ProgressDto
import com.newslearn.bd.data.remote.QuizDto
import com.newslearn.bd.data.remote.ReviewRequest
import com.newslearn.bd.data.remote.SaveWordRequest
import com.newslearn.bd.data.remote.SearchResponseDto
import com.newslearn.bd.data.remote.SubmitQuizRequest
import com.newslearn.bd.data.remote.TutorRequest
import com.newslearn.bd.data.remote.TutorResponseDto
import com.newslearn.bd.data.remote.UpdateProfileRequest
import com.newslearn.bd.data.remote.UserDto
import com.newslearn.bd.data.remote.UserWordDto

/**
 * Stands in for the server in screen tests. Every answer is a response captured from the
 * real backend (src/test/resources/fixtures), so screens are exercised with real shapes.
 */
class FakeApi : ApiService {
    val savedWords = mutableListOf<Int>()
    val knownWords = mutableListOf<Int>()
    val reviews = mutableListOf<Pair<Int, String>>()
    val checks = mutableListOf<CheckAnswerRequest>()
    var goals: Pair<Int?, Int?>? = null

    /** When set, served instead of the daily quiz (e.g. a mock exam). */
    var quizOverride: QuizDto? = null
    var submitted: SubmitQuizRequest? = null

    private inline fun <reified T> fixture(name: String): T {
        val stream = checkNotNull(javaClass.getResourceAsStream("/fixtures/$name.json")) { "missing fixture $name" }
        return ApiJson.decodeFromString(stream.bufferedReader().use { it.readText() })
    }

    override suspend fun me(): UserDto = fixture("user")
    override suspend fun updateProfile(body: UpdateProfileRequest): UserDto {
        if (body.dailyArticleGoal != null || body.dailyWordGoal != null) {
            goals = body.dailyArticleGoal to body.dailyWordGoal
        }
        val user = fixture<UserDto>("user")
        return user.copy(
            dailyArticleGoal = body.dailyArticleGoal ?: user.dailyArticleGoal,
            dailyWordGoal = body.dailyWordGoal ?: user.dailyWordGoal,
        )
    }
    override suspend fun progress(): ProgressDto = fixture("progress")
    override suspend fun categories(): List<CategoryDto> = fixture("categories")

    override suspend fun articles(
        category: String?,
        exam: Boolean?,
        forYou: Boolean?,
        saved: Boolean?,
        query: String?,
        cursor: String?,
        limit: Int,
    ): ArticlePageDto = fixture<ArticlePageDto>("feed").copy(nextCursor = null)

    override suspend fun article(id: Int): ArticleDetailDto = fixture("article")
    override suspend fun markRead(id: Int) = Unit
    override suspend fun saveArticle(id: Int) = Unit
    override suspend fun unsaveArticle(id: Int) = Unit
    override suspend fun search(query: String): SearchResponseDto = fixture("search")

    override suspend fun vocabulary(status: String): List<UserWordDto> = fixture("vocabulary")
    override suspend fun dueWords(limit: Int): List<UserWordDto> = fixture("vocabulary")

    override suspend fun saveWord(id: Int, body: SaveWordRequest): UserWordDto {
        savedWords += id
        return fixture("user_word")
    }

    override suspend fun markKnown(id: Int): UserWordDto {
        knownWords += id
        return fixture("user_word")
    }

    override suspend fun removeWord(id: Int) {
        savedWords -= id
    }

    override suspend fun reviewWord(id: Int, body: ReviewRequest): UserWordDto {
        reviews += id to body.rating
        return fixture("user_word")
    }

    override suspend fun explain(body: ExplainRequest): ExplainResponseDto = fixture("explain")
    override suspend fun askTutor(id: Int, body: TutorRequest): TutorResponseDto = fixture("tutor")
    override suspend fun digest(period: String): DigestDto = fixture("digest")

    override suspend fun dailyQuiz(): QuizDto = fixture("quiz")
    override suspend fun weeklyQuiz(): QuizDto = fixture("quiz")
    override suspend fun createQuiz(body: CreateQuizRequest): QuizDto = fixture("quiz_timed")
    override suspend fun articleQuiz(id: Int): QuizDto = fixture("quiz")
    override suspend fun quiz(id: Int): QuizDto = quizOverride ?: fixture("quiz")

    /** Mirrors the mock backend's questions, whose first option is always the right one. */
    override suspend fun checkAnswer(id: Int, body: CheckAnswerRequest): CheckAnswerDto {
        checks += body
        val verdict = fixture<CheckAnswerDto>(if (body.selectedIndex == 0) "check" else "check_wrong")
        return verdict.copy(questionId = body.questionId)
    }

    override suspend fun submitQuiz(id: Int, body: SubmitQuizRequest): AttemptResultDto {
        submitted = body
        return fixture("attempt")
    }

    override suspend fun attempts(): List<AttemptSummaryDto> = fixture("attempts")
}
