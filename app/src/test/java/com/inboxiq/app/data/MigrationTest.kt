package com.inboxiq.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves the v14 -> v15 upgrade keeps every existing row. This is the user's real message
 * history, so the test seeds v14 with messages, a thread label and a blocked number, migrates,
 * and re-reads them through the exported v15 schema (Room validates the schema on open).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `14 to 15 keeps messages, labels and blocks and adds the new tables`() {
        helper.createDatabase("mig", 14).use { db ->
            db.execSQL(
                "INSERT INTO messages (threadId, address, body, timestamp, isIncoming, label, labelConfidence, " +
                    "sendStatus, isRead, awaitingAutoHeal, autoHealRetryCount) " +
                    "VALUES (1, '+15550100', 'Lunch at 1?', 1000, 1, 'PERSONAL', 0.9, 'NONE', 0, 0, 0)",
            )
            db.execSQL(
                "INSERT INTO messages (threadId, address, body, timestamp, isIncoming, label, labelConfidence, " +
                    "sendStatus, isRead, awaitingAutoHeal, autoHealRetryCount) " +
                    "VALUES (1, '+15550100', 'Sure', 2000, 0, 'PERSONAL', 0, 'SENT', 1, 0, 0)",
            )
            db.execSQL("INSERT INTO thread_labels (address, label, confidence, classifiedAt) VALUES ('+15550100', 'PERSONAL', 0.9, 5)")
            db.execSQL("INSERT INTO blocked_numbers (address, blockedAt) VALUES ('+15550123', 7)")
        }

        helper.runMigrationsAndValidate("mig", 15, true, MIGRATION_14_15).use { db ->
            db.query("SELECT COUNT(*) FROM messages").use { c -> c.moveToFirst(); assertEquals(2, c.getInt(0)) }
            db.query("SELECT body, isRead FROM messages WHERE timestamp = 1000").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("Lunch at 1?", c.getString(0)); assertEquals(0, c.getInt(1))
            }
            db.query("SELECT label FROM thread_labels WHERE address = '+15550100'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("PERSONAL", c.getString(0))
            }
            db.query("SELECT COUNT(*) FROM blocked_numbers").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            db.execSQL("INSERT INTO thread_state (address, archived, labelCorrected) VALUES ('+15550100', 1, 0)")
            db.execSQL("INSERT INTO scheduled_messages (address, body, sendAt, createdAt, isUndoWindow) VALUES ('+15550100', 'hi', 9, 1, 0)")
            db.query("SELECT COUNT(*) FROM scheduled_messages").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        }
    }

    @Test
    fun `upgrade path from v14 opens through the real database builder chain`() {
        helper.createDatabase("chain", 14).close()
        val db = androidx.room.Room.databaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
            "chain",
        ).addMigrations(MIGRATION_14_15).allowMainThreadQueries().build()
        db.openHelper.writableDatabase // forces open + schema validation
        db.close()
    }
}
