package app.prodl.core

import de.sciss.jump3r.mpg.MPGLib
import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.GetAudio
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Parse
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.VbrMode
import de.sciss.jump3r.mp3.Version
import java.io.Closeable
import java.io.OutputStream

/**
 * MP3 encoder: jump3r, a pure-Java port of LAME 3.100 (LGPL-2.1).
 *
 * Android's MediaCodec encodes AAC/Opus/FLAC and decodes MP3, but has never
 * shipped an MP3 *encoder*. This is the smallest honest way to keep MP3 as a
 * first-class output: ~350 KB of Java instead of a 35 MB ffmpeg payload, and it
 * runs on any ABI.
 *
 * LAME's `lame_encode_buffer_int` expects 32-bit samples, so 16-bit PCM is
 * shifted left by 16 before encoding.
 */
class Mp3Writer(
    private val sampleRate: Int,
    private val channels: Int,
    bitrateKbps: Int,
) : Closeable {

    // LAME is wired module by module, exactly like the reference CLI port:
    // `lame_init_params` dereferences these objects, so a bare `Lame()` throws.
    private val lame = Lame().apply {
        val gaud = GetAudio()
        val ga = newGain()
        val bs = BitStream()
        val presets = Presets()
        val qupvt = QuantizePVT()
        val qu = Quantize()
        val vbr = VBRTag()
        val ver = Version()
        val id3 = ID3Tag()
        val reservoir = Reservoir()
        val takehiro = Takehiro()
        val parse = Parse()
        val mpg = MPGLib()

        setModules(ga, bs, presets, qupvt, qu, vbr, ver, id3, mpg)
        bs.setModules(ga, mpg, ver, vbr)
        id3.setModules(bs, ver)
        presets.setModules(this)
        qu.setModules(bs, reservoir, qupvt, takehiro)
        qupvt.setModules(takehiro, reservoir, enc.psy)
        reservoir.setModules(bs)
        takehiro.setModules(qupvt)
        vbr.setModules(this, bs, ver)
        gaud.setModules(parse, mpg)
        parse.setModules(ver, id3, presets)
    }

    private val gfp: LameGlobalFlags = lame.lame_init().apply {
        in_samplerate = sampleRate
        out_samplerate = sampleRate
        num_channels = channels.coerceIn(1, 2)
        mode = if (channels == 1) MPEGMode.MONO else MPEGMode.JOINT_STEREO
        VBR = VbrMode.vbr_off
        brate = bitrateKbps.coerceIn(MIN_BITRATE, MAX_BITRATE)
        quality = 3
        bWriteVbrTag = false
        write_id3tag_automatic = false
    }

    private var leftBuf = IntArray(BLOCK_SAMPLES)
    private var rightBuf = IntArray(BLOCK_SAMPLES)
    private var mp3Buf = ByteArray((BLOCK_SAMPLES * 5 / 4) + 7200)

    @Volatile
    private var closed = false

    init {
        val status = lame.lame_init_params(gfp)
        if (status < 0) throw IllegalStateException("LAME rejected the encoder settings (code $status)")
    }

    /** Encode `count` interleaved 16-bit samples. */
    fun write(pcm: ShortArray, count: Int, out: OutputStream) {
        if (closed) throw IllegalStateException("Mp3Writer is closed")
        val frames = count / channels
        if (frames <= 0) return
        ensureCapacity(frames)
        for (i in 0 until frames) {
            val l = pcm[i * channels].toInt() shl 16
            leftBuf[i] = l
            rightBuf[i] = if (channels > 1) pcm[i * channels + 1].toInt() shl 16 else l
        }
        val written = lame.lame_encode_buffer_int(gfp, leftBuf, rightBuf, frames, mp3Buf, 0, mp3Buf.size)
        if (written < 0) throw IllegalStateException("LAME encode failed (code $written)")
        if (written > 0) out.write(mp3Buf, 0, written)
    }

    /** Flush the encoder's last frames. Idempotent. */
    fun finish(out: OutputStream) {
        if (closed) return
        closed = true
        val written = lame.lame_encode_flush(gfp, mp3Buf, 0, mp3Buf.size)
        if (written > 0) out.write(mp3Buf, 0, written)
        out.flush()
        lame.lame_close(gfp)
    }

    override fun close() {
        if (!closed) {
            closed = true
            runCatching { lame.lame_close(gfp) }
        }
    }

    private fun ensureCapacity(frames: Int) {
        if (frames <= leftBuf.size) return
        leftBuf = IntArray(frames)
        rightBuf = IntArray(frames)
        mp3Buf = ByteArray((frames * 5 / 4) + 7200)
    }

    companion object {
        const val MIN_BITRATE = 32
        const val MAX_BITRATE = 320
        private const val BLOCK_SAMPLES = 8192

        private fun newGain() = GainAnalysis()
    }
}
