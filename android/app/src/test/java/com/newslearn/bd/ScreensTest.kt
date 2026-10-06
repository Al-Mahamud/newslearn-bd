package com.newslearn.bd

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.newslearn.bd.data.remote.ApiJson
import com.newslearn.bd.data.remote.ArticleDetailDto
import com.newslearn.bd.data.remote.DigestDto
import com.newslearn.bd.data.remote.QuizDto
import com.newslearn.bd.data.remote.UserWordDto
import com.newslearn.bd.ui.article.ArticleScreen
import com.newslearn.bd.ui.home.HomeScreen
import com.newslearn.bd.ui.learn.ReviewScreen
import com.newslearn.bd.ui.profile.ProfileScreen
import com.newslearn.bd.ui.quiz.QuizScreen
import com.newslearn.bd.ui.theme.NewsLearnTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws each redesigned screen with real captured server data and walks its main path.
 * These run on the JVM, so they prove the screens compose and respond; they do not prove
 * how the screens look on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi") // a common phone size
class ScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val api = FakeApi()

    private inline fun <reified T> fixture(name: String): T =
        ApiJson.decodeFromString(javaClass.getResourceAsStream("/fixtures/$name.json")!!.bufferedReader().readText())

    @Before
    fun useFakeServer() {
        val app = ApplicationProvider.getApplicationContext<NewsLearnApp>()
        app.container = AppContainer(app, apiOverride = api)
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { NewsLearnTheme { content() } }
    }

    private fun waitForText(text: String, substring: Boolean = false) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun today_showsSummaryAndRankedPicks() {
        val digest = fixture<DigestDto>("digest")
        val best = digest.groups.flatMap { it.articles }.maxBy { it.examImportance }
        var opened: Int? = null

        show { HomeScreen(onOpenArticle = { opened = it }) }

        waitForText("Today's brief")
        waitForText(best.title)
        compose.onNodeWithText("Articles read").assertIsDisplayed()
        compose.onNodeWithText("Pick 1 of", substring = true).assertIsDisplayed()

        compose.onAllNodesWithText(best.title).onFirst().performClick()
        compose.waitForIdle()
        assertEquals(best.id, opened)
    }

    @Test
    fun today_topicTabShowsTheFeed() {
        show { HomeScreen(onOpenArticle = {}) }
        waitForText("Today's brief")

        compose.onNodeWithText("ICT").performClick()
        waitForText("words to learn", substring = true)
    }

    @Test
    fun article_showsNotesAndOpensAWord() {
        val article = fixture<ArticleDetailDto>("article")
        val word = article.vocabulary.first { !it.saved }

        show { ArticleScreen(articleId = article.id, onBack = {}, onOpenQuiz = {}) }

        waitForText(article.title)
        compose.onNodeWithText("Easy English").assertIsDisplayed()
        compose.onNodeWithText("Quiz me · ${article.questionCount}").assertIsDisplayed()

        compose.onNodeWithText("Words to learn").performScrollTo()
        compose.onAllNodesWithText(word.word).onFirst().performScrollTo().performClick()
        waitForText("Save to my words")
        compose.onNodeWithText(word.meaningEn).assertIsDisplayed()

        compose.onNodeWithText("Save to my words").performScrollTo().performClick()
        compose.waitUntil(10_000) { word.id in api.savedWords }
        waitForText("Saved — remove from my words")
    }

    @Test
    fun article_switchesToBangla() {
        val article = fixture<ArticleDetailDto>("article")
        show { ArticleScreen(articleId = article.id, onBack = {}, onOpenQuiz = {}) }
        waitForText(article.title)

        compose.onNodeWithText("বাংলা").performClick()
        waitForText(article.banglaSummary)
    }

    @Test
    fun review_revealsThenRecordsTheAnswer() {
        val due = fixture<List<UserWordDto>>("vocabulary")
        var closed = false

        show { ReviewScreen(onBack = { closed = true }) }

        waitForText(due.first().word.word)
        compose.onNodeWithText("Show meaning").performClick()
        waitForText(due.first().word.meaningEn)

        compose.onNodeWithText("Knew it").performClick()
        compose.waitUntil(10_000) { api.reviews.isNotEmpty() }
        assertEquals(due.first().word.id to true, api.reviews.first())

        waitForText("Finish")
        compose.onNodeWithText("Finish").performClick()
        assertTrue(closed)
    }

    @Test
    fun quiz_answersOneQuestionAtATimeThenShowsTheResult() {
        val quiz = fixture<QuizDto>("quiz")
        show { QuizScreen(quizId = quiz.id, onBack = {}, onOpenArticle = {}) }

        quiz.questions.forEachIndexed { index, question ->
            waitForText("Question ${index + 1} of ${quiz.questions.size}")
            compose.onNodeWithText(question.options.first()).performClick()
            val last = index == quiz.questions.lastIndex
            compose.onNodeWithText(if (last) "Finish and see answers" else "Next question").performClick()
        }

        compose.waitUntil(10_000) { api.submitted != null }
        assertEquals(quiz.questions.map { it.id }, api.submitted!!.answers.map { it.questionId })
        assertTrue(api.submitted!!.answers.all { it.selectedIndex == 0 })
        waitForText("correct", substring = true)
        // The results are a lazily built list: scroll it until the last item exists.
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Done"))
        compose.onNodeWithText("Done").assertIsDisplayed()
    }

    @Test
    fun progress_showsStreakAndTopics() {
        show { ProfileScreen(onOpenQuiz = {}) }

        waitForText("Your progress")
        compose.onNodeWithText("day streak").assertIsDisplayed()
        compose.onNodeWithText("articles read").assertIsDisplayed()
        compose.onNodeWithText("Accuracy by topic").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("studied", substring = true).assertExists()
    }
}
