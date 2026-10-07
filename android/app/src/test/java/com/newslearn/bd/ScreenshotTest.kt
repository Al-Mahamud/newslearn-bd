package com.newslearn.bd

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.newslearn.bd.ui.article.ArticleScreen
import com.newslearn.bd.ui.article.WordSheet
import com.newslearn.bd.ui.common.rememberSpeaker
import com.newslearn.bd.ui.home.HomeScreen
import com.newslearn.bd.ui.learn.ReviewScreen
import com.newslearn.bd.ui.profile.ProfileScreen
import com.newslearn.bd.ui.quiz.QuizScreen
import com.newslearn.bd.ui.theme.NewsLearnTheme
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Writes the README's screen images by drawing the app's real screens with sample content.
 * Skipped unless SCREENSHOT_DIR is set:
 *
 *     SCREENSHOT_DIR=../docs/images ./gradlew testDebugUnitTest --tests "*ScreenshotTest"
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val dir: File? = System.getenv("SCREENSHOT_DIR")?.let(::File)
    private val api = DemoApi()

    @Before
    fun setUp() {
        assumeTrue("SCREENSHOT_DIR is not set", dir != null)
        val app = ApplicationProvider.getApplicationContext<NewsLearnApp>()
        app.container = AppContainer(app, apiOverride = api)
    }

    private fun waitFor(text: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun capture(name: String, ready: String, steps: () -> Unit = {}, content: @Composable () -> Unit) {
        compose.setContent { NewsLearnTheme { content() } }
        waitFor(ready)
        steps()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(checkNotNull(dir), "$name.png").path)
    }

    @Test
    fun today() = capture("today", ready = "Pick 1 of") { HomeScreen(onOpenArticle = {}) }

    @Test
    fun article() = capture("article", ready = "Words to learn") {
        ArticleScreen(articleId = 1, onBack = {}, onOpenQuiz = {})
    }

    @Test
    fun word() = capture("word", ready = "undermine") {
        // The panel as it appears over a dimmed article.
        Box(Modifier.fillMaxSize().background(Color(0xFF55615A)), contentAlignment = Alignment.BottomCenter) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(Modifier.padding(top = 28.dp)) {
                    WordSheet(api.undermine, rememberSpeaker(), onToggleSave = {}, onKnown = {})
                }
            }
        }
    }

    @Test
    fun review() = capture(
        "review",
        ready = "Show meaning",
        steps = {
            compose.onNodeWithText("Show meaning").performClick()
            waitFor("Knew it")
        },
    ) { ReviewScreen(onBack = {}) }

    @Test
    fun quiz() = capture(
        "quiz",
        ready = "Question 1 of 10",
        steps = {
            compose.onNodeWithText("National Equipment Identity Register").performClick()
            compose.onNodeWithText("Check answer").performClick()
            waitFor("Correct")
        },
    ) { QuizScreen(quizId = 1, onBack = {}, onOpenArticle = {}) }

    @Test
    fun progress() = capture("progress", ready = "Accuracy by topic") { ProfileScreen(onOpenQuiz = {}) }
}
