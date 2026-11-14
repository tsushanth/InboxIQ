package com.inboxiq.app.reactions

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class EmojiCatalogTest {
    private val categories by lazy { EmojiCatalog.load(ApplicationProvider.getApplicationContext<android.content.Context>().resources) }

    @Test fun `parse reads emoji, name and search words`() {
        assertEquals(EmojiCatalog.Entry("😂", "face with tears of joy", "laugh lol"), EmojiCatalog.parse("😂|face with tears of joy|laugh lol"))
        assertEquals(EmojiCatalog.Entry("x", "name", ""), EmojiCatalog.parse("x|name"))
        assertNull(EmojiCatalog.parse("onlyone"))
        assertNull(EmojiCatalog.parse("|name|words"))
    }

    @Test fun `the bundled catalog is a few hundred emoji in named categories with no duplicates`() {
        val all = categories.flatMap { it.entries }
        assertTrue("size ${all.size}", all.size in 250..500)
        assertEquals(7, categories.size)
        assertTrue(categories.all { it.title.isNotBlank() && it.icon.isNotBlank() && it.entries.isNotEmpty() })
        assertEquals(all.size, all.map { ReactionEmoji.canonical(it.emoji) }.toSet().size)
        assertTrue(all.all { it.name.isNotBlank() })
    }

    @Test fun `every quick reaction and every tapback emoji can be found and named`() {
        for (e in ReactionEmoji.QUICK + listOf(ReactionEmoji.THUMBS_DOWN, ReactionEmoji.EXCLAMATION, ReactionEmoji.QUESTION)) {
            val name = EmojiCatalog.nameOf(categories, e)
            assertTrue("no name for $e", name != e && name.isNotBlank())
        }
        assertEquals("thumbs up", EmojiCatalog.nameOf(categories, ReactionEmoji.THUMBS_UP))
        assertEquals("red heart", EmojiCatalog.nameOf(categories, "❤")) // without the variation selector
        assertEquals("🦄🦄", EmojiCatalog.nameOf(categories, "🦄🦄")) // unknown: the emoji itself
    }

    @Test fun `search matches names and search words, every word, case-insensitively`() {
        assertTrue(EmojiCatalog.search(categories, "heart").size > 5)
        assertTrue(EmojiCatalog.search(categories, "LOL").any { it.emoji == "😂" })
        val both = EmojiCatalog.search(categories, "face tears")
        assertTrue(both.any { it.emoji == "😂" } && both.all { "face" in it.name + it.words && "tears" in (it.name + " " + it.words) })
        assertTrue(EmojiCatalog.search(categories, "zzzzqqq").isEmpty())
        assertTrue(EmojiCatalog.search(categories, "   ").isEmpty())
    }

    @Test fun `the grid index covers the same emoji`() {
        val idx = EmojiCatalog.nameIndex(categories)
        assertNotNull(idx[ReactionEmoji.canonical(ReactionEmoji.HEART)])
        assertEquals(categories.flatMap { it.entries }.size, idx.size)
    }
}
