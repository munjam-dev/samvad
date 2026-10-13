package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.dao.CategoryDao
import com.example.data.dao.DocumentDao
import com.example.data.dao.ReminderDao
import com.example.data.model.CategoryEntity
import com.example.data.model.DocumentEntity
import com.example.data.model.ReminderEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [DocumentEntity::class, CategoryEntity::class, ReminderEntity::class],
    version = 1,
    exportSchema = false
)
abstract class SanrakshaDatabase : RoomDatabase() {

    abstract fun documentDao(): DocumentDao
    abstract fun categoryDao(): CategoryDao
    abstract fun reminderDao(): ReminderDao

    companion object {
        @Volatile
        private var INSTANCE: SanrakshaDatabase? = null

        fun getDatabase(context: Context): SanrakshaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SanrakshaDatabase::class.java,
                    "sanraksha_vault.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .addCallback(DatabaseCallback())
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private class DatabaseCallback : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                INSTANCE?.let { database ->
                    CoroutineScope(Dispatchers.IO).launch {
                        populateDefaultCategories(database.categoryDao())
                    }
                }
            }

            private suspend fun populateDefaultCategories(categoryDao: CategoryDao) {
                val defaultCategories = listOf(
                    CategoryEntity(id = "identity", name = "Identity", iconKey = "badge", isDefault = true),
                    CategoryEntity(id = "education", name = "Education", iconKey = "school", isDefault = true),
                    CategoryEntity(id = "banking", name = "Banking", iconKey = "account_balance", isDefault = true),
                    CategoryEntity(id = "insurance", name = "Insurance", iconKey = "verified_user", isDefault = true),
                    CategoryEntity(id = "medical", name = "Medical", iconKey = "medical_services", isDefault = true),
                    CategoryEntity(id = "travel", name = "Travel", iconKey = "flight", isDefault = true),
                    CategoryEntity(id = "other", name = "Other", iconKey = "folder", isDefault = true)
                )
                categoryDao.insertCategories(defaultCategories)
            }
        }
    }
}
