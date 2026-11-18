package com.stuassistant.app.nativeapp;

import static org.junit.Assert.*;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Checks the installed native entry and its real merged assets, replacing the old template test. */
@RunWith(AndroidJUnit4.class)
public class NativePackagingInstrumentedTest {
    private static final String NATIVE_ACTIVITY = "com.stuassistant.app.NativeActivity";
    private static final String[] LAUNCHER_ALIASES = {
        "com.stuassistant.app.LauncherSystem",
        "com.stuassistant.app.LauncherLight",
        "com.stuassistant.app.LauncherDark"
    };

    @Test public void launcherAndAssetsUseTheNativeFrontend() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("com.stuassistant.app.gpuqa", app.getPackageName());
        assertNativeLauncher(app);
        assertTrue(Arrays.asList(app.getAssets().list("")).contains("native-config.json"));
        try (InputStream input = app.getAssets().open("native-config.json")) {
            JSONObject config = new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("https://stu.oyishi.asia", config.getString("server"));
            assertEquals("Legacy sessions must retain their original server scope",
                "https://stu.oyishi.asia", config.getString("legacyServer"));
            URI service = URI.create(config.getString("server"));
            assertEquals("https", service.getScheme());
            assertNotNull(service.getHost());
            assertNull(service.getUserInfo());
            assertNull(service.getQuery());
            assertNull(service.getFragment());
        }
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

    @Test public void eachAppearanceModeKeepsOneNativeLauncherAlias() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("com.stuassistant.app.gpuqa", app.getPackageName());
        PackageManager pm = app.getPackageManager();
        String originalAlias = assertNativeLauncher(app);
        ComponentName[] components = new ComponentName[LAUNCHER_ALIASES.length];
        int[] originalStates = new int[LAUNCHER_ALIASES.length];
        for (int i = 0; i < LAUNCHER_ALIASES.length; i++) {
            components[i] = new ComponentName(app.getPackageName(), LAUNCHER_ALIASES[i]);
            originalStates[i] = pm.getComponentEnabledSetting(components[i]);
        }

        try {
            String[] modes = {"system", "light", "dark"};
            for (int i = 0; i < modes.length; i++) {
                LauncherThemeSync.INSTANCE.syncLauncherAliasOnStop(app, modes[i]);
                assertEquals(LAUNCHER_ALIASES[i], assertNativeLauncher(app));
            }
            LauncherThemeSync.INSTANCE.syncLauncherAliasOnStop(app, "system");
            assertEquals(LAUNCHER_ALIASES[0], assertNativeLauncher(app));
        } finally {
            // Restore the original live entry first, preserving DEFAULT overrides too.
            // No app preferences or appearance settings are changed by this fixture.
            int originalIndex = Arrays.asList(LAUNCHER_ALIASES).indexOf(originalAlias);
            pm.setComponentEnabledSetting(components[originalIndex], originalStates[originalIndex],
                PackageManager.DONT_KILL_APP);
            for (int i = 0; i < components.length; i++) {
                if (i != originalIndex) {
                    pm.setComponentEnabledSetting(components[i], originalStates[i],
                        PackageManager.DONT_KILL_APP);
                }
            }
        }
        assertEquals(originalAlias, assertNativeLauncher(app));
    }

    private static String assertNativeLauncher(Context app) {
        PackageManager pm = app.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(app.getPackageName());
        List<ResolveInfo> entries = pm.queryIntentActivities(launcher, 0);
        assertEquals("Exactly one launcher alias must be enabled", 1, entries.size());
        ResolveInfo entry = entries.get(0);
        assertTrue("Unexpected launcher: " + entry.activityInfo.name,
            Arrays.asList(LAUNCHER_ALIASES).contains(entry.activityInfo.name));
        assertEquals(app.getPackageName(), entry.activityInfo.packageName);
        assertEquals(NATIVE_ACTIVITY, entry.activityInfo.targetActivity);
        assertTrue("Launcher alias must be exported", entry.activityInfo.exported);

        Intent launch = pm.getLaunchIntentForPackage(app.getPackageName());
        assertNotNull(launch);
        assertNotNull(launch.getComponent());
        assertEquals(new ComponentName(app.getPackageName(), entry.activityInfo.name),
            launch.getComponent());
        ResolveInfo resolved = pm.resolveActivity(launch, 0);
        assertNotNull("Launch intent must resolve to an enabled alias", resolved);
        assertEquals(entry.activityInfo.name, resolved.activityInfo.name);
        assertEquals(NATIVE_ACTIVITY, resolved.activityInfo.targetActivity);
        return entry.activityInfo.name;
    }
}
