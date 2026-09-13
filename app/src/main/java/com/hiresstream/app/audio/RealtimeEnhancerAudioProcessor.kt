package com.hiresstream.app.audio

import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight real-time restoration stage. It intentionally avoids aggressive synthesis:
 * transient-safe de-harshing, gentle high-frequency reconstruction and soft limiting.
 * The profile changes the actual coefficients, not only the UI label.
 */
class RealtimeEnhancerAudioProcessor : AudioProcessor {
    enum class Profile(val hfMix: Float, val denoise: Float, val ceiling: Float) {
        P128(0.035f, 0.010f, 0.985f),
        P320(0.018f, 0.004f, 0.992f)
    }

    @Volatile var profile: Profile = Profile.P320

    private var inputFormat = AudioProcessor.AudioFormat.NOT_SET
    private var outputBuffer = EMPTY_BUFFER
    private var channelCount = 0
    private var lastL = 0f
    private var lastR = 0f
    private var ended = false

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        inputFormat = inputAudioFormat
        channelCount = inputAudioFormat.channelCount
        if (inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_FLOAT &&
            inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun isActive(): Boolean = inputFormat != AudioProcessor.AudioFormat.NOT_SET
    override fun queueEndOfStream() { ended = true }
    override fun getOutput(): ByteBuffer = outputBuffer.also { outputBuffer = EMPTY_BUFFER }
    override fun isEnded(): Boolean = ended && outputBuffer === EMPTY_BUFFER
    override fun flush() { outputBuffer = EMPTY_BUFFER; lastL = 0f; lastR = 0f; ended = false }
    override fun reset() { flush(); inputFormat = AudioProcessor.AudioFormat.NOT_SET; channelCount = 0 }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val out = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        if (inputFormat.encoding == androidx.media3.common.C.ENCODING_PCM_FLOAT) {
            val src = inputBuffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
            val count = src.remaining()
            val tmp = FloatArray(count)
            src.get(tmp)
            process(tmp)
            out.asFloatBuffer().put(tmp)
            out.limit(bytes)
        } else {
            val dup = inputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val count = dup.remaining()
            repeat(count) {
                val s = dup.get().toInt() / 32768f
                val v = processSample(s, if (it % max(channelCount, 1) == 0) 0 else 1)
                out.putShort((v * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            }
            out.flip()
        }
        inputBuffer.position(inputBuffer.limit())
        outputBuffer = out
    }

    private fun process(samples: FloatArray) {
        for (i in samples.indices) samples[i] = processSample(samples[i], if (channelCount > 1) i % channelCount else 0)
    }

    private fun processSample(x0: Float, channel: Int): Float {
        val p = profile
        val prev = if (channel == 0) lastL else lastR
        val hp = x0 - prev
        val deHarsh = x0 - hp * p.denoise
        val restored = deHarsh + hp * p.hfMix
        if (channel == 0) lastL = x0 else lastR = x0
        val limited = if (abs(restored) > p.ceiling) {
            p.ceiling + (abs(restored) - p.ceiling) * 0.18f
        } else restored
        return limited.coerceIn(-1f, 1f)
    }

    companion object { private val EMPTY_BUFFER = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()) }
}
