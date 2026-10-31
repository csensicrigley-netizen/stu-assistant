package com.stuassistant.app.nativeapp;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayDeque;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Checks the installed native entry and its real merged assets, replacing the old template test. */
@RunWith(AndroidJUnit4.class)
public class NativePackagingInstrumentedTest {
    @Test public void launcherAndAssetsUseTheNativeFrontend() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("com.stuassistant.app.gpuqa", app.getPackageName());
        Intent launch = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        assertNotNull(launch);
        assertEquals("com.stuassistant.app.NativeActivity", launch.getComponent().getClassName());
        assertTrue(Arrays.asList(app.getAssets().list("")).contains("native-config.json"));
        assertFalse(Arrays.asList(app.getAssets().list("")).contains("public"));
        ArrayDeque<String> directories = new ArrayDeque<>();
        directories.add("");
        while (!directories.isEmpty()) {
            String directory = directories.removeFirst();
            for (String name : app.getAssets().list(directory)) {
                String path = directory.isEmpty() ? name : directory + "/" + name;
                assertFalse("Web script included: " + path, name.endsWith(".js"));
                if (app.getAssets().list(path).length > 0) directories.add(path);
            }
        }
    }
}
