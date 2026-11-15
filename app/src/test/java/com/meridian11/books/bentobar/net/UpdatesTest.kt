package com.meridian11.books.bentobar.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure version comparison and release metadata parsing for [Updates]. */
class UpdatesTest {

    @Test fun forkPatchVersionsCompareAsNewer() {
        assertEquals(listOf(2, 2, 1, 3), Updates.parts("2.2.1-notch.3"))
        assertTrue(Updates.newer("2.2.1-notch.3", "2.2.1-notch.1"))
        assertTrue(Updates.newer("2.2.1-notch.3", "2.2.1"))
        assertFalse(Updates.newer("2.2.1-notch.3", "2.2.1-notch.3"))
        assertFalse(Updates.newer("2.2.1-notch.1", "2.2.1-notch.3"))
    }

    @Test fun parsesGitHubLatestReleaseAndAcceptsOnlyGithubApkPaths() {
        val json = """
            {
              "tag_name": "v2.2.1-notch.3",
              "html_url": "https://github.com/Nessuno6/bentobar/releases/tag/v2.2.1-notch.3",
              "assets": [
                {"name": "SHA256SUMS", "browser_download_url": "https://github.com/Nessuno6/bentobar/releases/download/v2.2.1-notch.3/SHA256SUMS"},
                {"name": "BentoBar.apk", "browser_download_url": "https://github.com/Nessuno6/bentobar/releases/download/v2.2.1-notch.3/BentoBar.apk"}
              ]
            }
        """.trimIndent()
        val parsed = Updates.parse(json)
        assertNotNull(parsed)
        assertEquals("2.2.1-notch.3", parsed!!.version)
        assertEquals("https://github.com/Nessuno6/bentobar/releases/tag/v2.2.1-notch.3", parsed.page)
        assertEquals("/Nessuno6/bentobar/releases/download/v2.2.1-notch.3/BentoBar.apk", parsed.apkPath)
        assertNotNull(HttpTransport.address(Request(Host.GITHUB_RELEASES, parsed.apkPath!!)))

        val foreign = """
            {
              "tag_name": "v2.2.1-notch.3",
              "assets": [{"name": "BentoBar.apk", "browser_download_url": "https://evil.example/BentoBar.apk"}]
            }
        """.trimIndent()
        assertNull(Updates.parse(foreign)?.apkPath)
    }
}
