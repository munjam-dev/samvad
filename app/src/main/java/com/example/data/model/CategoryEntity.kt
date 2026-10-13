package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val iconKey: String,
    val isDefault: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
