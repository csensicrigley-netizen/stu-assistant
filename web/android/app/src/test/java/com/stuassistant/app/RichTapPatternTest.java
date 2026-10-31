package com.stuassistant.app;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    @Test public void confirmIsOneTransientAndRejectIsTwoWithZeroFilledProtocolTails() {
        int[] confirm = new RichTapPattern("confirm", 100).packet();
        assertEquals(56, confirm.length);
        assertEquals(3, confirm[0]);
        assertTransient(confirm, 0, 0, 46, 50);
        int[] reject = new RichTapPattern("reject", 100).packet();
        assertEquals(111, reject.length);
        assertEquals(3, reject[0]);
        assertTransient(reject, 0, 0, 22, 50);
        assertTransient(reject, 1, 60, 19, 50);
    }

    @Test public void vendorJsonAndPacketsAgreeOnEverySemanticOnsetGainAndSharpness() {
        for (String kind : new String[] { "selection", "toggle", "confirm", "reject", "preview" }) {
            for (int strength : new int[] { 0, 1, 25, 50, 100 }) {
                RichTapPattern pattern = new RichTapPattern(kind, strength);
                int[] packet = pattern.packet();
                String json = pattern.json();
                assertTrue(json.contains("\"Metadata\":{\"Version\":1}"));
                assertFalse(json.contains("Curve"));
                assertFalse(json.contains("Duration"));
                List<int[]> events = jsonEvents(json);
                assertEquals("reject".equals(kind) ? 2 : 1, events.size());
                assertEquals(1 + 55 * events.size(), packet.length);
                for (int event = 0; event < events.size(); event++) {
                    int offset = 1 + 55 * event;
                    int[] fields = events.get(event);
                    assertEquals(event == 0 ? 0 : 60, fields[0]);
                    assertEquals(packet[offset + 1], fields[0]);
                    assertEquals(packet[offset + 2], fields[1]);
                    assertEquals(50, fields[2]);
                    assertEquals(packet[offset + 3], fields[2]);
                    assertTrue(fields[1] >= 0 && fields[1] <= 100);
                    assertEquals(strength == 0, fields[1] == 0);
                }
            }
        }
    }

    @Test public void unknownKindsCannotCreateAVendorEvent() {
        for (String kind : new String[] { null, "", "unknown", "selection " }) {
            try {
                new RichTapPattern(kind, 100);
                fail("Unknown kind must not become a vendor event");
            } catch (IllegalArgumentException expected) { }
        }
    }

    private static void assertTransient(int[] packet, int event, int onset, int intensity, int frequency) {
        int offset = 1 + 55 * event;
        assertEquals(0x1001, packet[offset]);
        assertEquals(onset, packet[offset + 1]);
        assertEquals(intensity, packet[offset + 2]);
        assertEquals(frequency, packet[offset + 3]);
        for (int i = offset + 4; i < offset + 55; i++) assertEquals(0, packet[i]);
    }

    // Extract the vendor event fields without relying on Android's stubbed org.json in JVM tests.
    private static List<int[]> jsonEvents(String json) {
        Matcher event = Pattern.compile("\\\"Event\\\"\\s*:\\s*\\{(.*?)\\\"Parameters\\\"\\s*:\\s*\\{(.*?)\\}\\s*\\}").matcher(json);
        List<int[]> events = new ArrayList<>();
        while (event.find()) {
            assertTrue(event.group(1).replaceAll("\\s", "").contains("\"Type\":\"transient\""));
            events.add(new int[] { integerField(event.group(1), "RelativeTime"),
                integerField(event.group(2), "Intensity"), integerField(event.group(2), "Frequency") });
        }
        return events;
    }

    private static int integerField(String object, String name) {
        Matcher field = Pattern.compile("\\\"" + Pattern.quote(name) + "\\\"\\s*:\\s*(\\d+)").matcher(object);
        assertTrue("Missing vendor field: " + name, field.find());
        int value = Integer.parseInt(field.group(1));
        assertFalse("Duplicate vendor field: " + name, field.find());
        return value;
    }
}
