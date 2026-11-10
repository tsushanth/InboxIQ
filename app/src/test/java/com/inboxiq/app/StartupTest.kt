package com.inboxiq.app

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.inboxiq.app.inbox.MessageChannel
import com.inboxiq.app.sms.MessageNotifier
import com.inboxiq.app.worker.ClassifyMessagesWorker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the app does by itself at start (InboxIqApp.onCreate), without waiting for a text or an import. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class StartupTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("inboxiq_prefs", Context.MODE_PRIVATE)

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        prefs.edit().clear().commit()
    }

    @After fun tearDown() { prefs.edit().clear().commit() }

    @Test fun `every notification channel exists after start, with the intended importance`() {
        MessageNotifier.ensureAllChannels(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        val byId = nm.notificationChannels.associateBy { it.id }
        for (c in MessageChannel.entries) {
            assertTrue("missing channel ${c.id}", byId.containsKey(c.id))
        }
        assertTrue(byId.containsKey("auto_heal"))
        // Transactions: sound, but no heads-up pop-up (default importance).
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, byId.getValue(MessageChannel.TRANSACTIONS.id).importance)
        // Idempotent.
        MessageNotifier.ensureAllChannels(context)
        assertEquals(byId.size, nm.notificationChannels.size)
    }

    @Test fun `an old pipeline version queues exactly one re-sort at start, and it completes the upgrade`() {
        prefs.edit().putInt("classifier_pipeline_version", 2).commit()
        assertTrue(ClassifyMessagesWorker.refreshPending(context))

        ClassifyMessagesWorker.enqueueIfRefreshPending(context)
        ClassifyMessagesWorker.enqueueIfRefreshPending(context) // a second start does not stack another pass

        fun infos() = WorkManager.getInstance(context).getWorkInfosForUniqueWork(ClassifyMessagesWorker.WORK_NAME_REFRESH).get()
        assertEquals(1, infos().size)
        // The pass runs on a background thread; wait for it.
        val deadline = System.currentTimeMillis() + 30_000
        while (infos().single().state != WorkInfo.State.SUCCEEDED && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(WorkInfo.State.SUCCEEDED, infos().single().state)
        // The pass recorded the new version, so later starts queue nothing.
        assertFalse(ClassifyMessagesWorker.refreshPending(context))
        assertEquals(ClassifyMessagesWorker.PIPELINE_VERSION, prefs.getInt("classifier_pipeline_version", 0))
    }

    @Test fun `nothing is queued at start when the pipeline version is current`() {
        prefs.edit().putInt("classifier_pipeline_version", ClassifyMessagesWorker.PIPELINE_VERSION).commit()
        ClassifyMessagesWorker.enqueueIfRefreshPending(context)
        assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork(ClassifyMessagesWorker.WORK_NAME_REFRESH).get().isEmpty())
    }
}
