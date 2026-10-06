package com.newslearn.bd

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.newslearn.bd.data.local.AppDatabase
import com.newslearn.bd.data.local.SessionStore
import com.newslearn.bd.data.remote.ApiService
import com.newslearn.bd.data.remote.Network
import com.newslearn.bd.data.repo.AuthRepository
import com.newslearn.bd.data.repo.LearnRepository
import com.newslearn.bd.data.repo.NewsRepository
import com.newslearn.bd.data.repo.ProfileRepository
import com.newslearn.bd.data.repo.QuizRepository
import com.newslearn.bd.data.repo.VocabularyRepository

/** Everything the app shares, built once. ViewModels receive what they need from here. */
class AppContainer(context: Context, apiOverride: ApiService? = null) {
    private val sessionStore = SessionStore(context)
    private val network = Network(sessionStore)

    /** Tests pass a stand-in here so screens can be exercised without a server. */
    private val api: ApiService = apiOverride ?: network.api
    private val database = Room.databaseBuilder(context, AppDatabase::class.java, "newslearn.db")
        .fallbackToDestructiveMigration() // the database is only a cache
        .build()

    val auth = AuthRepository(network.authApi, sessionStore, database.cacheDao())
    val news = NewsRepository(api, database.cacheDao())
    val vocabulary = VocabularyRepository(api)
    val learn = LearnRepository(api)
    val quiz = QuizRepository(api)
    val profile = ProfileRepository(api)
}

class NewsLearnApp : Application() {
    lateinit var container: AppContainer
        internal set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
