package com.stuassistant.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class HapticTimingTest {
    @Test public void fastClickPreserves110MillisecondOnsetSpacing() {
        HapticTiming timing = new HapticTiming();
        timing.played("press", 100);
        assertFalse(timing.throttled("release", 104));
        assertEquals(106, timing.releaseDelay("release", 104));
        assertEquals(1, timing.releaseDelay("release", 209));
        assertEquals(0, timing.releaseDelay("release", 210));
    }

    @Test public void heldButtonReleasesImmediately() {
        HapticTiming timing = new HapticTiming();
        timing.played("press", 100);
        assertEquals(0, timing.releaseDelay("release", 600));
    }

    @Test public void repeatedClicksAndSliderEventsDoNotBuildAQueue() {
        HapticTiming timing = new HapticTiming();
        timing.played("release", 210);
        assertTrue(timing.throttled("press", 220));
        assertTrue(timing.throttled("selection", 259));
        assertFalse(timing.throttled("selection", 260));
    }

    @Test public void cancellationResetsThePressPair() {
        HapticTiming timing = new HapticTiming();
        timing.played("press", 100);
        timing.reset();
        assertEquals(0, timing.releaseDelay("release", 110));
        assertFalse(timing.throttled("press", 110));
    }

    @Test public void navigationSelectionHasNoSecondPulseDelay() {
        HapticTiming timing = new HapticTiming();
        timing.played("selection", 100);
        assertEquals(0, timing.releaseDelay("selection", 101));
        assertTrue(timing.throttled("selection", 120));
        assertFalse(timing.throttled("selection", 150));
        // A later ordinary button still uses the mechanical pair.
        timing.played("press", 200);
        assertEquals(100, timing.releaseDelay("release", 210));
    }

    @Test public void semanticEventsNeverUseTheLegacyReleaseQueue() {
        for (String previous : new String[] { "press", "release", "navigate_press", "utility_press", "destructive_press",
            "selection", "toggle", "confirm", "reject", "preview", "navigation" }) {
            HapticTiming timing = new HapticTiming();
            timing.played(previous, 100);
            for (String kind : new String[] { "selection", "toggle", "confirm", "reject", "preview", "navigation" }) {
                assertEquals(previous + " to " + kind, 0, timing.releaseDelay(kind, 101));
            }
        }
    }

    @Test public void droppedSemanticEventsDoNotMoveTheRateLimitWindow() {
        String[] kinds = { "selection", "toggle", "confirm", "reject", "preview", "navigation" };
        int[] intervals = { 50, 60, 90, 160, 60, 50 };
        for (int i = 0; i < kinds.length; i++) {
            HapticTiming timing = new HapticTiming();
            timing.played(kinds[i], 1000);
            for (int elapsed = 1; elapsed < intervals[i]; elapsed++) {
                assertTrue(kinds[i] + " should drop inside its window", timing.throttled(kinds[i], 1000 + elapsed));
                assertEquals(0, timing.releaseDelay(kinds[i], 1000 + elapsed));
            }
            // Queries for dropped events must not enqueue work or postpone this boundary.
            assertFalse(kinds[i], timing.throttled(kinds[i], 1000 + intervals[i]));
            timing.played(kinds[i], 1000 + intervals[i]);
            assertTrue(timing.throttled(kinds[i], 1001 + intervals[i]));
        }
    }

    @Test public void rejectionInterruptsLegacyButRepeatedRejectionWaits160Milliseconds() {
        for (String previous : new String[] { "press", "release", "navigate_press", "utility_press", "destructive_press" }) {
            HapticTiming timing = new HapticTiming();
            timing.played(previous, 100);
            assertFalse(timing.throttled("reject", 101));
            assertEquals(0, timing.releaseDelay("reject", 101));
            timing.played("reject", 101);
            // The internally scheduled second onset must not admit another reject event.
            assertTrue(timing.throttled("reject", 161));
            assertTrue(timing.throttled("reject", 260));
            assertFalse(timing.throttled("reject", 261));
        }
    }

    @Test public void nonErrorSemanticEventsStillRespectTheLastActualLegacyOnset() {
        String[] kinds = { "selection", "toggle", "confirm", "preview", "navigation" };
        int[] intervals = { 50, 60, 90, 60, 50 };
        for (String previous : new String[] { "press", "release" }) {
            for (int i = 0; i < kinds.length; i++) {
                HapticTiming timing = new HapticTiming();
                timing.played(previous, 100);
                assertTrue(timing.throttled(kinds[i], 100 + intervals[i] - 1));
                assertFalse(timing.throttled(kinds[i], 100 + intervals[i]));
                assertEquals(0, timing.releaseDelay(kinds[i], 101));
            }
        }
    }

    @Test public void unknownKindsAreDroppedWithoutAffectingTheNextKnownEvent() {
        HapticTiming timing = new HapticTiming();
        timing.played("selection", 100);
        for (String kind : new String[] { null, "", "unknown", "CONFIRM" }) {
            assertTrue(timing.throttled(kind, 1000));
            assertEquals(0, timing.releaseDelay(kind, 1000));
        }
        assertTrue(timing.throttled("selection", 149));
        assertFalse(timing.throttled("selection", 150));
    }

    @Test public void rejectProtectsItsSecondPulseWithoutQueuingFollowUpEvents() {
        HapticTiming timing = new HapticTiming();
        timing.played("reject", 100);
        for (String kind : new String[] { "selection", "toggle", "confirm", "reject", "preview", "navigation",
            "press", "release", "navigate_press", "utility_press", "destructive_press" }) {
            for (long now : new long[] { 101, 150, 160, 259 }) assertTrue(kind, timing.throttled(kind, now));
            assertFalse(kind, timing.throttled(kind, 260));
            assertEquals(0, timing.releaseDelay(kind, 150));
        }
        // Zero gain, lifecycle cancellation and close call reset directly, outside admission.
        timing.reset();
        assertFalse(timing.throttled("selection", 150));
        assertFalse(timing.throttled("press", 150));
        assertEquals(0, timing.releaseDelay("release", 150));
    }

    @Test public void typedReleasesOnlyCompleteTheirMatchingFamily() {
        String[] presses = { "navigate_press", "utility_press", "destructive_press" };
        String[] releases = { "navigate_release", "utility_release", "destructive_release" };
        int[] intervals = { 80, 110, 150 };
        for (int family = 0; family < presses.length; family++) {
            HapticTiming timing = new HapticTiming();
            timing.played(presses[family], 100);
            assertFalse(timing.throttled(releases[family], 101));
            assertEquals(intervals[family] - 1, timing.releaseDelay(releases[family], 101));
            assertEquals(1, timing.releaseDelay(releases[family], 100 + intervals[family] - 1));
            assertEquals(0, timing.releaseDelay(releases[family], 100 + intervals[family]));
            assertEquals(0, timing.releaseDelay(releases[family], 1000));
            for (int other = 0; other < releases.length; other++) if (family != other) {
                assertTrue("Cross-family release must be dropped, not scheduled", timing.throttled(releases[other], 1000));
                assertEquals(0, timing.releaseDelay(releases[other], 101));
            }
        }
    }

    @Test public void orphanedOrCancelledTypedReleasesAreDroppedWithoutInventingAPress() {
        for (String release : new String[] { "navigate_release", "utility_release", "destructive_release" }) {
            HapticTiming timing = new HapticTiming();
            assertTrue(timing.throttled(release, 1000));
            assertEquals(0, timing.releaseDelay(release, 1000));
            timing.played("press", 100);
            assertTrue(timing.throttled(release, 1000));
            assertEquals(0, timing.releaseDelay(release, 101));
            timing.played("navigation", 200);
            assertTrue(timing.throttled(release, 1000));
            timing.reset();
            assertTrue(timing.throttled(release, 1000));
        }
        // The original loose legacy release behavior is intentionally unchanged.
        assertFalse(new HapticTiming().throttled("release", 1000));
    }
}
