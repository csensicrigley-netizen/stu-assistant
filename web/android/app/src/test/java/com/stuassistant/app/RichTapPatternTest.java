package com.stuassistant.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class RichTapPatternTest {
    @Test public void chosenProfileUsesTransientsWithIndependentSharpness() {
        int[] press = new RichTapPattern("press", 100).packet();
        int[] release = new RichTapPattern("release", 100).packet();
        assertEquals(56, press.length);
        assertEquals(3, press[0]);
        assertEquals(0x1001, press[1]);
        assertEquals(100, press[3]);
        assertEquals(52, press[4]);
        assertEquals(100, release[3]);
        assertEquals(100, release[4]);
        for (int i = 5; i < press.length; i++) assertEquals(0, press[i]);
    }

    @Test public void sliderScalesBothPhasesWithoutChangingSharpness() {
        for (int strength : new int[] {0, 1, 25, 50, 100}) {
            RichTapPattern press = new RichTapPattern("press", strength);
            RichTapPattern release = new RichTapPattern("release", strength);
            assertEquals(strength, press.intensity);
            assertEquals(strength, release.intensity);
            assertEquals(52, press.frequency);
            assertEquals(100, release.frequency);
            assertTrue(press.json().contains("\"Intensity\":" + strength));
            assertFalse(press.json().contains("Curve"));
        }
    }

    @Test public void malformedGainCannotReachTheVendorProtocol() {
        assertEquals(0, new RichTapPattern("press", -1).intensity);
        assertEquals(100, new RichTapPattern("release", 101).intensity);
    }
}
