package com.chengjieli.medication.data

import android.content.Context
import androidx.room.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow

class DbConverters {
    @TypeConverter fun strings(value: List<String>): String = Gson().toJson(value)
    @TypeConverter fun list(value: String): List<String> = Gson().fromJson(value, object : TypeToken<List<String>>() {}.type)
}

@Dao
interface MedicationDao {
    @Query("SELECT * FROM medication_cases ORDER BY createdAt DESC") fun casesFlow(): Flow<List<CaseEntity>>
    @Query("SELECT * FROM medications ORDER BY name") fun medicationsFlow(): Flow<List<MedicationEntity>>
    @Query("SELECT * FROM dose_schedules ORDER BY time") fun schedulesFlow(): Flow<List<ScheduleEntity>>
    @Query("SELECT * FROM dose_occurrences ORDER BY originalAt DESC") fun occurrencesFlow(): Flow<List<OccurrenceEntity>>
    @Query("SELECT * FROM intake_records ORDER BY actualAt DESC") fun intakesFlow(): Flow<List<IntakeEntity>>
    @Query("SELECT * FROM medication_cases") suspend fun cases(): List<CaseEntity>
    @Query("SELECT * FROM medications") suspend fun medications(): List<MedicationEntity>
    @Query("SELECT * FROM dose_schedules") suspend fun schedules(): List<ScheduleEntity>
    @Query("SELECT * FROM dose_occurrences") suspend fun occurrences(): List<OccurrenceEntity>
    @Query("SELECT * FROM intake_records") suspend fun intakes(): List<IntakeEntity>
    @Query("SELECT * FROM expiry_locks") suspend fun locks(): List<ExpiryLockEntity>
    @Query("SELECT * FROM dose_occurrences WHERE id = :id") suspend fun occurrence(id: String): OccurrenceEntity?
    @Query("SELECT * FROM intake_records WHERE occurrenceId = :id") suspend fun intake(id: String): IntakeEntity?
    @Upsert suspend fun putCase(value: CaseEntity)
    @Upsert suspend fun putMedication(value: MedicationEntity)
    @Upsert suspend fun putSchedule(value: ScheduleEntity)
    @Upsert suspend fun putOccurrence(value: OccurrenceEntity)
    @Upsert suspend fun putIntake(value: IntakeEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun putLock(value: ExpiryLockEntity)
    @Query("DELETE FROM dose_occurrences WHERE id = :id") suspend fun deleteOccurrence(id: String)
    @Query("DELETE FROM intake_records WHERE occurrenceId = :id") suspend fun deleteIntake(id: String)
    @Query("DELETE FROM intake_records") suspend fun clearIntakes()
    @Query("DELETE FROM dose_occurrences") suspend fun clearOccurrences()
    @Query("DELETE FROM dose_schedules") suspend fun clearSchedules()
    @Query("DELETE FROM medications") suspend fun clearMedications()
    @Query("DELETE FROM medication_cases") suspend fun clearCases()
}

@Database(entities = [CaseEntity::class, MedicationEntity::class, ScheduleEntity::class,
    OccurrenceEntity::class, IntakeEntity::class, ExpiryLockEntity::class], version = 1, exportSchema = false)
@TypeConverters(DbConverters::class)
abstract class MedicationDatabase : RoomDatabase() {
    abstract fun dao(): MedicationDao
    companion object {
        @Volatile private var instance: MedicationDatabase? = null
        fun get(context: Context): MedicationDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, MedicationDatabase::class.java, "medication.db")
                .build().also { instance = it }
        }
    }
}
