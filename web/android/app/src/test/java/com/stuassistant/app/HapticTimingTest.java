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
}
