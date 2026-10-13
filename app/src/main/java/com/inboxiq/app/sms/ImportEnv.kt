package com.inboxiq.app.sms

import android.content.ContentResolver
import android.content.Context
import com.inboxiq.app.data.AppDatabase

/** Everything a page import needs, bundled so tests can swap the provider, DB and clock-free pieces. */
class ImportEnv(
    val context: Context,
    val resolver: ContentResolver,
    val db: AppDatabase,
    val tombstones: ImportTombstones,
    private val normalizer: (String) -> String = { PhoneNumberNormalizer.normalize(context, it) },
    /** Bulk imports read parts for a whole page at once; the small recent-sync path reads per message. */
    val batchParts: Boolean = true,
) {
    private val cache = HashMap<String, String>()

    /** Normalization hits libphonenumber; 10k rows come from a few hundred distinct numbers. */
    fun normalize(raw: String): String {
        cache[raw]?.let { return it }
        if (cache.size >= MAX_CACHE) cache.clear()
        return normalizer(raw).also { cache[raw] = it }
    }

    companion object {
        private const val MAX_CACHE = 4096

        fun from(context: Context, batchParts: Boolean = true): ImportEnv = ImportEnv(
            context = context.applicationContext,
            resolver = context.applicationContext.contentResolver,
            db = AppDatabase.get(context),
            tombstones = ImportTombstones(context),
            batchParts = batchParts,
        )
    }
}

/** What one page of a provider read did. [read] == 0 means the range is exhausted. */
data class PageResult(val read: Int, val minId: Long, val maxId: Long, val inserted: Int) {
    companion object { val EMPTY = PageResult(0, -1, -1, 0) }
}

/** The provider handed back nothing at all (null cursor): it is down or restricted, not empty. */
class ProviderUnavailableException(message: String) : RuntimeException(message)
