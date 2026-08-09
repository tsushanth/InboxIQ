package com.inboxiq.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter
    fun fromLabel(label: MessageLabel): String = label.name

    @TypeConverter
    fun toLabel(value: String): MessageLabel = MessageLabel.valueOf(value)

    @TypeConverter
    fun fromSendStatus(status: SendStatus): String = status.name

    @TypeConverter
    fun toSendStatus(value: String): SendStatus = SendStatus.valueOf(value)
}

/**
 * v2 (versionCode 2) is the first release submitted for real users, so schema changes
 * from here on need a real migration, not fallbackToDestructiveMigration() — that would
 * silently wipe read-state, thread labels, and blocked numbers for anyone already updated.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN aiGeneratedConfidence REAL")
    }
}

@Database(
    entities = [MessageEntity::class, ThreadLabelEntity::class, BlockedNumberEntity::class],
    version = 8,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun threadLabelDao(): ThreadLabelDao
    abstract fun blockedNumberDao(): BlockedNumberDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "inboxiq.db",
                )
                    .addMigrations(MIGRATION_7_8)
                    // Only wipes on a downgrade (e.g. a bad build during dev) — real
                    // forward upgrades must go through an explicit migration above.
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build().also { instance = it }
            }
    }
}
