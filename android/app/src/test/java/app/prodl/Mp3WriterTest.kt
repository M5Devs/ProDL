package app.prodl

import app.prodl.core.Mp3Writer
import app.prodl.core.WavWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.sin

/**
 * MP3 is the one output Android cannot encode natively, so it is the one that
 * needs proving. jump3r is pure Java: this encodes a real sine wave and parses
 * the frame header back, which fails loudly if the wrong class or setting is
 * used on a future upgrade.
 */
class Mp3WriterTest {

    private fun tone(sampleRate: Int, channels: Int, seconds: Double): ShortArray {
        val frames = (sampleRate * seconds).toInt()
        val samples = ShortArray(frames * channels)
        for (i in 0 until frames) {
            val value = (sin(2 * PI * 440.0 * i / sampleRate) * 12_000).toInt().toShort()
            for (c in 0 until channels) samples[i * channels + c] = value
        }
        return samples
    }

    @Test
    fun `encodes a valid mpeg frame stream and flushes cleanly`() {
        val sampleRate = 44_100
        val out = ByteArrayOutputStream()
        val writer = Mp3Writer(sampleRate, channels = 2, bitrateKbps = 128)
        try {
            val pcm = tone(sampleRate, 2, 1.0)
            writer.write(pcm, pcm.size, out)
            writer.finish(out)
        } finally {
            writer.close()
        }

        val bytes = out.toByteArray()
        assertTrue("encoder produced ${bytes.size} bytes", bytes.size > 8_000)
        // MPEG-1 Layer III, no CRC: 11 sync bits set, version 1, layer 3.
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xFB, bytes[1].toInt() and 0xFB)
        val bitrate = (bytes[2].toInt() and 0xFF) ushr 4
        val sampleRateIndex = (bytes[2].toInt() and 0x0C) ushr 2
        assertEquals("128 kbps", 9, bitrate)
        assertEquals("44.1 kHz", 0, sampleRateIndex)
        // 1 s at 128 kbps ≈ 16 KB, allow encoder padding.
        assertTrue("size ${bytes.size} within 10%", bytes.size in 14_000..18_000)
    }

    @Test
    fun `mono input produces a single-channel stream`() {
        val out = ByteArrayOutputStream()
        val writer = Mp3Writer(22_050, channels = 1, bitrateKbps = 64)
        try {
            val pcm = tone(22_050, 1, 0.5)
            writer.write(pcm, pcm.size, out)
            writer.finish(out)
        } finally {
            writer.close()
        }
        val bytes = out.toByteArray()
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        val mode = (bytes[3].toInt() and 0xC0) ushr 6 // 3 = mono
        assertEquals(3, mode)
        assertTrue(bytes.size > 3_000)
    }

    @Test
    fun `flush is idempotent`() {
        val out = ByteArrayOutputStream()
        val writer = Mp3Writer(44_100, 1, 96)
        val pcm = tone(44_100, 1, 0.1)
        writer.write(pcm, pcm.size, out)
        writer.finish(out)
        val first = out.size()
        writer.finish(out)
        writer.close()
        assertEquals(first, out.size())
    }

    @Test
    fun `wav header declares the pcm payload`() {
        val header = WavWriter.header(sampleRate = 48_000, channels = 2, dataBytes = 96_000)
        assertEquals(WavWriter.HEADER_BYTES, header.size)
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(header, 36, 4, Charsets.US_ASCII))
        // 96_000 + 36 = 0x17724, little endian
        assertEquals(0x24, header[4].toInt() and 0xFF)
        assertEquals(0x77, header[5].toInt() and 0xFF)
        assertEquals(0x01, header[6].toInt() and 0xFF)
        // byte rate = 48000 * 2 * 2
        assertEquals(192_000, le32(header, 28))
        assertEquals(96_000, le32(header, 40))
    }

    private fun le32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
