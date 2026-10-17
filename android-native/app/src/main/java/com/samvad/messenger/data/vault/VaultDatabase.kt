package com.samvad.messenger.data.vault

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * One vault item. The title and MIME type are inside [meta], which is sealed with the vault data key
 * (see VaultMetaCodec), so a copy of this database file reveals no names. The columns left in the
 * clear are the coarse kind, the size and two dates.
 */
@Entity(tableName = "vault_items")
class VaultItemEntity(
  @PrimaryKey val id: String,
  /** VaultItemType.name. */
  val type: String,
  @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
  @ColumnInfo(name = "created_at") val createdAt: Long,
  @ColumnInfo(name = "updated_at") val updatedAt: Long,
  @ColumnInfo(name = "has_thumb") val hasThumb: Boolean,
  @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val meta: ByteArray,
)

@Dao
interface VaultDao {
  @Query("SELECT * FROM vault_items ORDER BY updated_at DESC")
  fun observeAll(): Flow<List<VaultItemEntity>>

  @Query("SELECT * FROM vault_items ORDER BY updated_at DESC")
  suspend fun all(): List<VaultItemEntity>

  @Query("SELECT * FROM vault_items WHERE id = :id")
  suspend fun find(id: String): VaultItemEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun put(item: VaultItemEntity)

  @Query("DELETE FROM vault_items WHERE id = :id")
  suspend fun remove(id: String)

  @Query("DELETE FROM vault_items")
  suspend fun removeAll()
}

@Database(entities = [VaultItemEntity::class], version = 1, exportSchema = true)
abstract class VaultDatabase : RoomDatabase() {
  abstract fun dao(): VaultDao

  companion object {
    /**
     * The database lives in the no-backup directory, next to the encrypted files, so neither auto
     * backup nor device-to-device transfer can pick it up (the manifest and data_extraction_rules.xml
     * also exclude everything).
     */
    fun open(context: Context, root: File): VaultDatabase {
      root.mkdirs()
      return Room.databaseBuilder(context.applicationContext, VaultDatabase::class.java, File(root, "vault.db").absolutePath)
        // No fallbackToDestructiveMigration: losing a person's documents silently is never acceptable.
        .build()
    }
  }
}
