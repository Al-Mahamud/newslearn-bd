package com.newslearn.bd.data.remote

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** Sign-in calls. Built on a client with no token handling, so a refresh can never recurse. */
interface AuthApi {
    @POST("api/v1/auth/register")
    suspend fun register(@Body body: CredentialsRequest): TokenResponse

    @POST("api/v1/auth/login")
    suspend fun login(@Body body: CredentialsRequest): TokenResponse

    @POST("api/v1/auth/refresh")
    fun refreshBlocking(@Body body: RefreshRequest): retrofit2.Call<TokenResponse>

    @POST("api/v1/auth/logout")
    suspend fun logout(@Body body: RefreshRequest)
}

interface ApiService {
    @GET("api/v1/me")
    suspend fun me(): UserDto

    @PATCH("api/v1/me")
    suspend fun updateProfile(@Body body: UpdateProfileRequest): UserDto

    @GET("api/v1/me/progress")
    suspend fun progress(): ProgressDto

    // --- articles ---

    @GET("api/v1/categories")
    suspend fun categories(): List<CategoryDto>

    @GET("api/v1/articles")
    suspend fun articles(
        @Query("category") category: String? = null,
        @Query("exam") exam: Boolean? = null,
        @Query("for_you") forYou: Boolean? = null,
        @Query("saved") saved: Boolean? = null,
        @Query("q") query: String? = null,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 20,
    ): ArticlePageDto

    @GET("api/v1/articles/{id}")
    suspend fun article(@Path("id") id: Int): ArticleDetailDto

    @POST("api/v1/articles/{id}/read")
    suspend fun markRead(@Path("id") id: Int)

    @PUT("api/v1/articles/{id}/save")
    suspend fun saveArticle(@Path("id") id: Int)

    @DELETE("api/v1/articles/{id}/save")
    suspend fun unsaveArticle(@Path("id") id: Int)

    @GET("api/v1/search")
    suspend fun search(@Query("q") query: String): SearchResponseDto

    // --- vocabulary ---

    @GET("api/v1/vocabulary")
    suspend fun vocabulary(@Query("status") status: String = "all"): List<UserWordDto>

    @GET("api/v1/vocabulary/review")
    suspend fun dueWords(@Query("limit") limit: Int = 20): List<UserWordDto>

    @PUT("api/v1/vocabulary/{id}")
    suspend fun saveWord(@Path("id") id: Int, @Body body: SaveWordRequest): UserWordDto

    @PUT("api/v1/vocabulary/{id}/known")
    suspend fun markKnown(@Path("id") id: Int): UserWordDto

    @DELETE("api/v1/vocabulary/{id}")
    suspend fun removeWord(@Path("id") id: Int)

    @POST("api/v1/vocabulary/{id}/review")
    suspend fun reviewWord(@Path("id") id: Int, @Body body: ReviewRequest): UserWordDto

    // --- learning ---

    @POST("api/v1/explain")
    suspend fun explain(@Body body: ExplainRequest): ExplainResponseDto

    @POST("api/v1/articles/{id}/ask")
    suspend fun askTutor(@Path("id") id: Int, @Body body: TutorRequest): TutorResponseDto

    @GET("api/v1/current-affairs/{period}")
    suspend fun digest(@Path("period") period: String): DigestDto

    // --- quizzes ---

    @GET("api/v1/quizzes/daily")
    suspend fun dailyQuiz(): QuizDto

    @GET("api/v1/quizzes/weekly")
    suspend fun weeklyQuiz(): QuizDto

    @POST("api/v1/quizzes")
    suspend fun createQuiz(@Body body: CreateQuizRequest): QuizDto

    @POST("api/v1/articles/{id}/quiz")
    suspend fun articleQuiz(@Path("id") id: Int): QuizDto

    @GET("api/v1/quizzes/{id}")
    suspend fun quiz(@Path("id") id: Int): QuizDto

    @POST("api/v1/quizzes/{id}/check")
    suspend fun checkAnswer(@Path("id") id: Int, @Body body: CheckAnswerRequest): CheckAnswerDto

    @POST("api/v1/quizzes/{id}/attempts")
    suspend fun submitQuiz(@Path("id") id: Int, @Body body: SubmitQuizRequest): AttemptResultDto

    @GET("api/v1/me/quiz-attempts")
    suspend fun attempts(): List<AttemptSummaryDto>
}
