package com.netmonitor.app.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** 1분 동안 쓴 바이트 수. minute은 분 단위로 내림한 epoch 밀리초. */
@Entity(tableName = "usage")
data class UsageEntity(
    @PrimaryKey val minute: Long,
    val wifiRx: Long,
    val wifiTx: Long,
    val mobileRx: Long,
    val mobileTx: Long,
)

/** 차트용으로 묶은 구간 하나의 사용량. */
data class UsageBucket(
    @ColumnInfo(name = "t") val t: Long,
    @ColumnInfo(name = "wifi") val wifi: Long,
    @ColumnInfo(name = "mobile") val mobile: Long,
)

@Dao
abstract class UsageDao {
    @Query("INSERT OR IGNORE INTO usage (minute, wifiRx, wifiTx, mobileRx, mobileTx) VALUES (:minute, 0, 0, 0, 0)")
    abstract suspend fun ensureRow(minute: Long)

    @Query(
        "UPDATE usage SET wifiRx = wifiRx + :wifiRx, wifiTx = wifiTx + :wifiTx, " +
            "mobileRx = mobileRx + :mobileRx, mobileTx = mobileTx + :mobileTx WHERE minute = :minute"
    )
    abstract suspend fun addToRow(minute: Long, wifiRx: Long, wifiTx: Long, mobileRx: Long, mobileTx: Long)

    @Transaction
    open suspend fun add(minute: Long, wifiRx: Long, wifiTx: Long, mobileRx: Long, mobileTx: Long) {
        ensureRow(minute)
        addToRow(minute, wifiRx, wifiTx, mobileRx, mobileTx)
    }

    @Query(
        "SELECT (minute / :bucketMs) * :bucketMs AS t, " +
            "SUM(wifiRx + wifiTx) AS wifi, SUM(mobileRx + mobileTx) AS mobile " +
            "FROM usage WHERE minute >= :from AND minute < :to GROUP BY t ORDER BY t"
    )
    abstract suspend fun buckets(from: Long, to: Long, bucketMs: Long): List<UsageBucket>

    @Query("SELECT COALESCE(SUM(wifiRx + wifiTx + mobileRx + mobileTx), 0) FROM usage WHERE minute >= :from AND minute < :to")
    abstract suspend fun total(from: Long, to: Long): Long

    @Query("DELETE FROM usage WHERE minute < :before")
    abstract suspend fun deleteOlderThan(before: Long)
}

/** 속도 측정 한 번의 결과. 속도는 초당 바이트. */
@Entity(tableName = "speed_test")
data class SpeedTestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val connection: String,
    val downBps: Double,
    val upBps: Double,
    val pingMs: Double,
)

@Dao
interface SpeedTestDao {
    @Insert
    suspend fun insert(test: SpeedTestEntity)

    @Query("SELECT * FROM speed_test WHERE time >= :from ORDER BY time")
    fun since(from: Long): Flow<List<SpeedTestEntity>>

    /** 가장 최근 두 번(최신이 먼저). 증감 표시에 쓴다. */
    @Query("SELECT * FROM speed_test ORDER BY time DESC LIMIT 2")
    fun latestTwo(): Flow<List<SpeedTestEntity>>
}

@Database(entities = [UsageEntity::class, SpeedTestEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun usageDao(): UsageDao
    abstract fun speedTestDao(): SpeedTestDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "netmonitor.db",
                ).build().also { instance = it }
            }
    }
}
