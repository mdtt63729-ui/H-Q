package com.hiresstream.app.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Robust local-only enhancement processor for Upgraded playback.
 *
 * The important compatibility rule is that the processor outputs standard PCM16 at 48 kHz.
 * This avoids device/route-specific failures seen with forced 192 kHz float/packed-24 output,
 * while the actual decode, resampling and DSP still happen entirely on the phone.
 */
class RealtimeEnhancerAudioProcessor : AudioProcessor {
    data class AudioStats(
        val inputSampleRate: Int = 0,
        val inputChannels: Int = 0,
        val inputBits: Int = 0,
        val outputSampleRate: Int = 48_000,
        val outputBits: Int = 16,
        val outputIsFloat: Boolean = false
    )

    val stats = MutableStateFlow(AudioStats())
    private var inputFormat = AudioProcessor.AudioFormat.NOT_SET
    private var outputFormat = AudioProcessor.AudioFormat.NOT_SET
    private var outputBuffer = EMPTY_BUFFER
    private var channelCount = 0
    private var inputRate = 0
    private val targetRate = 48_000
    private var sourceFrames = FloatArray(0)
    private var sourceFrameCount = 0
    private var sourcePosition = 0.0
    private var ended = false
    private var lastL = 0f
    private var lastR = 0f

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        inputFormat = inputAudioFormat
        channelCount = inputAudioFormat.channelCount
        inputRate = inputAudioFormat.sampleRate
        if (channelCount <= 0 || inputRate <= 0) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_24BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }

        // 48 kHz PCM16 is intentionally used as the stable device-facing format. The entire
        // enhancement remains local; Android's AudioTrack/route gets a format it can reliably
        // play instead of a forced 192 kHz format that can fail on some devices.
        outputFormat = AudioProcessor.AudioFormat(targetRate, channelCount, C.ENCODING_PCM_16BIT)
        stats.value = AudioStats(
            inputSampleRate = inputRate,
            inputChannels = channelCount,
            inputBits = bitsForEncoding(inputAudioFormat.encoding),
            outputSampleRate = targetRate,
            outputBits = 16,
            outputIsFloat = false
        )
        return outputFormat
    }

    override fun isActive(): Boolean = inputFormat != AudioProcessor.AudioFormat.NOT_SET

    override fun queueEndOfStream() {
        if (sourceFrameCount > 0) {
            produceAvailable(forceFinal = true)
        }
        ended = true
    }

    override fun getOutput(): ByteBuffer = outputBuffer.also { outputBuffer = EMPTY_BUFFER }

    override fun isEnded(): Boolean = ended && outputBuffer === EMPTY_BUFFER && sourceFrameCount == 0

    override fun flush() {
        outputBuffer = EMPTY_BUFFER
        sourceFrames = FloatArray(0)
        sourceFrameCount = 0
        sourcePosition = 0.0
        lastL = 0f
        lastR = 0f
        ended = false
    }

    override fun reset() {
        flush()
        inputFormat = AudioProcessor.AudioFormat.NOT_SET
        outputFormat = AudioProcessor.AudioFormat.NOT_SET
        channelCount = 0
        inputRate = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val decoded = decodeToFloat(inputBuffer)
        inputBuffer.position(inputBuffer.limit())
        if (decoded.isEmpty()) return
        appendFrames(decoded)
        produceAvailable(forceFinal = false)
    }

    private fun decodeToFloat(inputBuffer: ByteBuffer): FloatArray {
        val duplicate = inputBuffer.duplicate()
        duplicate.order(if (inputFormat.encoding == C.ENCODING_PCM_FLOAT) ByteOrder.nativeOrder() else ByteOrder.LITTLE_ENDIAN)
        return when (inputFormat.encoding) {
            C.ENCODING_PCM_FLOAT -> duplicate.asFloatBuffer().let { fb -> FloatArray(fb.remaining()).also { fb.get(it) } }
            C.ENCODING_PCM_16BIT -> duplicate.asShortBuffer().let { sb -> FloatArray(sb.remaining()) { sb.get().toFloat() / 32768f } }
            C.ENCODING_PCM_24BIT -> {
                val bytes = ByteArray(duplicate.remaining()).also { duplicate.get(it) }
                FloatArray(bytes.size / 3).also { out ->
                    var p = 0
                    for (i in out.indices) {
                        val b0 = bytes[p].toInt() and 0xFF
                        val b1 = bytes[p + 1].toInt() and 0xFF
                        val b2 = bytes[p + 2].toInt()
                        var value = b0 or (b1 shl 8) or (b2 shl 16)
                        if ((value and 0x800000) != 0) value = value or -0x1000000
                        out[i] = value / 8_388_608f
                        p += 3
                    }
                }
            }
            else -> FloatArray(0)
        }
    }

    private fun appendFrames(samples: FloatArray) {
        val required = sourceFrameCount * channelCount + samples.size
        if (sourceFrames.size < required) {
            sourceFrames = sourceFrames.copyOf(max(required, sourceFrames.size * 2 + channelCount * 256))
        }
        samples.copyInto(sourceFrames, sourceFrameCount * channelCount)
        sourceFrameCount += samples.size / channelCount
    }

    private fun produceAvailable(forceFinal: Boolean) {
        if (sourceFrameCount < if (forceFinal) 1 else 2) return

        val step = inputRate.toDouble() / targetRate.toDouble()
        val maxPosition = sourceFrameCount - 1.0
        val lastPosition = if (forceFinal) maxPosition else maxPosition - 1e-9
        if (sourcePosition > lastPosition) return

        val estimatedFrames = max(1, floor((lastPosition - sourcePosition) / step).toInt() + 1)
        val bytesPerFrame = channelCount * 2
        val out = ByteBuffer.allocateDirect(estimatedFrames * bytesPerFrame + bytesPerFrame)
            .order(ByteOrder.LITTLE_ENDIAN)

        while (sourcePosition <= lastPosition) {
            emitInterpolatedFrame(sourcePosition, out)
            sourcePosition += step
        }

        compactSourceBuffer()
        out.flip()
        outputBuffer = out
    }

    private fun emitInterpolatedFrame(position: Double, target: ByteBuffer) {
        val i0 = position.toInt().coerceIn(0, max(0, sourceFrameCount - 1))
        val i1 = min(i0 + 1, max(0, sourceFrameCount - 1))
        val fraction = (position - i0).toFloat().coerceIn(0f, 1f)
        for (ch in 0 until channelCount) {
            val a = sourceFrames[i0 * channelCount + ch]
            val b = sourceFrames[i1 * channelCount + ch]
            writeSample(target, enhance(a + (b - a) * fraction, ch))
        }
    }

    private fun writeSample(buffer: ByteBuffer, sample: Float) {
        val v = (sample * 32767f).toInt().coerceIn(-32768, 32767)
        buffer.putShort(v.toShort())
    }

    private fun enhance(x0: Float, channel: Int): Float {
        val prev = when {
            channel == 0 -> lastL
            channel == 1 -> lastR
            else -> 0f
        }
        val delta = x0 - prev
        // Very mild local presence/clarity shaping. It does not invent missing source detail.
        val restored = (x0 + delta * 0.012f).coerceIn(-1f, 1f)
        if (channel == 0) lastL = x0 else if (channel == 1) lastR = x0
        val ceiling = 0.992f
        return if (abs(restored) > ceiling) {
            (ceiling + (abs(restored) - ceiling) * 0.12f) * if (restored < 0f) -1f else 1f
        } else restored
    }

    private fun compactSourceBuffer() {
        val consumed = sourcePosition.toInt().coerceAtMost(max(0, sourceFrameCount - 1))
        if (consumed <= 0) return
        val remainingFrames = sourceFrameCount - consumed
        sourceFrames.copyInto(sourceFrames, 0, consumed * channelCount, sourceFrameCount * channelCount)
        sourceFrameCount = remainingFrames
        sourcePosition -= consumed
    }

    private fun bitsForEncoding(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_FLOAT -> 32
        C.ENCODING_PCM_24BIT -> 24
        C.ENCODING_PCM_16BIT -> 16
        else -> 0
    }

    companion object {
        private val EMPTY_BUFFER = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }
}
