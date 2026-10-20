package com.samvad.messenger.data.search

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import com.samvad.core.search.SearchEntry
import com.samvad.core.search.SearchPolicy
import com.samvad.core.search.Tokenizer

/**
 * One row per searchable message the device has seen. [body] is the raw text, kept for the result snippet;
 * [normBody] is the folded, tokenised form (see [Tokenizer]) that the FTS table below actually indexes, so
 * matching stays consistent with the pure ranking logic in `core`. Rows are written as messages arrive or
 * load (see `SearchIndexer`); nothing here is ever uploaded. Signed out, or the account deleted, the whole
 * table is dropped (see [SearchDao.clear]).
 */
@Entity(tableName = "search_messages")
data class SearchMessageEntity(
  /** "conversationId_messageId", so a re-index of the same message replaces its row. */
  @PrimaryKey val id: String,
  val conversationId: String,
  val messageId: String,
  val senderId: String,
  val createdAt: Long,
  val expiresAt: Long?,
  val kind: String,
  val flags: Int,
  @ColumnInfo(name = "body") val body: String,
  @ColumnInfo(name = "normBody") val normBody: String,
)

/** FTS index over the folded [SearchMessageEntity.normBody]; content lives in that table (`rowid`-linked). */
@Fts4(contentEntity = SearchMessageEntity::class)
@Entity(tableName = "search_messages_fts")
data class SearchMessageFts(val normBody: String)

@Dao
interface SearchDao {
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(rows: List<SearchMessageEntity>)

  @Query("DELETE FROM search_messages WHERE id IN (:ids)")
  suspend fun delete(ids: List<String>)

  @Query("DELETE FROM search_messages WHERE conversationId = :conversationId")
  suspend fun deleteChat(conversationId: String)

  @Query("DELETE FROM search_messages")
  suspend fun clear()

  @Query("SELECT COUNT(*) FROM search_messages")
  suspend fun count(): Int

  /** The [howMany] oldest rows, for the indexer to drop once the index grows past its cap. */
  @Query("SELECT id FROM search_messages ORDER BY createdAt ASC LIMIT :howMany")
  suspend fun oldest(howMany: Int): List<String>

  /**
   * Rows whose folded text matches the FTS expression (every query word as a prefix). Capped at
   * [SearchPolicy.MAX_CANDIDATES]; the caller re-checks and ranks them (pure logic, testable on the JVM).
   */
  @Transaction
  @Query(
    """SELECT m.* FROM search_messages m
       JOIN search_messages_fts f ON m.rowid = f.rowid
       WHERE f.normBody MATCH :matchExpression
       LIMIT :limit""",
  )
  suspend fun search(matchExpression: String, limit: Int = SearchPolicy.MAX_CANDIDATES): List<SearchMessageEntity>
}

@Database(entities = [SearchMessageEntity::class, SearchMessageFts::class], version = 1, exportSchema = true)
abstract class SearchDatabase : RoomDatabase() {
  abstract fun searchDao(): SearchDao

  companion object {
    @Volatile private var instance: SearchDatabase? = null

    fun get(context: Context): SearchDatabase = instance ?: synchronized(this) {
      instance ?: Room.databaseBuilder(context, SearchDatabase::class.java, "samvad_search.db")
        .fallbackToDestructiveMigration(true)
        .build().also { instance = it }
    }
  }
}

internal fun SearchEntry.toEntity(): SearchMessageEntity = SearchMessageEntity(
  id = "${conversationId}_$messageId",
  conversationId = conversationId,
  messageId = messageId,
  senderId = senderId,
  createdAt = createdAt,
  expiresAt = expiresAt,
  kind = kind,
  flags = flags,
  body = text,
  normBody = Tokenizer.indexForm(text),
)

internal fun SearchMessageEntity.toSearchEntry(): SearchEntry =
  SearchEntry(conversationId, messageId, senderId, createdAt, expiresAt, kind, flags, body)
