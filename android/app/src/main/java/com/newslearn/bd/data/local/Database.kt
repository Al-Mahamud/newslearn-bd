package com.newslearn.bd.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/** The first page of a feed, stored as JSON so it can be shown without a connection. */
@Entity(tableName = "cached_feeds")
data class CachedFeed(
    @PrimaryKey val filterKey: String,
    val json: String,
    val cachedAt: Long,
)

/** An article the reader has opened. */
@Entity(tableName = "cached_articles")
data class CachedArticle(
    @PrimaryKey val id: Int,
    val json: String,
    val cachedAt: Long,
)

@Dao
interface CacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putFeed(feed: CachedFeed)

    @Query("SELECT * FROM cached_feeds WHERE filterKey = :filterKey")
    suspend fun feed(filterKey: String): CachedFeed?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putArticle(article: CachedArticle)

    @Query("SELECT * FROM cached_articles WHERE id = :id")
    suspend fun article(id: Int): CachedArticle?

    @Query("DELETE FROM cached_articles WHERE cachedAt < :before")
    suspend fun deleteArticlesOlderThan(before: Long)

    @Query("DELETE FROM cached_feeds")
    suspend fun clearFeeds()

    @Query("DELETE FROM cached_articles")
    suspend fun clearArticles()
}

@Database(entities = [CachedFeed::class, CachedArticle::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cacheDao(): CacheDao
}
