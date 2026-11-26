package app.prodl

import app.prodl.core.InputParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputParserTest {

    @Test
    fun `keywords are searches`() {
        assertEquals(InputParser.Kind.SEARCH, InputParser.classify("lofi hip hop"))
        assertEquals(InputParser.Kind.SEARCH, InputParser.classify("  "))
        assertFalse(InputParser.isUrl("lofi hip hop"))
        assertFalse(InputParser.isUrl("https://example.com two words"))
    }

    @Test
    fun `watch links open the video screen`() {
        assertEquals(
            InputParser.Kind.VIDEO,
            InputParser.classify("https://www.youtube.com/watch?v=jNQXAC9IVRw"),
        )
        assertEquals(InputParser.Kind.VIDEO, InputParser.classify("youtu.be/jNQXAC9IVRw"))
        assertEquals(
            InputParser.Kind.VIDEO,
            InputParser.classify("https://www.youtube.com/shorts/abc123"),
        )
    }

    @Test
    fun `channel and playlist links open their lists`() {
        assertEquals(InputParser.Kind.CHANNEL, InputParser.classify("https://www.youtube.com/@veritasium"))
        assertEquals(
            InputParser.Kind.CHANNEL,
            InputParser.classify("https://www.youtube.com/channel/UCabc"),
        )
        assertEquals(
            InputParser.Kind.PLAYLIST,
            InputParser.classify("https://www.youtube.com/playlist?list=PL123"),
        )
        assertEquals(
            InputParser.Kind.PLAYLIST,
            InputParser.classify("https://www.youtube.com/watch?v=x&list=PL123"),
        )
    }

    @Test
    fun `other links stay generic urls`() {
        assertEquals(InputParser.Kind.URL, InputParser.classify("https://vimeo.com/12345"))
        assertTrue(InputParser.isUrl("https://vimeo.com/12345"))
    }

    @Test
    fun `normalize adds a scheme but keeps queries intact`() {
        assertEquals("https://youtu.be/abc", InputParser.normalize("youtu.be/abc"))
        assertEquals("https://www.youtube.com/watch?v=abc", InputParser.normalize("www.youtube.com/watch?v=abc"))
        assertEquals("https://x.dev/a?b=1", InputParser.normalize("https://x.dev/a?b=1"))
        assertEquals("plain query", InputParser.normalize("plain query"))
    }
}
