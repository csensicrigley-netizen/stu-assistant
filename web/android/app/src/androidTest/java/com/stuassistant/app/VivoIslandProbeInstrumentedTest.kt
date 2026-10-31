package com.stuassistant.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stuassistant.app.liveprobe.LiveUpdateProbe
import com.stuassistant.app.liveprobe.VivoIslandProbe
import com.stuassistant.app.liveprobe.VivoProbeMode
import com.stuassistant.app.liveprobe.VivoSuperXPayload
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Checks what Android received, without treating an emulator as an OriginOS island renderer. */
@RunWith(AndroidJUnit4::class)
class VivoIslandProbeInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private lateinit var probe: VivoIslandProbe

    @Before fun setUp() {
        assumeTrue("Channel-based notifications require API 26", Build.VERSION.SDK_INT >= 26)
        context = instrumentation.targetContext
        assertEquals("Only isolated debug data may be exercised", "com.stuassistant.app.gpuqa", context.packageName)
        manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        assumeTrue("The device policy disables app notifications", manager.areNotificationsEnabled())
        probe = VivoIslandProbe(context)
        probe.finish()
        awaitCondition("Old probe was not cleaned up") { ownNotifications().isEmpty() }
    }

    @After fun tearDown() {
        if (::probe.isInitialized) {
            probe.finish()
            awaitCondition("Probe remained active after cleanup") { ownNotifications().isEmpty() }
        }
    }

    @Test fun sixModesHaveDistinctPayloadsAndHybridIsExplicit() {
        assumeTrue("Modern modes require API36", Build.VERSION.SDK_INT >= 36)
        VivoProbeMode.entries.forEach { mode ->
            probe.start(mode)
            val posted = awaitMode(mode)
            val n = posted.notification
            assertEquals(if (mode == VivoProbeMode.SUPERX || mode == VivoProbeMode.SUPERX_NAVIGATION) VivoIslandProbe.SUPERX_TAG else VivoIslandProbe.NOTIFICATION_TAG, posted.tag)
            assertEquals(VivoIslandProbe.CHANNEL_ID, n.channelId)
            assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertTrue(n.timeoutAfter in 1L..VivoIslandProbe.TIMEOUT_MS)
            assertNull(n.contentView)
            assertNull(n.bigContentView)
            when (mode) {
                VivoProbeMode.CONTROL -> {
                    assertFalse(n.extras.getBoolean(LiveUpdateProbe.REQUEST_PROMOTED_EXTRA, false))
                    assertFalse(n.extras.containsKey(VivoSuperXPayload.OPERATION))
                    assertEquals(0, n.extras.getInt(Notification.EXTRA_PROGRESS))
                }
                VivoProbeMode.STANDARD_PLAIN, VivoProbeMode.STANDARD_COLORIZED -> {
                    assertTrue(n.extras.getBoolean(LiveUpdateProbe.REQUEST_PROMOTED_EXTRA, false))
                    assertEquals(mode == VivoProbeMode.STANDARD_COLORIZED, n.extras.getBoolean(Notification.EXTRA_COLORIZED, false))
                    assertEquals(Notification.ProgressStyle::class.java.name, n.extras.getString(Notification.EXTRA_TEMPLATE))
                    assertFalse(n.extras.containsKey(VivoSuperXPayload.OPERATION))
                }
                VivoProbeMode.SUPERX, VivoProbeMode.SUPERX_NAVIGATION -> {
                    assertFalse(n.extras.containsKey(LiveUpdateProbe.REQUEST_PROMOTED_EXTRA))
                    assertFalse(n.extras.getBoolean(LiveUpdateProbe.REQUEST_PROMOTED_EXTRA, false))
                    assertEquals(0, n.extras.getInt(VivoSuperXPayload.OPERATION, -1))
                    assertEquals(if (mode == VivoProbeMode.SUPERX_NAVIGATION) "NAVIGATION" else "TRAIN", n.extras.getString("notification.superx.scene"))
                    if (mode == VivoProbeMode.SUPERX) assertTrue(VivoSuperXPayload.extrasObserved(n.extras))
                    assertEquals(65809, n.extras.getInt("notification.superx.displays", -1))
                    assertTrue(n.extras.getBundle(VivoSuperXPayload.ISLAND)?.getBoolean("island.superx.forceShow", false) == true)
                    assertEquals(4, n.extras.getBundle(VivoSuperXPayload.ISLAND)?.getInt("island.superx.rightTemplate"))
                }
                VivoProbeMode.SUPERX_HYBRID -> {
                    assertTrue(n.extras.getBoolean(LiveUpdateProbe.REQUEST_PROMOTED_EXTRA, false))
                    assertFalse(n.extras.getBoolean(Notification.EXTRA_COLORIZED, false))
                    assertEquals(Notification.ProgressStyle::class.java.name, n.extras.getString(Notification.EXTRA_TEMPLATE))
                    assertEquals(0, n.extras.getInt(VivoSuperXPayload.OPERATION, -1))
                    assertEquals("NAVIGATION", n.extras.getString("notification.superx.scene"))
                    assertEquals(4, n.extras.getBundle(VivoSuperXPayload.ISLAND)?.getInt("island.superx.template"))
                }
            }
            probe.finish()
            awaitCondition("Mode cleanup did not complete") { ownNotifications().isEmpty() }
        }
        assertEquals(2, VivoSuperXPayload.endExtras().getInt(VivoSuperXPayload.OPERATION, -1))
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(VivoIslandProbe.CHANNEL_ID)?.importance)
    }

    @Test fun manualProgressUpdateKeepsKeyAndDoesNotExtendExpiration() {
        probe.start(VivoProbeMode.CONTROL)
        val initial = awaitMode(VivoProbeMode.CONTROL)
        val started = probe.snapshot().startedAt
        val expires = probe.snapshot().expiresAt
        probe.advance()
        awaitCondition("The OS did not receive the manual update") {
            ownNotifications().singleOrNull()?.notification?.extras?.getInt(Notification.EXTRA_PROGRESS) == 20
        }
        assertEquals(initial.key, ownNotifications().single().key)
        assertEquals(started, probe.snapshot().startedAt)
        assertEquals(expires, probe.snapshot().expiresAt)
        assertEquals(20, probe.snapshot().progress)
        assertTrue(probe.historyText().contains("firstObservedAt="))
    }

    @Test fun manualObservationAndTimestampSurviveControllerRecreation() {
        probe.start(VivoProbeMode.SUPERX)
        awaitMode(VivoProbeMode.SUPERX)
        val before = System.currentTimeMillis()
        probe.recordObservation("只有通知栏")
        val text = VivoIslandProbe(context).historyText()
        assertTrue(text.contains("SUPERX=只有通知栏 @ "))
        assertTrue(text.contains("postTime="))
        assertTrue(text.contains("superx=true"))
        val value = requireNotNull(VivoIslandProbe(context).snapshot().observations[VivoProbeMode.SUPERX])
        val time = value.substringAfter(" @ ").toLong()
        assertTrue(time in before..System.currentTimeMillis())
        probe.start(VivoProbeMode.SUPERX)
        assertEquals("A new trial cannot inherit an old visual result", "未记录", probe.snapshot().observations[VivoProbeMode.SUPERX])
    }

    @Test fun staleDismissCannotStopReplacementAndCurrentEndActionCan() {
        probe.start(VivoProbeMode.CONTROL)
        val stale = requireNotNull(awaitMode(VivoProbeMode.CONTROL).notification.deleteIntent)
        probe.start(VivoProbeMode.SUPERX)
        val replacement = awaitMode(VivoProbeMode.SUPERX)
        val latch = CountDownLatch(1)
        try {
            stale.send(context, 0, null, PendingIntent.OnFinished { _, _, _, _, _ -> latch.countDown() }, null)
            assertTrue(latch.await(5, TimeUnit.SECONDS))
        } catch (_: PendingIntent.CanceledException) { }
        assertTrue(probe.snapshot().active)
        assertEquals(VivoProbeMode.SUPERX, probe.snapshot().mode)
        assertEquals(replacement.key, ownNotifications().single().key)
        val finish = requireNotNull(replacement.notification.actions?.firstOrNull { it.title.toString().contains("结束") })
        finish.actionIntent.send()
        awaitCondition("End action did not clear the current session") { !probe.snapshot().active }
        probe.advance()
        assertFalse("Updating a stopped session must not resurrect it", probe.snapshot().active)
    }

    @Test fun rawInventoryKeepsAReceivedNotificationWhoseSessionMarkerWasRemoved() {
        probe.start(VivoProbeMode.SUPERX_NAVIGATION)
        val initial = awaitMode(VivoProbeMode.SUPERX_NAVIGATION)
        val stripped = initial.notification.clone().apply {
            extras = Bundle(extras).apply { remove(VivoIslandProbe.EXTRA_SESSION) }
        }
        assertFalse("The stripping fixture must remove the session before posting", stripped.extras.containsKey(VivoIslandProbe.EXTRA_SESSION))
        manager.notify(VivoIslandProbe.SUPERX_TAG, VivoIslandProbe.NOTIFICATION_ID, stripped)
        awaitCondition("Raw inventory failed to expose a session-stripped notification") {
            val state = probe.snapshot()
            state.current == null && !state.active && state.rawActive.any {
                it.id == VivoIslandProbe.NOTIFICATION_ID && it.tag == VivoIslandProbe.SUPERX_TAG &&
                    !it.notification.extras.containsKey(VivoIslandProbe.EXTRA_SESSION)
            }
        }
        val state = probe.snapshot()
        assertFalse(state.active)
        assertNull(state.current)
        assertTrue(state.rawActive.all { it.packageName == context.packageName && it.user == android.os.Process.myUserHandle() })
        assertTrue(probe.historyText().contains("sessionPresent=false"))
        assertTrue(probe.historyText().contains("mode=SUPERX_NAVIGATION"))
        assertTrue(probe.historyText().contains("scene=NAVIGATION"))
        assertTrue(probe.historyText().contains("requestedImportance=4"))
        probe.advance()
        assertFalse("Missing session proof must not authorize an update", probe.snapshot().active)
        probe.finish()
        awaitCondition("Finish must clean the owned raw notification without requiring its session marker") { ownNotifications().isEmpty() }
    }

    @Test fun finishClearsRetaggedRawProbeButPreservesADifferentOwnedNotificationId() {
        val rewrittenTag = "vivo_probe_rom_rewritten_tag"
        val otherId = VivoIslandProbe.NOTIFICATION_ID + 100
        val otherTag = "vivo_probe_unrelated_owned_test"
        try {
            probe.start(VivoProbeMode.SUPERX_NAVIGATION)
            val initial = awaitMode(VivoProbeMode.SUPERX_NAVIGATION)
            val stripped = initial.notification.clone().apply {
                extras = Bundle(extras).apply { remove(VivoIslandProbe.EXTRA_SESSION) }
            }
            assertFalse("The retagging fixture must remove the session before posting", stripped.extras.containsKey(VivoIslandProbe.EXTRA_SESSION))
            manager.cancel(VivoIslandProbe.SUPERX_TAG, VivoIslandProbe.NOTIFICATION_ID)
            manager.notify(rewrittenTag, VivoIslandProbe.NOTIFICATION_ID, stripped)
            manager.notify(otherTag, otherId, Notification.Builder(context, VivoIslandProbe.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_live_probe).setContentTitle("Different owned ID control")
                .setContentText("This test notification must survive probe.finish")
                .setTimeoutAfter(30_000L).build())
            awaitCondition("Raw inventory did not observe both owned notifications") {
                val state = probe.snapshot()
                state.current == null && !state.active &&
                    state.rawActive.any { it.id == VivoIslandProbe.NOTIFICATION_ID && it.tag == rewrittenTag } &&
                    state.rawActive.any { it.id == otherId && it.tag == otherTag }
            }
            probe.finish()
            awaitCondition("Explicit finish did not clear the retagged reserved probe ID") {
                probe.snapshot().rawActive.none { it.id == VivoIslandProbe.NOTIFICATION_ID }
            }
            assertTrue("The probe must not cancel a different owned ID", manager.activeNotifications.any { it.id == otherId && it.tag == otherTag })
        } finally {
            // This fixture alone owns this different-ID notification; the engine
            // must not remove it as part of ordinary probe cleanup.
            manager.cancel(otherTag, otherId)
            manager.cancel(rewrittenTag, VivoIslandProbe.NOTIFICATION_ID)
        }
    }

    private fun ownNotifications(): List<StatusBarNotification> = manager.activeNotifications.filter {
        it.id == VivoIslandProbe.NOTIFICATION_ID && it.tag in setOf(VivoIslandProbe.NOTIFICATION_TAG, VivoIslandProbe.SUPERX_TAG)
    }

    private fun awaitMode(mode: VivoProbeMode): StatusBarNotification {
        var current: StatusBarNotification? = null
        awaitCondition("The OS did not receive $mode") {
            current = ownNotifications().singleOrNull()?.takeIf { it.notification.extras.getString(VivoIslandProbe.EXTRA_MODE) == mode.name }
            current != null
        }
        return requireNotNull(current)
    }

    private fun awaitCondition(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(25)
        }
        throw AssertionError(message)
    }
}
