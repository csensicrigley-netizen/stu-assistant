package com.stuassistant.app.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTodayExportTest {

    @Test
    fun deepLinkWhitelist_acceptsOnlyExactTodayStuImportCodes() {
        assertTrue(isAllowedTodayDeepLink("today://sheet/stu-import?code=2345ABCDJKLM"))
        val rejected = listOf(
            "",
            "today://sheet/stu-import?code=2345ABCDJKL",
            "today://sheet/stu-import?code=2345ABCDJKLMO",
            "today://sheet/stu-import?code=0000OOOO1111",
            "today://sheet/stu-import?code=2345abcdjklm",
            "today://sheet/stu-import?code=2345ABCDJKLM&extra=1",
            "today://sheet/other?code=2345ABCDJKLM",
            "https://today.oyishi.asia/",
            "intent://sheet/stu-import?code=2345ABCDJKLM",
            "javascript:alert(1)",
        )
        for (url in rejected) {
            assertFalse("Expected rejected deep link: $url", isAllowedTodayDeepLink(url))
        }
    }

    @Test
    fun formatAndCampusHelpers_produceReadableLabels() {
        assertEquals("2345 ABCD JKLM", formatTodayExportCode(" 2345abcdjklm "))
        assertEquals("汕大本部", todayCampusLabel("main"))
        assertEquals("汕大医学院", todayCampusLabel("med"))
        assertEquals("手动导入", todayCampusLabel("manual"))
        assertEquals("手动导入", todayCampusLabel(""))
    }
}
