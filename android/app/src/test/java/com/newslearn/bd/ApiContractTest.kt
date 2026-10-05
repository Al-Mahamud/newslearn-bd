package com.newslearn.bd

import com.newslearn.bd.data.remote.ApiJson
import com.newslearn.bd.data.remote.ArticleDetailDto
import com.newslearn.bd.data.remote.ArticlePageDto
import com.newslearn.bd.data.remote.AttemptResultDto
import com.newslearn.bd.data.remote.AttemptSummaryDto
import com.newslearn.bd.data.remote.CategoryDto
import com.newslearn.bd.data.remote.DigestDto
import com.newslearn.bd.data.remote.ExplainResponseDto
import com.newslearn.bd.data.remote.ProgressDto
import com.newslearn.bd.data.remote.QuizDto
import com.newslearn.bd.data.remote.SaveWordRequest
import com.newslearn.bd.data.remote.SearchResponseDto
import com.newslearn.bd.data.remote.SubmitQuizRequest
import com.newslearn.bd.data.remote.AnswerDto
import com.newslearn.bd.data.remote.TokenResponse
import com.newslearn.bd.data.remote.TutorResponseDto
import com.newslearn.bd.data.remote.UserDto
import com.newslearn.bd.data.remote.UserWordDto
import com.newslearn.bd.ui.common.relativeTime
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decodes responses captured from the real backend (see backend/scripts/export_fixtures.py),
 * so a renamed or retyped field on either side fails here instead of on a phone.
 */
class ApiContractTest {
    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name.json")) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }

    private inline fun <reified T> decode(name: String): T = ApiJson.decodeFromString(fixture(name))

    @Test
    fun signInResponse() {
        val tokens = decode<TokenResponse>("token")
        assertTrue(tokens.accessToken.isNotEmpty() && tokens.refreshToken.isNotEmpty())
        assertEquals("a@example.com", tokens.user.email)
        assertEquals("intermediate", decode<UserDto>("user").englishLevel)
    }

    @Test
    fun feedPage() {
        val page = decode<ArticlePageDto>("feed")
        assertEquals(2, page.items.size)
        assertNotNull(page.nextCursor)
        val card = page.items.first()
        assertEquals("Test Times", card.source)
        assertTrue(card.wordCount > 0)
        assertTrue(relativeTime(card.publishedAt).isNotEmpty())
    }

    @Test
    fun articleDetail() {
        val article = decode<ArticleDetailDto>("article")
        assertTrue(article.easySummary.isNotEmpty() && article.banglaSummary.isNotEmpty())
        assertTrue(article.vocabulary.isNotEmpty())
        assertTrue(article.vocabulary.first().meaningBn.isNotEmpty())
        assertTrue(article.vocabulary.first().contextSentence.isNotEmpty())
        assertTrue(article.facts.any { it.kind == "number" })
        assertEquals(1, article.questionCount)
        assertNull(article.imageUrl)
    }

    @Test
    fun vocabulary() {
        val saved = decode<UserWordDto>("user_word")
        assertTrue(saved.word.saved)
        assertFalse(saved.learned)
        assertEquals(1, saved.articleId)
        assertEquals(1, decode<List<UserWordDto>>("vocabulary").size)
    }

    @Test
    fun learning() {
        val explanation = decode<ExplainResponseDto>("explain")
        assertTrue(explanation.simpleEnglish.isNotEmpty() && explanation.bangla.isNotEmpty())
        assertTrue(decode<TutorResponseDto>("tutor").answer.isNotEmpty())

        val digest = decode<DigestDto>("digest")
        assertEquals(3, digest.articleCount)
        assertTrue(digest.groups.first().articles.isNotEmpty())
        assertTrue(digest.revision.getValue("number").isNotEmpty())

        val search = decode<SearchResponseDto>("search")
        assertEquals(3, search.articles.size)
        assertEquals(7, decode<List<CategoryDto>>("categories").size)
    }

    @Test
    fun quizzes() {
        val quiz = decode<QuizDto>("quiz")
        assertEquals(4, quiz.questions.first().options.size)
        assertNotNull(decode<QuizDto>("quiz_timed").timeLimitSeconds)

        val result = decode<AttemptResultDto>("attempt")
        assertEquals(result.total, result.review.size)
        assertTrue(result.review.first().correct)
        assertEquals(1, decode<List<AttemptSummaryDto>>("attempts").size)
    }

    @Test
    fun progress() {
        val progress = decode<ProgressDto>("progress")
        assertEquals(1, progress.streakDays)
        assertEquals(1, progress.articlesRead)
        assertEquals(1, progress.wordsSaved)
        assertEquals(7, progress.last7Days.size)
        assertEquals("economy", progress.topics.single().category)
    }

    @Test
    fun requestsUseTheServersFieldNames() {
        assertEquals("""{"article_id":5}""", ApiJson.encodeToString(SaveWordRequest(articleId = 5)))
        // An unanswered question is sent without selected_index rather than as null.
        assertEquals(
            """{"answers":[{"question_id":3}],"duration_seconds":9}""",
            ApiJson.encodeToString(SubmitQuizRequest(listOf(AnswerDto(3)), durationSeconds = 9)),
        )
    }
}
