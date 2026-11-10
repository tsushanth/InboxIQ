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

    /**
     * The Transactions label adds an enum value only. Labels are stored as text, so the schema (and the exported 15.json) is
     * unchanged and there is deliberately no 15 to 16 migration. This proves what that relies on: a real v15 file with every
     * existing label, plus a TRANSACTION row and a name from the future, opens through the production builder chain and reads back.
     */
    @Test
    fun `a v15 database with every label, Transactions and an unknown name opens and reads back`() {
        helper.createDatabase("labels", 15).use { db ->
            val names = MessageLabel.entries.map { it.name } + "SOME_FUTURE_LABEL"
            names.forEachIndexed { i, n ->
                db.execSQL(
                    "INSERT INTO messages (threadId, address, body, timestamp, isIncoming, label, labelConfidence, sendStatus, isRead, awaitingAutoHeal, autoHealRetryCount) " +
                        "VALUES (1, '+1555010$i', 'b$i', ${1000 + i}, 1, '$n', 0.5, 'NONE', 1, 0, 0)",
                )
            }
            db.execSQL("INSERT INTO thread_labels (address, label, confidence, classifiedAt) VALUES ('+15550100', 'PERSONAL', 0.9, 5)")
        }
        val db = androidx.room.Room.databaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
            "labels",
        ).addMigrations(MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)
            .allowMainThreadQueries().build()
        try {
            val read = kotlinx.coroutines.runBlocking { db.messageDao().pageAfter(0, 100) }.associate { it.body to it.label }
            MessageLabel.entries.forEachIndexed { i, l -> assertEquals(l, read["b$i"]) }
            assertEquals("unknown name reads as not sorted yet", MessageLabel.UNLABELED, read["b${MessageLabel.entries.size}"])
            assertTrue(read.values.contains(MessageLabel.TRANSACTION))
            // and a Transactions row can be written back through the DAO
            kotlinx.coroutines.runBlocking {
                db.messageDao().insert(MessageEntity(threadId = 0, address = "VM-TESTBK-T", body = "new", timestamp = 9_999, isIncoming = true, label = MessageLabel.TRANSACTION))
                assertEquals(MessageLabel.TRANSACTION, db.messageDao().messagesForThread("VM-TESTBK-T").single().label)
            }
        } finally {
            db.close()
        }
    }
}
