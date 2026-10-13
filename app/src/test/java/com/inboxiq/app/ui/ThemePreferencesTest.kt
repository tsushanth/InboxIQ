package com.inboxiq.app.ui

import androidx.test.core.app.ApplicationProvider
import com.inboxiq.app.inbox.MuteDuration
import com.inboxiq.app.ui.theme.Density
import com.inboxiq.app.ui.theme.ThemeMode
import com.inboxiq.app.ui.theme.ThemePreferences
import com.inboxiq.app.ui.theme.ThemeSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ThemePreferencesTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun `defaults follow the system`() {
        ctx.getSharedPreferences("inboxiq_prefs", 0).edit().clear().commit()
        assertEquals(ThemeSettings(), ThemePreferences.get(ctx))
    }

    @Test fun `settings round trip`() {
        val s = ThemeSettings(ThemeMode.TRUE_BLACK, dynamicColor = true, density = Density.COMPACT, fontScale = 1.3f)
        ThemePreferences.set(ctx, s)
        assertEquals(s, ThemePreferences.get(ctx))
    }

    @Test fun `an unknown stored font scale falls back to default instead of breaking layout`() {
        ctx.getSharedPreferences("inboxiq_prefs", 0).edit().putFloat("theme_font_scale", 7f).commit()
        assertEquals(1f, ThemePreferences.get(ctx).fontScale)
    }

    @Test fun `mute durations`() {
        assertEquals(1_000L + 3_600_000, MuteDuration.HOUR.until(1_000))
        assertEquals(Long.MAX_VALUE, MuteDuration.FOREVER.until(1_000))
        assertEquals(1_000L + 7 * 24 * 3_600_000, MuteDuration.WEEK.until(1_000))
    }

    @Test fun `timestamps show time today and a date otherwise`() {
        val now = Fixtures.NOW
        val locale = java.util.Locale.US
        val today = formatTimestamp(now - 60_000, now, locale)
        val old = formatTimestamp(now - 3L * 24 * 3_600_000, now, locale)
        assert(today.contains(":")) { today }
        assert(!old.contains(":")) { old }
    }
}
