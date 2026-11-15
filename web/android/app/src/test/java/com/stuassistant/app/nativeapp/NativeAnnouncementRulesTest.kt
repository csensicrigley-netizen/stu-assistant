package com.stuassistant.app.nativeapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

class NativeAnnouncementRulesTest {
    @Test fun effectsAreBundledOptionalAndDeduplicated() {
        assertEquals(emptyList<String>(), NativeAnnouncementRules.effects(JSONObject()))
        assertEquals(listOf("star-struck", "party-popper"), NativeAnnouncementRules.effects(JSONObject()
            .put("effects", org.json.JSONArray(listOf("star-struck", "../private", "party-popper", "star-struck", 7)))))
        assertEquals(emptyList<String>(), NativeAnnouncementRules.effects(JSONObject().put("effects", "https://example.test/remote.json")))
    }
    private val server = "https://stu.example.test"
    private fun row(id: String = "star", priority: Int = 0): JSONObject = JSONObject()
        .put("id", id).put("revision", 1).put("title", "给项目点一颗 Star")
        .put("body", "感谢支持校园助手。")
        .put("display", "once").put("priority", priority)
    private fun feed(vararg values: Any): JSONObject = JSONObject().put("announcements", JSONArray(values.toList()))
    private fun item() = NativeAnnouncementRules.parse(feed(row()), server).single()

    @Test fun malformedFeedAndRowsDoNotHideTheRemainingValidMessage() {
        assertTrue(NativeAnnouncementRules.parse(JSONObject(), server).isEmpty())
        assertTrue(NativeAnnouncementRules.parse(JSONObject().put("announcements", "bad"), server).isEmpty())
        val invalid = listOf(
            "not an object", JSONObject.NULL,
            row().put("id", " "), row().put("title", ""), row().put("title", "t".repeat(161)),
            row().put("body", 12), row().put("body", "b".repeat(4001)),
            row().put("revision", 0), row().put("revision", 1.5), row().put("revision", "2"),
            row().put("display", "hourly"), row().put("display", JSONObject.NULL),
        )
        val result = NativeAnnouncementRules.parse(feed(*(invalid + row("valid")).toTypedArray()), server)
        assertEquals(listOf("valid"), result.map { it.id })
        assertEquals("开发者来信", result.single().eyebrow)
        assertEquals("了解更多", result.single().actionLabel)
    }

    @Test fun unsafeOptionalDestinationsAreDroppedWithoutDroppingTheMessage() {
        val message = NativeAnnouncementRules.parse(feed(row()
            .put("action_url", "javascript:alert(1)")
            .put("image_url", "file:///private/photo.png")), server).single()
        assertEquals("star", message.id)
        assertNull(message.actionUrl)
        assertNull(message.imageUrl)
    }

    @Test fun actionUrlsRejectActiveContentCredentialsAndAmbiguousAuthorities() {
        val valid = "https://github.com/csensicrigley-netizen/stu-assistant?tab=readme#start"
        assertEquals(valid, NativeAnnouncementRules.httpsUrl(valid))
        listOf(
            "http://github.com/repo", "javascript:alert(1)", "intent://github.com",
            "file:///tmp/image.png", "data:text/html,hello", "//github.com/repo",
            "https://user:password@github.com/repo", "https://github.com@evil.example/repo",
            "https://github.com\\@evil.example", "https://github.com/a b",
            "https://github.com/\nrepo", "https://github.com:0/repo", "https://github.com:65536/repo",
            "https:///missing-host", "https://github.com/" + "x".repeat(2048),
        ).forEach { assertNull("Rejected URL: $it", NativeAnnouncementRules.httpsUrl(it)) }
    }

    @Test fun onlySafeLocalImageNamesResolveAgainstTheCurrentServer() {
        assertEquals("$server/v1/announcements/assets/welcome-2.webp",
            NativeAnnouncementRules.imageUrl("/v1/announcements/assets/welcome-2.webp", "$server/"))
        assertEquals("https://cdn.example.test/poster.png", NativeAnnouncementRules.imageUrl("https://cdn.example.test/poster.png", server))
        listOf(
            "/v1/announcements/assets/../private.png", "/v1/announcements/assets/%2e%2e",
            "/v1/announcements/assets/sub/pic.png", "/v1/announcements/assets/.secret",
            "/v1/announcements/assets/poster.png?token=secret", "//evil.example/poster.png",
            "/other/poster.png", "/v1/announcements/assets/a\\b.png",
        ).forEach { assertNull("Rejected image: $it", NativeAnnouncementRules.imageUrl(it, server)) }
        assertNull(NativeAnnouncementRules.imageUrl("/v1/announcements/assets/pic.png", "javascript:bad"))
    }

    @Test fun priorityIsStableAndRepeatedIdsCannotProduceRepeatedPopups() {
        val rows = feed(row("low", -10), row("first", 20), row("second", 20), row("first", 100), row("medium", 1))
        val result = NativeAnnouncementRules.parse(rows, server)
        assertEquals(listOf("first", "second", "medium", "low"), result.map { it.id })
        assertEquals(20, result.first().priority)
    }

    @Test fun receiptsSeparateServersAccountsIdsAndMessageRevisions() {
        val message = item()
        val base = NativeAnnouncementRules.receiptKey(server, "alice", message)
        assertEquals(base, NativeAnnouncementRules.receiptKey(server, "alice", message))
        val keys = setOf(base,
            NativeAnnouncementRules.receiptKey("https://other.example.test", "alice", message),
            NativeAnnouncementRules.receiptKey(server, "bob", message),
            NativeAnnouncementRules.receiptKey(server, "alice", message.copy(id = "other")),
            NativeAnnouncementRules.receiptKey(server, "alice", message.copy(revision = 2)))
        assertEquals(5, keys.size)
        assertTrue(keys.all { it.matches(Regex("[0-9a-f]{64}")) })
    }

    @Test fun displayRulesUseShanghaiMidnightRegardlessOfTheDeviceTimezone() {
        val beforeMidnight = Instant.parse("2026-11-15T15:59:59Z").toEpochMilli()
        val midnight = Instant.parse("2026-11-15T16:00:00Z").toEpochMilli()
        val previousZone = TimeZone.getDefault()
        try {
            listOf("UTC", "America/Los_Angeles", "Asia/Tokyo").forEach { zone ->
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertTrue(NativeAnnouncementRules.eligible("once", 0, midnight))
                assertFalse(NativeAnnouncementRules.eligible("once", beforeMidnight, midnight))
                assertTrue(NativeAnnouncementRules.eligible("daily", 0, midnight))
                assertFalse(NativeAnnouncementRules.eligible("daily", beforeMidnight - 1000, beforeMidnight))
                assertTrue(NativeAnnouncementRules.eligible("daily", beforeMidnight, midnight))
                assertFalse(NativeAnnouncementRules.eligible("daily", midnight, midnight + 1000))
                assertTrue(NativeAnnouncementRules.eligible("always", midnight, midnight))
                assertFalse(NativeAnnouncementRules.eligible("unknown", 0, midnight))
            }
        } finally { TimeZone.setDefault(previousZone) }
    }

    @Test fun explicitGithubAppTargetRequiresTheExactTrustedHost() {
        assertEquals("com.github.android", NativeAnnouncementRules.githubAppTarget("https://github.com/csensicrigley-netizen/stu-assistant"))
        assertEquals("com.github.android", NativeAnnouncementRules.githubAppTarget("https://GITHUB.COM/repo"))
        listOf("https://github.com.evil.example/repo", "https://evil.github.com/repo", "https://example.test/?url=github.com",
            "https://github.com@evil.example/repo", "http://github.com/repo", "intent://github.com/repo")
            .forEach { assertNull(it, NativeAnnouncementRules.githubAppTarget(it)) }
    }
}
