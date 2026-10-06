package com.newslearn.bd.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.newslearn.bd.NewsLearnApp
import com.newslearn.bd.ui.article.ArticleScreen
import com.newslearn.bd.ui.auth.AuthScreen
import com.newslearn.bd.ui.home.HomeScreen
import com.newslearn.bd.ui.learn.DigestScreen
import com.newslearn.bd.ui.learn.LearnScreen
import com.newslearn.bd.ui.learn.ReviewScreen
import com.newslearn.bd.ui.profile.ProfileScreen
import com.newslearn.bd.ui.quiz.QuizHubScreen
import com.newslearn.bd.ui.quiz.QuizScreen
import com.newslearn.bd.ui.saved.SavedScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val Tabs = listOf(
    Tab("home", "Today", Icons.Default.Home),
    Tab("learn", "Words", Icons.AutoMirrored.Filled.MenuBook),
    Tab("quiz", "Quiz", Icons.Default.Quiz),
    Tab("saved", "Saved", Icons.Default.Bookmark),
    Tab("profile", "Progress", Icons.Default.BarChart),
)

@Composable
fun NewsLearnRoot() {
    val container = (LocalContext.current.applicationContext as NewsLearnApp).container
    val session by container.auth.session.collectAsStateWithLifecycle()
    if (session == null) AuthScreen() else MainScaffold()
}

@Composable
private fun MainScaffold() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            // The bar belongs to the five top-level screens only.
            if (Tabs.any { it.route == currentRoute }) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                    Tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { navController.openTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        val openArticle: (Int) -> Unit = { navController.navigate("article/$it") }
        val openQuiz: (Int) -> Unit = { navController.navigate("quiz/$it") }
        val back: () -> Unit = { navController.popBackStack() }

        NavHost(navController, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") { HomeScreen(onOpenArticle = openArticle) }
            composable("learn") {
                LearnScreen(
                    onReview = { navController.navigate("review") },
                    onDigest = { navController.navigate("digest/$it") },
                )
            }
            composable("quiz") { QuizHubScreen(onOpenQuiz = openQuiz) }
            composable("saved") { SavedScreen(onOpenArticle = openArticle) }
            composable("profile") { ProfileScreen(onOpenQuiz = openQuiz) }

            composable(
                "article/{id}",
                arguments = listOf(navArgument("id") { type = NavType.IntType }),
            ) { entry ->
                ArticleScreen(
                    articleId = entry.arguments?.getInt("id") ?: 0,
                    onBack = back,
                    onOpenQuiz = openQuiz,
                )
            }
            composable("review") { ReviewScreen(onBack = back) }
            composable("digest/{period}") { entry ->
                DigestScreen(
                    period = entry.arguments?.getString("period") ?: "daily",
                    onBack = back,
                    onOpenArticle = openArticle,
                )
            }
            composable(
                "quiz/{id}",
                arguments = listOf(navArgument("id") { type = NavType.IntType }),
            ) { entry ->
                QuizScreen(
                    quizId = entry.arguments?.getInt("id") ?: 0,
                    onBack = back,
                    onOpenArticle = openArticle,
                )
            }
        }
    }
}

private fun NavHostController.openTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
