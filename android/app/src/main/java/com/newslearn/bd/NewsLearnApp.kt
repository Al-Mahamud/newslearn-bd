package com.newslearn.bd

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.newslearn.bd.data.local.AppDatabase
import com.newslearn.bd.data.local.SessionStore
import com.newslearn.bd.data.remote.Network
import com.newslearn.bd.data.repo.AuthRepository
import com.newslearn.bd.data.repo.LearnRepository
import com.newslearn.bd.data.repo.NewsRepository
import com.newslearn.bd.data.repo.ProfileRepository
import com.newslearn.bd.data.repo.QuizRepository
import com.newslearn.bd.data.repo.VocabularyRepository

/** Everything the app shares, built once. ViewModels receive what they need from here. */
class AppContainer(context: Context) {
    private val sessionStore = SessionStore(context)
    private val network = Network(sessionStore)
    private val database = Room.databaseBuilder(context, AppDatabase::class.java, "newslearn.db")
        .fallbackToDestructiveMigration() // the database is only a cache
        .build()

    val auth = AuthRepository(network.authApi, sessionStore, database.cacheDao())
    val news = NewsRepository(network.api, database.cacheDao())
    val vocabulary = VocabularyRepository(network.api)
    val learn = LearnRepository(network.api)
    val quiz = QuizRepository(network.api)
    val profile = ProfileRepository(network.api)
}

class NewsLearnApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
