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

    @TypeConverter
    fun fromAgentDraftStatus(status: AgentDraftStatus): String = status.name

    @TypeConverter
    fun toAgentDraftStatus(value: String): AgentDraftStatus = AgentDraftStatus.valueOf(value)
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

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS paired_devices (
                id TEXT NOT NULL PRIMARY KEY,
                displayName TEXT NOT NULL,
                tokenHash TEXT NOT NULL,
                pairedAt INTEGER NOT NULL,
                lastActiveAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agent_drafts (
                id TEXT NOT NULL PRIMARY KEY,
                address TEXT NOT NULL,
                resolvedName TEXT,
                body TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                status TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }
}

@Database(
    entities = [
        MessageEntity::class,
        ThreadLabelEntity::class,
        BlockedNumberEntity::class,
        PairedDeviceEntity::class,
        AgentDraftEntity::class,
    ],
    version = 10,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun threadLabelDao(): ThreadLabelDao
    abstract fun blockedNumberDao(): BlockedNumberDao
    abstract fun pairedDeviceDao(): PairedDeviceDao
    abstract fun agentDraftDao(): AgentDraftDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "inboxiq.db",
                )
                    .addMigrations(MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                    // Only wipes on a downgrade (e.g. a bad build during dev) — real
                    // forward upgrades must go through an explicit migration above.
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build().also { instance = it }
            }
    }
}
