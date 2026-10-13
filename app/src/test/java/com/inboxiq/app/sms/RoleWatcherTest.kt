package com.inboxiq.app.sms

import org.junit.Assert.assertEquals
import org.junit.Test

class RoleWatcherTest {
    private var role = false
    private var fired = 0
    private val watcher = RoleWatcher({ role }, { fired++ })

    @Test fun `not default never triggers`() {
        watcher.check(true); watcher.check(false)
        assertEquals(0, fired)
    }

    @Test fun `already default at first sight triggers once, polling does not repeat`() {
        role = true
        watcher.check(true); watcher.check(false); watcher.check(false)
        assertEquals(1, fired)
    }

    @Test fun `switching to InboxIQ while the app is running triggers on the next poll`() {
        watcher.check(true)
        role = true            // user changed the default in Android Settings; we were never told
        watcher.check(false)   // poll
        assertEquals(1, fired)
        watcher.check(false)
        assertEquals(1, fired)
    }

    @Test fun `returning to the app forces a check even when the role did not change`() {
        role = true
        watcher.check(true)
        watcher.check(true)    // ON_START again after Settings
        assertEquals(2, fired)
    }

    @Test fun `losing and regaining the role triggers again`() {
        role = true; watcher.check(false)
        role = false; watcher.check(false)
        role = true; watcher.check(false)
        assertEquals(2, fired)
    }
}
