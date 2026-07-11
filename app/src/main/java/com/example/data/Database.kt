package com.example.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "batches")
data class DbBatch(
    @PrimaryKey val id: String,
    val name: String,
    val startedAt: Long,
    val device: String?
)

@Entity(
    tableName = "scans",
    foreignKeys = [
        ForeignKey(
            entity = DbBatch::class,
            parentColumns = ["id"],
            childColumns = ["batchId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["batchId"])]
)
data class DbScan(
    @PrimaryKey val id: String,
    val batchId: String,
    val data: String,
    val type: String,
    val timestamp: Long,
    val sent: Boolean,
    val count: Int = 1
)

@Entity(tableName = "devices")
data class DbDevice(
    @PrimaryKey val host: String,
    val port: Int,
    val name: String,
    val lastSeen: Long
)

@Dao
interface ScanDao {
    @Query("SELECT * FROM batches ORDER BY startedAt DESC")
    fun getAllBatches(): Flow<List<DbBatch>>

    @Query("SELECT * FROM batches WHERE id = :batchId LIMIT 1")
    suspend fun getBatchById(batchId: String): DbBatch?

    @Query("SELECT * FROM scans ORDER BY timestamp DESC")
    fun getAllScans(): Flow<List<DbScan>>

    @Query("SELECT * FROM scans WHERE batchId = :batchId ORDER BY timestamp DESC")
    fun getScansForBatch(batchId: String): Flow<List<DbScan>>

    @Query("SELECT * FROM scans WHERE batchId = :batchId ORDER BY timestamp DESC")
    suspend fun getScansForBatchSync(batchId: String): List<DbScan>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatch(batch: DbBatch)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScan(scan: DbScan)

    @Query("UPDATE batches SET name = :name WHERE id = :id")
    suspend fun renameBatch(id: String, name: String)

    @Query("UPDATE scans SET sent = :sent WHERE id = :id")
    suspend fun updateScanSent(id: String, sent: Boolean)

    @Query("DELETE FROM batches WHERE id = :id")
    suspend fun deleteBatch(id: String)

    @Query("DELETE FROM batches")
    suspend fun clearAllBatches()

    @Query("DELETE FROM scans")
    suspend fun clearAllScans()

    // Device actions
    @Query("SELECT * FROM devices ORDER BY lastSeen DESC")
    fun getAllDevices(): Flow<List<DbDevice>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevice(device: DbDevice)

    @Query("DELETE FROM devices WHERE host = :host")
    suspend fun deleteDevice(host: String)

    @Query("UPDATE devices SET name = :name WHERE host = :host")
    suspend fun renameDevice(host: String, name: String)
}

@Database(entities = [DbBatch::class, DbScan::class, DbDevice::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scanDao(): ScanDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "scanner_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
