package com.inboxiq.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A database Room would have to wipe (written by a newer build, or older than the first migration) must be
 * set aside intact first, and a database Room can open normally must never be touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class DatabaseGuardTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun setInstance(v: AppDatabase?) = AppDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, v)

    @Before fun clean() { setInstance(null); wipe() }
    @After fun after() { setInstance(null); wipe() }

    private fun wipe() {
        context.getDatabasePath("g.db").parentFile?.listFiles()?.forEach { if (it.name.startsWith("g.db") || it.name.startsWith(AppDatabase.FILE_NAME)) it.delete() }
    }

    private fun make(name: String, version: Int, marker: String = "keep me"): File {
        val f = context.getDatabasePath(name)
        f.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            db.execSQL("CREATE TABLE t (v TEXT)")
            db.execSQL("INSERT INTO t VALUES ('$marker')")
            db.version = version
        }
        return f
    }

    private fun readMarker(f: File): String =
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT v FROM t", null).use { c -> c.moveToFirst(); c.getString(0) }
        }

    @Test fun `a newer database (downgrade) is moved aside intact`() {
        val f = make("g.db", 99)
        val aside = DatabaseGuard.setAsideIfWipeAhead(context, "g.db", 15, now = 1000)
        assertNotNull(aside)
        assertFalse("original must be gone so Room starts fresh", f.exists())
        assertEquals("g.db.v99.1000.bak", aside!!.name)
        assertEquals("keep me", readMarker(aside))
    }

    @Test fun `a database older than the first migration is moved aside`() {
        make("g.db", 3)
        assertNotNull(DatabaseGuard.setAsideIfWipeAhead(context, "g.db", 15, now = 1))
    }

    @Test fun `the current version and every migratable older version are left alone`() {
        for (v in listOf(15, 14, 10, DatabaseGuard.OLDEST_MIGRATABLE_VERSION)) {
            wipe()
            val f = make("g.db", v)
            assertNull("v$v must not be touched", DatabaseGuard.setAsideIfWipeAhead(context, "g.db", 15))
            assertTrue(f.exists())
        }
    }

    @Test fun `no file, an empty file and a non sqlite file are left alone`() {
        assertNull(DatabaseGuard.setAsideIfWipeAhead(context, "g.db", 15))
        val f = context.getDatabasePath("g.db"); f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(0))
        assertNull(DatabaseGuard.setAsideIfWipeAhead(context, "g.db", 15))
        f.writeText("this is not a database, just text that is long enough to not be empty")
        assertNull(DatabaseGuard.setAsideIfWipeAhead(context, "g.db", 15))
        assertTrue(f.exists())
    }

    @Test fun `only the two newest set-aside copies are kept`() {
        for (i in 1..4L) {
            make("g.db", 99, marker = "copy$i")
            DatabaseGuard.setAsideIfWipeAhead(context, "g.db", 15, now = i * 100)
        }
        val kept = DatabaseGuard.setAsideFiles(context.getDatabasePath("g.db").parentFile!!, "g.db")
        assertEquals(listOf("g.db.v99.300.bak", "g.db.v99.400.bak"), kept.map { it.name })
        assertEquals("copy4", readMarker(kept.last()))
    }

    @Test fun `AppDatabase opens over a newer database, keeps a copy, and works afterwards`() = runBlocking {
        val f = make(AppDatabase.FILE_NAME, 99, marker = "from a newer build")
        val db = AppDatabase.get(context)
        db.messageDao().insert(MessageEntity(threadId = 0, address = "+15550100", body = "hi", timestamp = 1, isIncoming = true))
        assertEquals(1, db.messageDao().count())
        db.close()
        val kept = DatabaseGuard.setAsideFiles(f.parentFile!!, AppDatabase.FILE_NAME)
        assertEquals(1, kept.size)
        assertEquals("from a newer build", readMarker(kept.single()))
    }

    @Test fun `AppDatabase opens over a pre-migration database instead of crashing`() = runBlocking {
        val f = make(AppDatabase.FILE_NAME, 4, marker = "old schema")
        val db = AppDatabase.get(context)
        assertEquals(0, db.messageDao().count())
        db.close()
        assertEquals("old schema", readMarker(DatabaseGuard.setAsideFiles(f.parentFile!!, AppDatabase.FILE_NAME).single()))
    }
}
