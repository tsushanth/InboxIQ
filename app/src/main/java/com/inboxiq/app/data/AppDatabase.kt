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

// The LAN-only local MCP server (and its per-device pairing) is retired in favor of the
// cloud relay (inboxiq-mcp-backend) — paired-device bookkeeping now lives server-side.
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS paired_devices")
    }
}

/**
 * Contact-picked numbers saved without a country code (very common) were never normalized
 * before being used as a thread's address — but the platform's own SMS/MMS provider always
 * normalizes to E.164, so this silently split real conversations into a second, disconnected
 * thread for the same person. Confirmed live on a real device: 7 contacts affected, one of
 * them split into a 19-message thread plus a separate 2-message thread that only ever saw
 * failed MMS sends. resolvePickedContactNumber() now normalizes going forward (MainActivity) —
 * this backfills the existing damage by merging any bare 10-digit US address into its already-
 * present "+1"-prefixed counterpart, wherever both exist.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TEMP TABLE merge_targets AS
            SELECT DISTINCT address FROM messages
            WHERE address NOT LIKE '+%' AND length(address) = 10
              AND ('+1' || address) IN (SELECT address FROM messages WHERE address LIKE '+1%')
            """.trimIndent(),
        )
        // OR IGNORE: extremely unlikely to hit the (address, timestamp, body) unique index,
        // but if it ever does, skip that one row rather than aborting the whole migration.
        db.execSQL("UPDATE OR IGNORE messages SET address = '+1' || address WHERE address IN (SELECT address FROM merge_targets)")
        db.execSQL("DROP TABLE merge_targets")
    }
}

/**
 * A handful of rows from MIGRATION_11_12 didn't merge — not because they held unique content,
 * but because they were exact (timestamp, body) duplicates of a message that already existed
 * under the normalized address, so UPDATE OR IGNORE correctly skipped them to avoid violating
 * the unique index. Confirmed live: every surviving bare-address row matched this exactly.
 * Safe to delete outright — the content already exists under the correct address.
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            DELETE FROM messages WHERE id IN (
                SELECT m1.id FROM messages m1
                JOIN messages m2 ON m2.address = '+1' || m1.address
                    AND m2.timestamp = m1.timestamp AND m2.body = m1.body
                WHERE m1.address NOT LIKE '+%' AND length(m1.address) = 10
            )
            """.trimIndent(),
        )
    }
}

/**
 * MIGRATION_12_13 was undone within the same session: SmsBackfill re-reads all of
 * content://sms on every launch and was re-inserting these same bare-format rows fresh each
 * time, since the address wasn't normalized until it was already on its way into Room —
 * OnConflictStrategy.IGNORE only dedupes an exact literal match, so a freshly-normalized
 * "+1..." insert never collided with the bare row above it that had just been deleted.
 * SmsBackfill/MmsSync/SmsDeliverReceiver now normalize on the way in (see
 * PhoneNumberNormalizer), so this re-run should be the last time this is needed.
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            UPDATE OR IGNORE messages SET address = '+1' || address
            WHERE address NOT LIKE '+%' AND length(address) = 10
              AND ('+1' || address) IN (SELECT address FROM messages WHERE address LIKE '+1%')
            """.trimIndent(),
        )
        db.execSQL(
            """
            DELETE FROM messages WHERE id IN (
                SELECT m1.id FROM messages m1
                JOIN messages m2 ON m2.address = '+1' || m1.address
                    AND m2.timestamp = m1.timestamp AND m2.body = m1.body
                WHERE m1.address NOT LIKE '+%' AND length(m1.address) = 10
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
        AgentDraftEntity::class,
    ],
    version = 14,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun threadLabelDao(): ThreadLabelDao
    abstract fun blockedNumberDao(): BlockedNumberDao
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
                    .addMigrations(MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
                    // Only wipes on a downgrade (e.g. a bad build during dev) — real
                    // forward upgrades must go through an explicit migration above.
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build().also { instance = it }
            }
    }
}
