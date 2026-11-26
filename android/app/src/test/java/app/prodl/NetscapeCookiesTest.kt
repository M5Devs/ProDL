package app.prodl

import app.prodl.core.CookieFormatException
import app.prodl.core.NetscapeCookies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NetscapeCookiesTest {

    private val sample = """
        # Netscape HTTP Cookie File
        # comment line

        .youtube.com	TRUE	/	TRUE	1893456000	SID	secret-value
        .youtube.com	TRUE	/	FALSE	1893456000	PREF	f6=400
    """.trimIndent()

    @Test
    fun `parses tab separated rows and skips comments`() {
        val rows = NetscapeCookies.parse(sample)
        assertEquals(2, rows.size)
        assertEquals(".youtube.com", rows[0].domain)
        assertTrue(rows[0].includeSubdomains)
        assertTrue(rows[0].secure)
        assertEquals("SID", rows[0].name)
        assertEquals("secret-value", rows[0].value)
        assertEquals(false, rows[1].secure)
    }

    @Test
    fun `serialize then parse round trips`() {
        val rows = NetscapeCookies.parse(sample)
        val reparsed = NetscapeCookies.parse(NetscapeCookies.serialize(rows))
        assertEquals(rows, reparsed)
        assertTrue(NetscapeCookies.serialize(rows).startsWith("# Netscape HTTP Cookie File"))
    }

    @Test
    fun `rejects rows with the wrong field count`() {
        val exception = assertThrows(CookieFormatException::class.java) {
            NetscapeCookies.parse(".youtube.com\tTRUE\t/\tSID")
        }
        assertTrue(exception.message!!.contains("line 1"))
    }

    @Test
    fun `rejects empty input`() {
        assertThrows(CookieFormatException::class.java) { NetscapeCookies.parse("# only comments\n") }
    }

    @Test
    fun `accepts httpOnly prefixed rows`() {
        val rows = NetscapeCookies.parse("#HttpOnly_.youtube.com\tTRUE\t/\tTRUE\t0\tHSID\tv")
        assertEquals("HSID", rows.single().name)
    }

    @Test
    fun `turns WebView cookie headers into rows`() {
        val rows = NetscapeCookies.fromCookieHeader(
            "https://www.youtube.com",
            "VISITOR_INFO1_LIVE=abc; PREF=f6=400; malformed",
            1893456000L,
        )
        assertEquals(2, rows.size)
        assertEquals(".youtube.com", rows[0].domain)
        assertEquals("VISITOR_INFO1_LIVE", rows[0].name)
        assertEquals("f6=400", rows[1].value)
    }

    @Test
    fun `maps hosts onto their registrable cookie domain`() {
        assertEquals(".youtube.com", NetscapeCookies.cookieDomainFor("m.youtube.com"))
        assertEquals(".google.com", NetscapeCookies.cookieDomainFor("accounts.google.com"))
        assertEquals(".example.org", NetscapeCookies.cookieDomainFor("example.org"))
    }

    @Test
    fun `dedupe keeps the last value for a name`() {
        val rows = NetscapeCookies.parse(sample) + NetscapeCookies.parse(
            ".youtube.com\tTRUE\t/\tTRUE\t1893456000\tSID\tnewer"
        )
        val deduped = NetscapeCookies.dedupe(rows)
        assertEquals(2, deduped.size)
        assertEquals("newer", deduped.first { it.name == "SID" }.value)
    }
}
