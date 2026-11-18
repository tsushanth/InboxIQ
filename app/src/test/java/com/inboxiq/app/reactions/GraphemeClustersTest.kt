package com.inboxiq.app.reactions

import org.junit.Assert.assertEquals
import org.junit.Test

class GraphemeClustersTest {
    @Test fun `ascii splits per character`() = assertEquals(listOf("a", "b", "c"), GraphemeClusters.split("abc"))

    @Test fun `surrogate pairs, variation selectors and skin tones stay together`() {
        assertEquals(listOf("😀", "❤️", "👍🏽", "a"), GraphemeClusters.split("😀❤️👍🏽a"))
    }

    @Test fun `ZWJ sequences stay together`() {
        val family = "👨‍👩‍👧"
        assertEquals(listOf(family, family), GraphemeClusters.split(family + family))
    }

    @Test fun `flags pair up regional indicators`() {
        val us = "🇺🇸"; val fr = "🇫🇷"
        assertEquals(listOf(us, fr, us), GraphemeClusters.split(us + fr + us))
    }

    @Test fun `keycaps, combining marks and CRLF`() {
        assertEquals(listOf("1️⃣", "é", "\r\n"), GraphemeClusters.split("1️⃣é\r\n"))
    }

    @Test fun `empty`() = assertEquals(emptyList<String>(), GraphemeClusters.split(""))
}
