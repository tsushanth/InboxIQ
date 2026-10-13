package com.inboxiq.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.inboxiq.app.data.MessageLabel
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelStyleContrastTest {
    private fun ratio(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble(); val l2 = b.luminance().toDouble()
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    @Test fun `every label meets 4_5 to 1 in light and dark`() {
        MessageLabel.entries.forEach { label ->
            val l = LabelPalette.light(label); val d = LabelPalette.dark(label)
            assertTrue("$label light ${ratio(l.container, l.content)}", ratio(l.container, l.content) >= 4.5)
            assertTrue("$label dark ${ratio(d.container, d.content)}", ratio(d.container, d.content) >= 4.5)
        }
    }

    @Test fun `every label has a distinct name and a distinct icon`() {
        val names = MessageLabel.entries.map { LabelPalette.displayName(it) }
        assertTrue(names.toSet().size == names.size)
        val icons = MessageLabel.entries.map { LabelPalette.icon(it).name }
        assertTrue(icons.toSet().size == icons.size)
    }
}
