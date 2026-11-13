package com.example.myasr

import com.example.myasr.context.provisioning.domain.DictUpdateState
import com.example.myasr.context.provisioning.domain.DictionaryStatusInfo
import com.example.myasr.context.provisioning.domain.DictionaryUpdateTracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class DictionaryUpdateTrackerTest {

    @Test
    fun testParseAtomFeedUpdatedMillis() {
        val sampleAtom = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom" xml:lang="en-US">
              <id>tag:github.com,2008:/utuhiro78/mozcdic-ut-jawiki/commits/main</id>
              <title>Recent Commits to mozcdic-ut-jawiki:main</title>
              <updated>2026-11-03T19:29:10Z</updated>
              <entry>
                <updated>2026-11-03T19:29:10Z</updated>
              </entry>
            </feed>
        """.trimIndent()

        val millis = DictionaryUpdateTracker.parseAtomFeedUpdatedMillis(sampleAtom)
        assertTrue("Expected positive epoch millis, got $millis", millis > 0L)
        val formattedUtc = DictionaryUpdateTracker.formatDateMillis(millis, timeZone = TimeZone.getTimeZone("UTC"))
        assertEquals("2026/11/03", formattedUtc)
    }

    @Test
    fun testParseGistUpdatedMillis() {
        val sampleGistJson = """
            {
              "url": "https://api.github.com/gists/39665c6e7ba6e63d6fe86c4c4b1bbd42",
              "id": "39665c6e7ba6e63d6fe86c4c4b1bbd42",
              "created_at": "2026-10-20T10:00:00Z",
              "updated_at": "2026-10-26T22:18:50Z"
            }
        """.trimIndent()

        val millis = DictionaryUpdateTracker.parseGistUpdatedMillis(sampleGistJson)
        assertTrue("Expected positive epoch millis, got $millis", millis > 0L)
        val formattedUtc = DictionaryUpdateTracker.formatDateMillis(millis, timeZone = TimeZone.getTimeZone("UTC"))
        assertEquals("2026/10/26", formattedUtc)
    }

    @Test
    fun testEvaluateUpdateStateTransitions() {
        val tOct15 = DictionaryUpdateTracker.parseIso8601ToMillis("2026-10-15T00:00:00Z")
        val tNov03 = DictionaryUpdateTracker.parseIso8601ToMillis("2026-11-03T00:00:00Z")
        val tNov08 = DictionaryUpdateTracker.parseIso8601ToMillis("2026-11-08T00:00:00Z")

        // 1. Not installed
        assertEquals(
            DictUpdateState.NOT_INSTALLED,
            DictionaryUpdateTracker.evaluateUpdateState(isInstalled = false, localUpdatedAtMillis = 0L, remoteUpdatedAtMillis = tNov03)
        )

        // 2. Installed, remote unknown (offline)
        assertEquals(
            DictUpdateState.INSTALLED_REMOTE_UNKNOWN,
            DictionaryUpdateTracker.evaluateUpdateState(isInstalled = true, localUpdatedAtMillis = tOct15, remoteUpdatedAtMillis = 0L)
        )

        // 3. Installed, remote is newer -> UPDATE_AVAILABLE
        assertEquals(
            DictUpdateState.UPDATE_AVAILABLE,
            DictionaryUpdateTracker.evaluateUpdateState(isInstalled = true, localUpdatedAtMillis = tOct15, remoteUpdatedAtMillis = tNov03)
        )

        // 4. Installed, local is newer or equal -> UP_TO_DATE
        assertEquals(
            DictUpdateState.UP_TO_DATE,
            DictionaryUpdateTracker.evaluateUpdateState(isInstalled = true, localUpdatedAtMillis = tNov08, remoteUpdatedAtMillis = tNov03)
        )
        assertEquals(
            DictUpdateState.UP_TO_DATE,
            DictionaryUpdateTracker.evaluateUpdateState(isInstalled = true, localUpdatedAtMillis = tNov03, remoteUpdatedAtMillis = tNov03)
        )
    }

    @Test
    fun testFormatComparisonLine() {
        val tOct15 = DictionaryUpdateTracker.parseIso8601ToMillis("2026-10-15T12:00:00Z")
        val tNov03 = DictionaryUpdateTracker.parseIso8601ToMillis("2026-11-03T12:00:00Z")

        val outdatedInfo = DictionaryStatusInfo(
            sourceId = "sudachidict",
            displayName = "SudachiDict",
            isInstalled = true,
            wordCount = 1280000,
            localUpdatedAtMillis = tOct15,
            remoteUpdatedAtMillis = tNov03,
            state = DictUpdateState.UPDATE_AVAILABLE
        )
        assertEquals(
            "端末更新: 2026/10/15  ｜  ネット最新: 2026/11/03 (要更新)",
            outdatedInfo.formatComparisonLine()
        )

        val notInstalledInfo = DictionaryStatusInfo(
            sourceId = "jawiki",
            displayName = "Wikipedia",
            isInstalled = false,
            wordCount = 0,
            localUpdatedAtMillis = 0L,
            remoteUpdatedAtMillis = tNov03,
            state = DictUpdateState.NOT_INSTALLED
        )
        assertEquals(
            "端末更新: 未導入  ｜  ネット最新: 2026/11/03",
            notInstalledInfo.formatComparisonLine()
        )
    }

    @Test
    fun testAllSevenSourcesHaveRemoteEndpoints() {
        val sources = DictionaryUpdateTracker.getAllSourceIds()
        assertEquals(7, sources.size)
        for (sourceId in sources) {
            val endpoint = DictionaryUpdateTracker.getRemoteCheckEndpoint(sourceId)
            assertNotNull("Endpoint for $sourceId must not be null", endpoint)
            assertTrue("Endpoint for $sourceId must be HTTPS", endpoint!!.startsWith("https://"))
        }
    }
}
