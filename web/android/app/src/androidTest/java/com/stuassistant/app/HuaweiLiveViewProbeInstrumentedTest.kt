package com.stuassistant.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stuassistant.app.liveprobe.HuaweiLiveViewProbe
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

/** Verifies the delivered Android payload; it cannot certify Huawei capsule admission. */
@RunWith(AndroidJUnit4::class)
class HuaweiLiveViewProbeInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private lateinit var probe: HuaweiLiveViewProbe

    @Before fun setUp() {
        assumeTrue("Notification channels require API 26", Build.VERSION.SDK_INT >= 26)
        context = instrumentation.targetContext
        assertEquals("Tests must use the isolated debug application", "com.stuassistant.app.gpuqa", context.packageName)
        manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        assumeTrue("Device policy disables app notifications", manager.areNotificationsEnabled())
        probe = HuaweiLiveViewProbe(context)
        probe.finish()
        awaitCondition("Previous Huawei probe was not cleaned up") { ownNotifications().isEmpty() }
    }

    @After fun tearDown() {
        if (::probe.isInitialized) {
            // Do not cancelAll(), delete channels, or modify the user's other data.
            probe.finish()
            awaitCondition("Huawei probe cleanup left an active notification") { ownNotifications().isEmpty() }
        }
    }

    @Test fun startDeliversATimerPayloadWithAThreeMinuteSystemClock() {
        val before = System.currentTimeMillis()
        probe.start()
        val after = System.currentTimeMillis()
        val status = awaitOperation(0)
        val notification = status.notification
        val end = requireNotNull(probe.snapshot().endsAt)
        assertTrue("Timer must last three minutes", end in (before + 175_000)..(after + 185_000))
        assertEquals("TIMER", notification.extras.getString("notification.live.event"))
        assertEquals(1, notification.extras.getInt("notification.live.type"))
        assertClock(notification, end)
        val capsule = notification.extras.getBundle("notification.live.capsule")
        assertNotNull("Huawei capsule data must survive OS delivery", capsule)
        assertEquals(1, requireNotNull(capsule).getInt("notification.live.capsuleStatus"))
        assertEquals(2, capsule.getInt("notification.live.capsuleType"))
        assertTrue(capsule.get("notification.live.capsuleIcon") is Icon)
        assertTrue(capsule.get("notification.live.capsuleBgColor") is Int)
        assertTrue(capsule.getString("notification.live.capsuleContent")?.isNotBlank() == true)
        assertEquals(end, capsule.getLong("notification.live.capsuleTime"))
        assertTrue(capsule.getBoolean("notification.live.capsuleCountdown", false))
        assertFalse(capsule.getBoolean("notification.live.capsulePause", true))
        val feature = requireNotNull(notification.extras.getBundle("notification.live.feature"))
        assertEquals(1, feature.getInt("notification.live.feature.chronometerPosition"))
        assertTrue(notification.extras.get("notification.live.keepTime") is Long)
        assertEquals(0L, notification.extras.getLong("notification.live.keepTime"))
        assertFalse("This Huawei experiment must not request Android Live Updates", notification.extras.containsKey("android.requestPromotedOngoing"))
        assertNull(notification.contentView)
        assertNull(notification.bigContentView)
        assertNull(notification.headsUpContentView)
        assertTrue(probe.snapshot().active)
        assertNull(status.tag)
    }

    @Test fun manualRefreshUpdatesTheSameNotificationWithoutRestartingTheClock() {
        probe.start()
        val initial = awaitOperation(0)
        val originalEnd = requireNotNull(probe.snapshot().endsAt)
        probe.refresh()
        val updated = awaitOperation(1)

        assertEquals(initial.key, updated.key)
        assertEquals(HuaweiLiveViewProbe.NOTIFICATION_ID, updated.id)
        assertNull(updated.tag)
        assertEquals("Manual refresh must not extend the timer", originalEnd, probe.snapshot().endsAt)
        assertEquals("TIMER", updated.notification.extras.getString("notification.live.event"))
        assertClock(updated.notification, originalEnd)
    }

    @Test fun finishEndsAndRemovesOnlyTheProbeNotification() {
        probe.start()
        awaitOperation(0)
        probe.finish()
        awaitCondition("Finish did not clear the Huawei session") {
            ownNotifications().isEmpty() && !probe.snapshot().active
        }
        assertEquals(2, probe.snapshot().operation)
        assertFalse(HuaweiLiveViewProbe(context).snapshot().active)
    }

    @Test fun dismissReceiverClearsPersistedTimerState() {
        probe.start()
        val deleteIntent = awaitOperation(0).notification.deleteIntent
        assertNotNull("Dismiss must reach the probe cleanup receiver", deleteIntent)
        sendAndWait(requireNotNull(deleteIntent))
        awaitCondition("Dismiss left a stale timer session") {
            ownNotifications().isEmpty() && !probe.snapshot().active
        }
        assertFalse(HuaweiLiveViewProbe(context).snapshot().active)
    }

    @Test fun staleDismissCannotStopANewTimerSession() {
        probe.start()
        val previous = awaitOperation(0)
        val oldDeleteIntent = requireNotNull(previous.notification.deleteIntent)
        probe.start()
        // Each session must carry a distinct delete PendingIntent, even though
        // both sessions deliberately update the same notification ID.
        awaitCondition("Replacement session was not delivered") {
            ownNotifications().singleOrNull()?.notification?.deleteIntent?.let { it != oldDeleteIntent } == true
        }
        val replacement = ownNotifications().single()
        try {
            sendAndWait(oldDeleteIntent)
        } catch (_: PendingIntent.CanceledException) {
            // Invalidating old PendingIntents also prevents stale cancellation.
        }

        assertTrue("Old delete receiver stopped a newer session", probe.snapshot().active)
        assertEquals(replacement.key, ownNotifications().single().key)
        assertEquals(0, ownNotifications().single().notification.extras.getInt("notification.live.operation"))
        assertTrue(HuaweiLiveViewProbe(context).snapshot().active)
    }

    private fun assertClock(notification: Notification, end: Long) {
        assertEquals(end, notification.`when`)
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false))
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false))
        assertTrue("Notification must have a bounded system timeout", notification.timeoutAfter in 1L..181_000L)
    }

    private fun sendAndWait(intent: PendingIntent) {
        val completed = CountDownLatch(1)
        intent.send(context, 0, null, PendingIntent.OnFinished { _, _, _, _, _ -> completed.countDown() }, null)
        assertTrue("Probe receiver did not finish", completed.await(5, TimeUnit.SECONDS))
    }

    private fun ownNotifications(): List<StatusBarNotification> = manager.activeNotifications.filter {
        it.id == HuaweiLiveViewProbe.NOTIFICATION_ID && it.tag == null &&
            it.notification.channelId == HuaweiLiveViewProbe.CHANNEL_ID
    }

    private fun awaitOperation(operation: Int): StatusBarNotification {
        var delivered: StatusBarNotification? = null
        awaitCondition("System did not receive Huawei operation $operation") {
            delivered = ownNotifications().singleOrNull()?.takeIf {
                it.notification.extras.containsKey("notification.live.operation") &&
                    it.notification.extras.getInt("notification.live.operation") == operation
            }
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
