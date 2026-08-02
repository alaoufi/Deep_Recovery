package com.deeprecovery.pro.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.deeprecovery.pro.data.model.MediaType
import com.deeprecovery.pro.data.model.RecoveryQuality
import com.deeprecovery.pro.data.model.ScanDepth
import com.deeprecovery.pro.data.model.ScanMode
import com.deeprecovery.pro.data.model.ScanStatus

class Converters {

    @TypeConverter fun mediaTypeToString(value: MediaType?): String? = value?.name
    @TypeConverter fun stringToMediaType(value: String?): MediaType? =
        value?.let { MediaType.fromName(it) }

    @TypeConverter fun qualityToString(value: RecoveryQuality?): String? = value?.name
    @TypeConverter fun stringToQuality(value: String?): RecoveryQuality? =
        value?.let { RecoveryQuality.fromName(it) }

    @TypeConverter fun modeToString(value: ScanMode?): String? = value?.name
    @TypeConverter fun stringToMode(value: String?): ScanMode? =
        value?.let { ScanMode.fromName(it) }

    @TypeConverter fun depthToString(value: ScanDepth?): String? = value?.name
    @TypeConverter fun stringToDepth(value: String?): ScanDepth? =
        value?.let { ScanDepth.fromName(it) }

    @TypeConverter fun statusToString(value: ScanStatus?): String? = value?.name
    @TypeConverter fun stringToStatus(value: String?): ScanStatus? =
        value?.let { ScanStatus.fromName(it) }
}

@Database(
    entities = [
        ScanSessionEntity::class,
        ScanTargetEntity::class,
        RecoveredFileEntity::class,
        RecoveryReportEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun scanSessionDao(): ScanSessionDao
    abstract fun scanTargetDao(): ScanTargetDao
    abstract fun recoveredFileDao(): RecoveredFileDao
    abstract fun recoveryReportDao(): RecoveryReportDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "deep_recovery.db"
            )
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
