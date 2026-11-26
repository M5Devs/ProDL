package app.prodl.core

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Minimal RIFF/WAVE writer: 16-bit PCM plus a 44-byte header.
 *
 * The header carries sizes known only after every sample is written, so the
 * file is written first and the two length fields are patched through random
 * access on close.
 */
object WavWriter {

    const val HEADER_BYTES = 44

    /** Header for [channels] interleaved 16-bit PCM of [dataBytes] payload. */
    fun header(sampleRate: Int, channels: Int, dataBytes: Long): ByteArray {
        val byteRate = sampleRate * channels * 2
        val out = ByteArrayOutputStream(HEADER_BYTES)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(littleEndian32((dataBytes + 36).toInt()))
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        out.write(littleEndian32(16))
        out.write(littleEndian16(1)) // PCM
        out.write(littleEndian16(channels))
        out.write(littleEndian32(sampleRate))
        out.write(littleEndian32(byteRate))
        out.write(littleEndian16(channels * 2)) // block align
        out.write(littleEndian16(16)) // bits per sample
        out.write("data".toByteArray(Charsets.US_ASCII))
        out.write(littleEndian32(dataBytes.toInt()))
        return out.toByteArray()
    }

    private fun littleEndian32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
    )

    private fun littleEndian16(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
    )

    internal fun littleEndian16(samples: ShortArray, count: Int): ByteArray {
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val value = samples[i].toInt()
            bytes[i * 2] = (value and 0xFF).toByte()
            bytes[i * 2 + 1] = ((value ushr 8) and 0xFF).toByte()
        }
        return bytes
    }
}

/** Destination for decoded PCM: MP3 (LAME), WAV (RIFF) or a caller's sink. */
interface PcmSink {
    fun open(sampleRate: Int, channels: Int)
    fun write(samples: ShortArray, count: Int)
    fun close()
}

class Mp3Sink(private val out: OutputStream, private val bitrateKbps: Int) : PcmSink {
    private var writer: Mp3Writer? = null

    override fun open(sampleRate: Int, channels: Int) {
        writer = Mp3Writer(sampleRate, channels, bitrateKbps)
    }

    override fun write(samples: ShortArray, count: Int) {
        writer?.write(samples, count, out)
    }

    override fun close() {
        writer?.finish(out)
        writer?.close()
        writer = null
    }
}

class WavSink(private val file: File) : PcmSink {
    private var out: OutputStream? = null
    private var dataBytes = 0L

    override fun open(sampleRate: Int, channels: Int) {
        val stream = BufferedOutputStream(file.outputStream())
        stream.write(WavWriter.header(sampleRate, channels, dataBytes = 0))
        out = stream
    }

    override fun write(samples: ShortArray, count: Int) {
        if (count <= 0) return
        out?.write(WavWriter.littleEndian16(samples, count))
        dataBytes += count * 2L
    }

    override fun close() {
        out?.flush()
        out?.close()
        out = null
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4)
            raf.write(littleEndian32((dataBytes + 36).toInt()))
            raf.seek(40)
            raf.write(littleEndian32(dataBytes.toInt()))
        }
    }

    private fun littleEndian32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
    )
}
