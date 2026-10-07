package com.newslearn.bd

import com.newslearn.bd.data.remote.ArticleDetailDto
import com.newslearn.bd.data.remote.CheckAnswerDto
import com.newslearn.bd.data.remote.CheckAnswerRequest
import com.newslearn.bd.data.remote.DayActivityDto
import com.newslearn.bd.data.remote.DigestArticleDto
import com.newslearn.bd.data.remote.DigestDto
import com.newslearn.bd.data.remote.DigestGroupDto
import com.newslearn.bd.data.remote.FactDto
import com.newslearn.bd.data.remote.ProgressDto
import com.newslearn.bd.data.remote.QuestionDto
import com.newslearn.bd.data.remote.QuizDto
import com.newslearn.bd.data.remote.TopicAccuracyDto
import com.newslearn.bd.data.remote.UserDto
import com.newslearn.bd.data.remote.UserWordDto
import com.newslearn.bd.data.remote.WordDto
import java.time.LocalDate

/**
 * Sample content for the README screenshots. The article, its words, facts and question
 * are what the live pipeline produced for one real story; the progress numbers are made up.
 */
class DemoApi : FakeApi() {
    private val relaunch = WordDto(
        id = 1, word = "relaunch", partOfSpeech = "verb",
        meaningEn = "to start or introduce something again after an earlier attempt or pause",
        meaningBn = "পুনরায় চালু করা", difficulty = 2, seenIn = 3,
        exampleSentence = "The company will relaunch its website next month.",
        contextSentence = "BTRC will relaunch the NEIR system on January 1, 2027.",
        synonyms = listOf("restart", "reintroduce", "revive"),
    )
    private val operational = WordDto(
        id = 2, word = "operational", partOfSpeech = "adjective",
        meaningEn = "ready for use or working as intended", meaningBn = "কার্যক্ষম বা সক্রিয়",
        difficulty = 2, seenIn = 4, synonyms = listOf("working", "functioning", "active"),
    )
    private val evade = WordDto(
        id = 3, word = "evade", partOfSpeech = "verb",
        meaningEn = "to avoid doing or paying something that is legally required",
        meaningBn = "ফাঁকি দেওয়া বা এড়ানো", difficulty = 3, seenIn = 2, saved = true,
        exampleSentence = "Some traders try to evade tax by hiding their income.",
        contextSentence = "The register is meant to block illegal and duty-evading handsets.",
        synonyms = listOf("avoid", "dodge", "escape"),
    )
    val undermine = WordDto(
        id = 4, word = "undermine", partOfSpeech = "verb",
        meaningEn = "to weaken or lessen the effectiveness or power of something",
        meaningBn = "ক্ষুণ্ণ বা দুর্বল করা", difficulty = 3, seenIn = 6,
        exampleSentence = "Missing classes will undermine your exam preparation.",
        contextSentence = "Illegal imports undermine government revenue.",
        synonyms = listOf("weaken", "erode", "damage"),
    )

    private val summary = "The Bangladesh Telecommunication Regulatory Commission has decided to " +
        "relaunch the National Equipment Identity Register on January 1, 2027, to block illegal " +
        "and duty-evading mobile handsets. The system will be introduced in two phases."
    private val easy = "The telecommunication regulator, BTRC, will relaunch the NEIR system on " +
        "January 1, 2027. This system tracks mobile phones using their IMEI numbers to stop " +
        "illegal and stolen devices. Phones already in use will be registered automatically."
    private val reason = "A national telecom policy involving regulation, revenue and security: " +
        "a likely question for ICT posts and BCS."

    override suspend fun me() = UserDto(
        id = 1, email = "reader@example.com", displayName = "Reader",
        dailyArticleGoal = 5, dailyWordGoal = 10, preferredCategories = listOf("science_technology", "economy"),
    )

    override suspend fun updateProfile(body: com.newslearn.bd.data.remote.UpdateProfileRequest) = me()

    override suspend fun progress(): ProgressDto {
        val today = LocalDate.now()
        return ProgressDto(
            streakDays = 12, articlesRead = 84, articlesReadToday = 3, savedArticles = 9,
            wordsSaved = 163, wordsLearned = 137, wordsDue = 12, wordsSavedToday = 6, quizzesToday = 0,
            dailyArticleGoal = 5, dailyWordGoal = 10, quizzesTaken = 31, averageScorePercent = 76,
            topics = listOf(
                TopicAccuracyDto("science_technology", "Science & Technology", 60, 53, 88),
                TopicAccuracyDto("bangladesh", "Bangladesh", 90, 73, 81),
                TopicAccuracyDto("international", "International", 50, 36, 72),
                TopicAccuracyDto("economy", "Economy", 50, 27, 54),
            ),
            weakestTopic = "economy",
            last7Days = (6 downTo 0).map { back ->
                DayActivityDto(today.minusDays(back.toLong()).toString(), articlesRead = if (back == 0) 0 else 4, quizzesTaken = if (back == 0) 0 else 1)
            },
        )
    }

    override suspend fun digest(period: String) = DigestDto(
        period = period, start = LocalDate.now().toString(), end = LocalDate.now().toString(), articleCount = 5,
        groups = listOf(
            DigestGroupDto(
                "science_technology", "Science & Technology",
                listOf(
                    DigestArticleDto(
                        1, "BTRC plans to relaunch NEIR on Jan 1", "The Daily Star", easy, 92, reason,
                        List(5) { FactDto("fact", "f") },
                    ),
                ),
            ),
            DigestGroupDto(
                "international", "International",
                listOf(DigestArticleDto(2, "Parliament Speaker becomes Chair of IPU Asia-Pacific Group", "Prothom Alo", "", 88)),
            ),
            DigestGroupDto(
                "bangladesh", "Bangladesh",
                listOf(DigestArticleDto(3, "Ministry issues gazette appointing 40 candidates in 49th Special BCS", "Prothom Alo", "", 85)),
            ),
            DigestGroupDto(
                "economy", "Economy",
                listOf(DigestArticleDto(4, "BSEC moves to cut stock market listing time to three months", "Prothom Alo", "", 82)),
            ),
        ),
    )

    override suspend fun article(id: Int) = ArticleDetailDto(
        id = 1, title = "BTRC plans to relaunch NEIR on Jan 1", source = "The Daily Star",
        url = "https://www.thedailystar.net", publishedAt = java.time.Instant.now().minusSeconds(3 * 3600).toString(),
        category = "science_technology", summary = summary, easySummary = easy,
        banglaSummary = "অবৈধ ও কর ফাঁকি দিয়ে আনা মোবাইল হ্যান্ডসেট বন্ধ করতে আগামী ২০২৭ সালের ১ জানুয়ারি থেকে এনইআইআর পুনরায় চালুর সিদ্ধান্ত নিয়েছে বিটিআরসি।",
        examImportance = 92, examImportant = true, examReason = reason, questionCount = 3,
        vocabulary = listOf(relaunch, operational, evade, undermine),
        facts = listOf(
            FactDto("number", "Tk 2,000 crore", "Annual revenue loss cited by NBR due to illegal handset imports"),
            FactDto("date", "January 1, 2027", "Target date set by BTRC to relaunch the first phase of NEIR"),
            FactDto("organization", "NEIR", "National Equipment Identity Register"),
        ),
    )

    override suspend fun dueWords(limit: Int) = List(12) { index ->
        UserWordDto(word = evade.copy(id = 100 + index), box = 2, reviewCount = 2)
    }

    override suspend fun quiz(id: Int) = QuizDto(
        id = 1, kind = "daily", title = "Daily quiz", instantFeedback = true,
        questions = listOf(
            QuestionDto(
                1, "What is the full form of the abbreviation NEIR?",
                listOf(
                    "National Electronic Information Registry",
                    "National Equipment Identity Register",
                    "Network Equipment Identification Record",
                    "National Electronic IMEI Register",
                ),
                "science_technology", 1,
            ),
        ) + List(9) { QuestionDto(2 + it, "Question", listOf("a", "b", "c", "d"), "economy", 1) },
    )

    override suspend fun checkAnswer(id: Int, body: CheckAnswerRequest) = CheckAnswerDto(
        questionId = body.questionId, correct = body.selectedIndex == 1, correctIndex = 1, articleId = 1,
        explanation = "NEIR is a central database that checks each phone's IMEI number so illegal handsets can be blocked.",
    )
}
