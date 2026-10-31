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
        for (String previous : new String[] { "press", "release", "selection", "toggle", "confirm", "reject", "preview" }) {
            HapticTiming timing = new HapticTiming();
            timing.played(previous, 100);
            for (String kind : new String[] { "selection", "toggle", "confirm", "reject", "preview" }) {
                assertEquals(previous + " to " + kind, 0, timing.releaseDelay(kind, 101));
            }
        }
    }

    @Test public void droppedSemanticEventsDoNotMoveTheRateLimitWindow() {
        String[] kinds = { "selection", "toggle", "confirm", "reject", "preview" };
        int[] intervals = { 50, 60, 90, 160, 60 };
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
        for (String previous : new String[] { "press", "release" }) {
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
        String[] kinds = { "selection", "toggle", "confirm", "preview" };
        int[] intervals = { 50, 60, 90, 60 };
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
        for (String kind : new String[] { "selection", "toggle", "confirm", "reject", "preview", "press", "release" }) {
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
}
