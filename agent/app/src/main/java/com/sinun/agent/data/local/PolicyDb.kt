package com.sinun.agent.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * policy cache מקומי — עיקרון fail-closed: אם השרת לא זמין,
 * ה-agent ממשיך לעבוד לפי ה-policy האחרון שנשמר כאן.
 */
@Entity(tableName = "cached_policy")
data class CachedPolicy(
    @PrimaryKey val id: Int = 1, // תמיד שורה אחת — ה-policy הפעיל
    val policyId: String,
    val version: Long,
    val rawJson: String,
    val fetchedAt: Long,
)

@Dao
interface PolicyDao {
    @Query("SELECT * FROM cached_policy WHERE id = 1")
    suspend fun get(): CachedPolicy?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(policy: CachedPolicy)
}

@Database(entities = [CachedPolicy::class], version = 1, exportSchema = false)
abstract class PolicyDb : RoomDatabase() {
    abstract fun policyDao(): PolicyDao

    companion object {
        @Volatile
        private var instance: PolicyDb? = null

        fun get(context: Context): PolicyDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, PolicyDb::class.java, "sinun.db",
                ).build().also { instance = it }
            }
    }
}
