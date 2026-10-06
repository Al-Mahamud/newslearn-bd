package com.newslearn.bd.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Field names are camelCase here and snake_case on the wire; the Json instance in
// Network.kt converts between them.

// --- auth ---

@Serializable
data class CredentialsRequest(val email: String, val password: String, val displayName: String = "")

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class UserDto(
    val id: Int,
    val email: String,
    val displayName: String = "",
    val englishLevel: String = "intermediate",
    val preferredCategories: List<String> = emptyList(),
    val dailyArticleGoal: Int = 5,
    val dailyWordGoal: Int = 5,
    val isAdmin: Boolean = false,
)

@Serializable
data class TokenResponse(val accessToken: String, val refreshToken: String, val user: UserDto)

@Serializable
data class UpdateProfileRequest(
    val displayName: String? = null,
    val englishLevel: String? = null,
    val preferredCategories: List<String>? = null,
    val dailyArticleGoal: Int? = null,
    val dailyWordGoal: Int? = null,
)

// --- articles ---

@Serializable
data class CategoryDto(val slug: String, val label: String)

@Serializable
data class ArticleCardDto(
    val id: Int,
    val title: String,
    val source: String,
    val url: String,
    val imageUrl: String? = null,
    val publishedAt: String,
    val category: String,
    val summary: String,
    val examImportance: Int = 0,
    val examImportant: Boolean = false,
    val wordCount: Int = 0,
    val saved: Boolean = false,
    val read: Boolean = false,
)

@Serializable
data class ArticlePageDto(val items: List<ArticleCardDto>, val nextCursor: String? = null)

@Serializable
data class WordDto(
    val id: Int,
    val word: String,
    val partOfSpeech: String = "",
    val meaningEn: String,
    val meaningBn: String,
    val exampleSentence: String = "",
    val difficulty: Int = 2,
    val contextSentence: String = "",
    val saved: Boolean = false,
    /** The reader marked it "I know it": not highlighted, not reviewed. */
    val known: Boolean = false,
    val synonyms: List<String> = emptyList(),
    /** How many articles have used this word. */
    val seenIn: Int = 0,
)

@Serializable
data class FactDto(val kind: String, val text: String, val detail: String = "")

@Serializable
data class ArticleDetailDto(
    val id: Int,
    val title: String,
    val source: String,
    val url: String,
    val imageUrl: String? = null,
    val publishedAt: String,
    val category: String,
    val summary: String,
    val examImportance: Int = 0,
    val examImportant: Boolean = false,
    val saved: Boolean = false,
    val read: Boolean = false,
    val author: String? = null,
    val easySummary: String = "",
    val banglaSummary: String = "",
    val examReason: String = "",
    val vocabulary: List<WordDto> = emptyList(),
    val facts: List<FactDto> = emptyList(),
    val questionCount: Int = 0,
)

@Serializable
data class SearchResponseDto(
    val articles: List<ArticleCardDto> = emptyList(),
    val words: List<WordDto> = emptyList(),
)

// --- vocabulary ---

@Serializable
data class SaveWordRequest(val articleId: Int? = null)

@Serializable
data class UserWordDto(
    val word: WordDto,
    val articleId: Int? = null,
    val box: Int = 0,
    val learned: Boolean = false,
    val dueAt: String = "",
    val reviewCount: Int = 0,
)

/** [rating] is "forgot", "hard" or "good". */
@Serializable
data class ReviewRequest(val rating: String)

// --- learning ---

@Serializable
data class ExplainRequest(val sentence: String, val articleId: Int? = null)

@Serializable
data class PhraseDto(val text: String, val meaningEn: String, val meaningBn: String)

@Serializable
data class ExplainResponseDto(
    val sentence: String,
    val simpleEnglish: String,
    val bangla: String,
    val words: List<PhraseDto> = emptyList(),
    val grammarNote: String = "",
    val example: String = "",
)

@Serializable
data class TutorTurnDto(val role: String, val content: String)

@Serializable
data class TutorRequest(val question: String, val history: List<TutorTurnDto> = emptyList())

@Serializable
data class TutorResponseDto(val answer: String)

@Serializable
data class DigestArticleDto(
    val id: Int,
    val title: String,
    val source: String,
    val summary: String,
    val examImportance: Int = 0,
    val examReason: String = "",
    val facts: List<FactDto> = emptyList(),
)

@Serializable
data class DigestGroupDto(val category: String, val label: String, val articles: List<DigestArticleDto>)

@Serializable
data class DigestDto(
    val period: String,
    val start: String,
    val end: String,
    val articleCount: Int = 0,
    val groups: List<DigestGroupDto> = emptyList(),
    val revision: Map<String, List<FactDto>> = emptyMap(),
)

// --- quizzes ---

@Serializable
data class QuestionDto(
    val id: Int,
    val text: String,
    val options: List<String>,
    val category: String = "",
    val articleId: Int = 0,
)

@Serializable
data class QuizDto(
    val id: Int,
    val kind: String,
    val title: String,
    val timeLimitSeconds: Int? = null,
    /** False for mock exams, where answers are shown only at the end. */
    val instantFeedback: Boolean = false,
    val questions: List<QuestionDto> = emptyList(),
)

@Serializable
data class CheckAnswerRequest(val questionId: Int, val selectedIndex: Int)

@Serializable
data class CheckAnswerDto(
    val questionId: Int,
    val correct: Boolean,
    val correctIndex: Int,
    val explanation: String = "",
    val articleId: Int = 0,
)

@Serializable
data class CreateQuizRequest(
    val kind: String = "practice",
    val category: String? = null,
    val count: Int? = null,
    val timed: Boolean = false,
)

@Serializable
data class AnswerDto(val questionId: Int, val selectedIndex: Int? = null)

@Serializable
data class SubmitQuizRequest(val answers: List<AnswerDto>, val durationSeconds: Int? = null)

@Serializable
data class AnswerReviewDto(
    val questionId: Int,
    val text: String,
    val options: List<String>,
    val selectedIndex: Int? = null,
    val correctIndex: Int,
    val correct: Boolean,
    val explanation: String = "",
    val articleId: Int = 0,
)

@Serializable
data class AttemptResultDto(
    val attemptId: Int,
    val quizTitle: String = "",
    val score: Int,
    val total: Int,
    val percent: Int,
    val review: List<AnswerReviewDto> = emptyList(),
)

@Serializable
data class AttemptSummaryDto(
    val attemptId: Int,
    val quizTitle: String,
    val kind: String,
    val score: Int,
    val total: Int,
    val percent: Int,
    val submittedAt: String,
)

// --- progress ---

@Serializable
data class TopicAccuracyDto(
    val category: String,
    val label: String,
    val answered: Int,
    val correct: Int,
    val percent: Int,
)

@Serializable
data class DayActivityDto(val day: String, val articlesRead: Int = 0, val quizzesTaken: Int = 0)

@Serializable
data class ProgressDto(
    val streakDays: Int = 0,
    val articlesRead: Int = 0,
    val articlesReadToday: Int = 0,
    val savedArticles: Int = 0,
    val wordsSaved: Int = 0,
    val wordsLearned: Int = 0,
    val wordsDue: Int = 0,
    val wordsSavedToday: Int = 0,
    val quizzesToday: Int = 0,
    val dailyArticleGoal: Int = 5,
    val dailyWordGoal: Int = 5,
    val quizzesTaken: Int = 0,
    val averageScorePercent: Int = 0,
    val topics: List<TopicAccuracyDto> = emptyList(),
    val weakestTopic: String? = null,
    // The naming strategy would produce "last7_days".
    @SerialName("last_7_days") val last7Days: List<DayActivityDto> = emptyList(),
)
