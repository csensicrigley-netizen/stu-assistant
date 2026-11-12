package com.stuassistant.app.nativeapp

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.PathParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRuntimeSafetyTest {

    @Test
    fun topEdgeShader_usesValidAgslWithoutFloatSuffixes() {
        val shader = TOP_EDGE_SHADER
        // Android AGSL (SkSL 1.0) rejects C/HLSL float suffixes such as 0.5f or 0.5h at runtime.
        val invalidFloatSuffix = Regex("""\b\d+\.\d+[fFhH]\b""")
        val matches = invalidFloatSuffix.findAll(shader).map { it.value }.toList()
        assertTrue("Invalid AGSL float literal suffixes found: $matches", matches.isEmpty())

        for (uniform in listOf("content", "depth", "radius", "fade", "axis", "bounds")) {
            assertTrue("Missing uniform '$uniform' in TOP_EDGE_SHADER", shader.contains(uniform))
        }
        assertTrue("Missing main entry point", shader.contains("half4 main(float2"))
        assertEquals(shader.count { it == '{' }, shader.count { it == '}' })
        assertEquals(shader.count { it == '(' }, shader.count { it == ')' })
    }

    @Test
    fun allNativeIconSvgPaths_parseCleanlyWithoutRuntimeErrors() {
        assertTrue(NativeIcons.svgPaths.size >= 30)
        for ((name, raw) in NativeIcons.svgPaths) {
            val nodes = PathParser().parsePathString(raw).toNodes()
            assertFalse("Icon '$name' produced empty path nodes", nodes.isEmpty())
        }
    }

    @Test
    fun allCampusPalettes_produceFiniteValidColorsAcrossLightAndDark() {
        fun assertValidColor(label: String, c: Color) {
            for (ch in listOf(c.red, c.green, c.blue, c.alpha)) {
                assertTrue("$label channel is non-finite: $ch", ch.isFinite())
                assertTrue("$label channel out of range: $ch", ch in 0f..1f)
            }
        }

        assertEquals(6, NATIVE_PALETTES.size)
        for (spec in NATIVE_PALETTES) {
            assertValidColor("${spec.key}:swatchPaper", spec.swatchPaper)
            assertValidColor("${spec.key}:swatchBrand", spec.swatchBrand)
            assertValidColor("${spec.key}:swatchAccent", spec.swatchAccent)

            val logo = nativeLogoPalette(spec.key)
            assertValidColor("${spec.key}:logoHi", logo.hi)
            assertValidColor("${spec.key}:logoLo", logo.lo)
            assertValidColor("${spec.key}:logoPaper", logo.paper)
            assertValidColor("${spec.key}:logoBead", logo.bead)

            for (dark in listOf(false, true)) {
                val colors = nativeColors(dark, spec.key)
                assertValidColor("${spec.key}:$dark:bg", colors.bg)
                assertValidColor("${spec.key}:$dark:surface", colors.surface)
                assertValidColor("${spec.key}:$dark:ink", colors.ink)
                assertValidColor("${spec.key}:$dark:muted", colors.muted)
                assertValidColor("${spec.key}:$dark:brand", colors.brand)
                assertValidColor("${spec.key}:$dark:danger", colors.danger)
                assertValidColor("${spec.key}:$dark:border", colors.border)
                assertValidColor("${spec.key}:$dark:onBrand", colors.onBrand)

                val sem = nativeSemanticTones(dark, spec.key)
                assertValidColor("${spec.key}:$dark:soft", sem.soft)
                assertValidColor("${spec.key}:$dark:softInk", sem.softInk)
                assertValidColor("${spec.key}:$dark:amber", sem.amber)
                assertValidColor("${spec.key}:$dark:dangerInk", sem.dangerInk)
                assertValidColor("${spec.key}:$dark:leaf", sem.leaf)

                for (hue in listOf(0, 60, 145, 220, 310)) {
                    val swatch = nativeCourseSwatch(hue, dark, spec.key)
                    assertValidColor("${spec.key}:$dark:$hue:mark", swatch.mark)
                    assertValidColor("${spec.key}:$dark:$hue:soft", swatch.soft)
                    assertValidColor("${spec.key}:$dark:$hue:mid", swatch.mid)
                    assertValidColor("${spec.key}:$dark:$hue:ink", swatch.ink)
                }
            }
        }
    }

    @Test
    fun academicCalendar_producesValidPhasesProgressAndMilestonesAcrossYear() {
        val sampleDates = listOf(
            "2026-08-25",
            "2026-09-07",
            "2026-10-01",
            "2026-11-12",
            "2027-01-15",
            "2027-02-01",
            "2027-03-10",
            "2027-07-15",
        )
        for (date in sampleDates) {
            val pos = AcademicCalendar.position(date)
            val term = pos?.term ?: AcademicCalendar.currentOrNext(date)
            assertTrue("Term phases must not be empty for $date", term.phases.isNotEmpty())
            assertTrue("Term totalWeeks must be positive", term.totalWeeks > 0)
            val p = AcademicCalendar.progress(term, AcademicClock.dayStart(date))
            assertTrue("Progress must be finite for $date: $p", p.isFinite())
            assertTrue("Progress out of [0, 1] for $date: $p", p in 0f..1f)

            for (week in 1..term.totalWeeks) {
                val monday = AcademicCalendar.monday(term, week)
                val slash = AcademicClock.slash(monday)
                assertTrue("Slash date invalid for $monday: $slash", slash.length in 3..5 && slash.contains('/'))
            }
            val milestones = AcademicCalendar.milestones(date, 6)
            for (m in milestones) {
                assertTrue("Milestone label blank on $date", m.label.isNotBlank())
            }
        }
    }

    @Test
    fun medicalAndImportRoutes_belongToMeDataSourceTab() {
        assertEquals("me", NativePageRoute("medical", "notices").tab)
        assertEquals("me", NativePageRoute("import", "notices").tab)
        assertEquals(4, NativePageRoute("medical", "notices").index)
        assertEquals(4, NativePageRoute("import", "notices").index)
    }

    @Test
    fun campusChargingDevices_excludeUtilitiesCenterAndAreAllVerified() {
        assertEquals(14, campusDevices.size)
        assertTrue(campusDevices.none { it.location == "水电中心" || it.code == "4GR00008477" })
        assertTrue(campusDevices.all { it.verified })
    }
}
