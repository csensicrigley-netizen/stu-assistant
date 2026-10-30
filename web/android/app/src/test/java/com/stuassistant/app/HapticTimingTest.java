package com.stuassistant.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class HapticTimingTest {
    @Test public void fastClickPreservesThirtyMillisecondOnsetSpacing() {
        HapticTiming timing = new HapticTiming();
        timing.played("press", 100);
        assertFalse(timing.throttled("release", 104));
        assertEquals(26, timing.releaseDelay("release", 104));
        assertEquals(0, timing.releaseDelay("release", 130));
    }

    @Test public void heldButtonReleasesImmediately() {
        HapticTiming timing = new HapticTiming();
        timing.played("press", 100);
        assertEquals(0, timing.releaseDelay("release", 600));
    }

    @Test public void repeatedClicksAndSliderEventsDoNotBuildAQueue() {
        HapticTiming timing = new HapticTiming();
        timing.played("release", 130);
        assertTrue(timing.throttled("press", 140));
        assertTrue(timing.throttled("selection", 179));
        assertFalse(timing.throttled("selection", 180));
    }

    @Test public void cancellationResetsThePressPair() {
        HapticTiming timing = new HapticTiming();
        timing.played("press", 100);
        timing.reset();
        assertEquals(0, timing.releaseDelay("release", 110));
        assertFalse(timing.throttled("press", 110));
    }
}
