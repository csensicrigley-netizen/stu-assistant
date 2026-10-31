package com.stuassistant.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stuassistant.app.liveprobe.LiveUpdateProbe
import com.stuassistant.app.liveprobe.ProbeKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Checks the notifications the OS actually received, not a claimed promotion outcome. */
@RunWith(AndroidJUnit4::class)
class LiveUpdateProbeInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private lateinit var probe: LiveUpdateProbe

    @Before fun setUp() {
        assumeTrue("Notification channels require API 26", Build.VERSION.SDK_INT >= 26)
        context = instrumentation.targetContext
        assertEquals("Only the isolated debug package may be exercised", "com.stuassistant.app.gpuqa", context.packageName)
        manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        assumeTrue("App notifications are disabled by device policy", manager.areNotificationsEnabled())
        probe = LiveUpdateProbe(context)
        probe.finish()
        awaitCondition("A previous probe notification was not removed") { ownNotifications().isEmpty() }
    }

    @After fun tearDown() {
        if (::probe.isInitialized) {
            // Never cancelAll(), delete other channels, or touch account/timetable data.
            probe.finish()
            awaitCondition("Probe cleanup left its notification active") { ownNotifications().isEmpty() }
        }
        // Revoking a permission of the instrumentation target can kill this process.
        // The root QA runner owns installation/removal of this debug-only package.
    }

    @Test fun progressRequestsPromotionWithoutPretendingItWasGranted() {
        assumeTrue("ProgressStyle and promotion diagnostics require API 36", Build.VERSION.SDK_INT >= 36)
        if (Build.VERSION.SDK_INT < 36) return

        probe.start(ProbeKind.PROGRESS)
        val notification = awaitPosted().notification
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING, false))
        assertEquals(Notification.ProgressStyle::class.java.name, notification.extras.getString(Notification.EXTRA_TEMPLATE))
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.isNotBlank() == true)
        assertFalse(notification.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        if (Build.VERSION.SDK_INT >= 37) {
            assertFalse(notification.extras.getBoolean(Notification.EXTRA_COLORIZED, false))
        }
        assertTrue("Progress must expire without a background cleanup service", notification.timeoutAfter in 1L..600_000L)
        assertNull("Custom RemoteViews disqualify a promoted notification", notification.contentView)
        assertNull(notification.bigContentView)
        assertNull(notification.headsUpContentView)

        val snapshot = probe.snapshot()
        assertTrue(snapshot.active)
        assertEquals(ProbeKind.PROGRESS, snapshot.kind)
        assertTrue(snapshot.notificationsEnabled)
        assertTrue(snapshot.channelEnabled)
        // Either true or false is a valid real system outcome. A request and
        // eligible characteristics must never be reported as proof of promotion.
        assertEquals(notification.flags and Notification.FLAG_PROMOTED_ONGOING != 0, snapshot.promoted)
        assertEquals(runCatching { notification.hasPromotableCharacteristics() }.getOrNull(), snapshot.eligible)
        assertEquals(runCatching { manager.canPostPromotedNotifications() }.getOrNull(), snapshot.promotionAllowed)
        assertTrue(probe.diagnosticText().isNotBlank())
    }

    @Test fun advancingProgressUpdatesTheSameSystemNotification() {
        assumeTrue("Recovering ProgressStyle requires API 36", Build.VERSION.SDK_INT >= 36)
        if (Build.VERSION.SDK_INT < 36) return

        probe.start(ProbeKind.PROGRESS)
        val initial = awaitPosted()
        assertEquals(0, recoverProgress(initial.notification))
        val firstKey = initial.key

        for (expected in listOf(20, 40)) {
            probe.advance()
            awaitCondition("The OS did not receive progress $expected") {
                ownNotifications().singleOrNull()?.let { recoverProgress(it.notification) == expected } == true
            }
            val updated = ownNotifications().single()
            assertEquals("An update must not create another notification", firstKey, updated.key)
            assertEquals(LiveUpdateProbe.NOTIFICATION_ID, updated.id)
            assertEquals(LiveUpdateProbe.NOTIFICATION_TAG, updated.tag)
            assertEquals(expected, probe.snapshot().progress)
        }
    }

    @Test fun countdownUsesTheSystemClockAndAdvanceDoesNotExtendIt() {
        val before = System.currentTimeMillis()
        probe.start(ProbeKind.COUNTDOWN)
        val after = System.currentTimeMillis()
        val end = requireNotNull(probe.snapshot().endsAt)
        assertTrue("The probe must start a three-minute countdown", end in (before + 175_000)..(after + 185_000))

        val initial = awaitPosted()
        assertCountdown(initial.notification, end)
        probe.advance()
        assertEquals("Progress action must not reset a running countdown", end, probe.snapshot().endsAt)
        val unchanged = awaitPosted()
        assertEquals(initial.key, unchanged.key)
        assertCountdown(unchanged.notification, end)
    }

    @Test fun notificationEndActionClearsOnlyTheProbeSession() {
        probe.start(ProbeKind.PROGRESS)
        val notification = awaitPosted().notification
        val endAction = notification.actions?.firstOrNull { it.title.toString().contains("结束") }
        assertNotNull("The real notification needs a visible End action", endAction)
        requireNotNull(endAction).actionIntent.send()

        awaitCondition("The End receiver did not clear the probe") {
            ownNotifications().isEmpty() && !probe.snapshot().active
        }
        assertFalse("Finished state must also survive a controller recreation", LiveUpdateProbe(context).snapshot().active)
    }

    @Test fun notificationDismissIntentClearsTheCountdownSession() {
        probe.start(ProbeKind.COUNTDOWN)
        val notification = awaitPosted().notification
        assertNotNull("A dismissible probe must provide a delete receiver", notification.deleteIntent)
        requireNotNull(notification.deleteIntent).send()

        awaitCondition("The dismiss receiver left countdown state active") {
            ownNotifications().isEmpty() && !probe.snapshot().active
        }
        assertFalse(LiveUpdateProbe(context).snapshot().active)
    }

    @Test fun anOldDismissIntentCannotCancelANewerSession() {
        probe.start(ProbeKind.COUNTDOWN)
        val oldDismiss = requireNotNull(awaitPosted().notification.deleteIntent)
        probe.start(ProbeKind.PROGRESS)
        awaitCondition("The replacement progress notification was not delivered") {
            ownNotifications().singleOrNull()?.notification?.extras
                ?.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) == false
        }
        val replacement = awaitPosted()

        // Wait until the stale broadcast has actually finished before checking
        // state; checking immediately after send() can hide a receiver race.
        val completed = CountDownLatch(1)
        try {
            oldDismiss.send(context, 0, null, PendingIntent.OnFinished { _, _, _, _, _ -> completed.countDown() }, null)
            assertTrue("The stale dismiss receiver did not finish", completed.await(5, TimeUnit.SECONDS))
        } catch (_: PendingIntent.CanceledException) {
            // Explicitly invalidating old intents is also a safe implementation.
        }

        val current = probe.snapshot()
        assertTrue("A stale delete intent must not stop the replacement", current.active)
        assertEquals(ProbeKind.PROGRESS, current.kind)
        assertEquals(0, current.progress)
        assertEquals(replacement.key, ownNotifications().single().key)
        assertTrue(LiveUpdateProbe(context).snapshot().active)
    }

    private fun assertCountdown(notification: Notification, end: Long) {
        assertEquals(end, notification.`when`)
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN, false))
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false))
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false))
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue("Countdown must expire at its end, not ten minutes later", notification.timeoutAfter in 1L..181_000L)
        if (Build.VERSION.SDK_INT >= 36) {
            assertTrue(notification.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING, false))
        }
        if (Build.VERSION.SDK_INT >= 37) {
            assertNull("A fixed short-critical label must not replace the live countdown", notification.shortCriticalText)
        }
    }

    private fun recoverProgress(notification: Notification): Int {
        if (Build.VERSION.SDK_INT < 36) throw AssertionError("ProgressStyle requires API 36")
        val style = Notification.Builder.recoverBuilder(context, notification).style
        assertTrue("The delivered notification lost its standard ProgressStyle", style is Notification.ProgressStyle)
        return (style as Notification.ProgressStyle).progress
    }

    private fun ownNotifications(): List<StatusBarNotification> = manager.activeNotifications.filter {
        it.id == LiveUpdateProbe.NOTIFICATION_ID && it.tag == LiveUpdateProbe.NOTIFICATION_TAG &&
            (Build.VERSION.SDK_INT < 26 || it.notification.channelId == LiveUpdateProbe.CHANNEL_ID)
    }

    private fun awaitPosted(): StatusBarNotification {
        var delivered: StatusBarNotification? = null
        awaitCondition("The OS did not receive the probe notification") {
            delivered = ownNotifications().singleOrNull()
            delivered != null
        }
        return requireNotNull(delivered)
    }

    private fun awaitCondition(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(25)
        }
        throw AssertionError(message)
    }
}
