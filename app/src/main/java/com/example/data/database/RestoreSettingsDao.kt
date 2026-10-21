package com.example.data.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "restore_settings")
data class RestoreSettingsEntity(@PrimaryKey val id: Int = 1, val payload: ByteArray)

@Dao
interface RestoreSettingsDao {
    @Query("SELECT * FROM restore_settings WHERE id = 1")
    suspend fun pending(): RestoreSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(settings: RestoreSettingsEntity)

    @Query("DELETE FROM restore_settings WHERE id = 1")
    suspend fun clear()
}
