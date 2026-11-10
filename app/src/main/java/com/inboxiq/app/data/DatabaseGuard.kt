package com.inboxiq.app.data

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Keeps the app database from being silently wiped.
 *
 * Room can only upgrade from [OLDEST_MIGRATABLE_VERSION] to the current version, and a database
 * written by a NEWER build (a downgrade, e.g. a sideloaded older build over a newer one) cannot be
 * read at all. [AppDatabase] allows a destructive rebuild for exactly those two cases so the app
 * always opens, but a rebuild drops every table: pins, archive, mute, drafts, scheduled texts and
 * blocked numbers. Before Room gets the chance, this moves such a file aside as
 * `inboxiq.db.v<oldVersion>.<time>.bak` (plus its `-wal`/`-shm`), so nothing is destroyed and the
 * old file can be put back (see docs/RELIABILITY.md). A rename needs no free space, so it cannot
 * fail for lack of room the way a copy could. Message text itself is not at stake: the Android
 * SMS provider is the source of truth and the history import re-reads it.
 *
 * Never touches a database that Room can open normally (same version, or an upgrade it can migrate).
 */
object DatabaseGuard {
    /** First schema version with a real migration path (see the MIGRATION_* list in AppDatabase). */
    const val OLDEST_MIGRATABLE_VERSION = 7

    /** How many set-aside databases are kept; the oldest are deleted beyond this. */
    const val KEEP = 2

    private val SUFFIXES = listOf("", "-wal", "-shm")

    /** Returns the set-aside main file when a wipe was ahead and the database was moved, else null. */
    fun setAsideIfWipeAhead(context: Context, name: String, currentVersion: Int, now: Long = System.currentTimeMillis()): File? {
        val db = context.getDatabasePath(name)
        if (!db.isFile || db.length() == 0L) return null
        val version = existingVersion(db) ?: return null
        val wipeAhead = version > currentVersion || version in 1 until OLDEST_MIGRATABLE_VERSION
        if (!wipeAhead) return null

        val target = File(db.parentFile, "$name.v$version.$now.bak")
        if (!db.renameTo(target)) return null // could not move it: leave it for Room (a rare, already-wipe case)
        for (s in SUFFIXES.drop(1)) {
            val side = File(db.path + s)
            if (side.exists()) side.renameTo(File(target.path + s))
        }
        prune(db.parentFile ?: return target, name)
        return target
    }

    /** The `user_version` of an existing database file, or null if it cannot be read as SQLite. */
    internal fun existingVersion(file: File): Int? = try {
        // A no-op error handler: the default one deletes a database it finds corrupt, and this must only look.
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, DatabaseErrorHandler { }).use { it.version }
    } catch (e: Exception) {
        null
    }

    internal fun setAsideFiles(dir: File, name: String): List<File> {
        val pattern = Regex("^" + Regex.escape(name) + """\.v\d+\.(\d+)\.bak$""")
        return (dir.listFiles() ?: emptyArray())
            .mapNotNull { f -> pattern.find(f.name)?.let { f to it.groupValues[1].toLong() } }
            .sortedBy { it.second }
            .map { it.first }
    }

    private fun prune(dir: File, name: String) {
        val all = setAsideFiles(dir, name)
        for (old in all.dropLast(KEEP)) for (s in SUFFIXES) File(old.path + s).delete()
    }
}
