package com.hiresstream.app.audio

import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow

/** Real-time reconstruction/resampling processor for the Upgraded playback path. */
class RealtimeEnhancerAudioProcessor : AudioProcessor {
    data class AudioStats(
        val inputSampleRate: Int = 0,
        val inputChannels: Int = 0,
        val inputBits: Int = 0,
        val outputSampleRate: Int = 192_000,
        val outputBits: Int = 24,
        val outputIsFloatFallback: Boolean = Build.VERSION.SDK_INT < 31
    )

    val stats = MutableStateFlow(AudioStats())
    private var inputFormat = AudioProcessor.AudioFormat.NOT_SET
    private var outputFormat = AudioProcessor.AudioFormat.NOT_SET
    private var outputBuffer = EMPTY_BUFFER
    private var channelCount = 0
    private var inputRate = 0
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
        val outputEncoding = if (Build.VERSION.SDK_INT >= 31) C.ENCODING_PCM_24BIT else C.ENCODING_PCM_FLOAT
        outputFormat = AudioProcessor.AudioFormat(192_000, channelCount, outputEncoding)
        stats.value = AudioStats(
            inputSampleRate = inputRate,
            inputChannels = channelCount,
            inputBits = bitsForEncoding(inputAudioFormat.encoding),
            outputBits = if (outputEncoding == C.ENCODING_PCM_24BIT) 24 else 32,
            outputIsFloatFallback = outputEncoding == C.ENCODING_PCM_FLOAT
        )
        return outputFormat
    }

    override fun isActive(): Boolean = inputFormat != AudioProcessor.AudioFormat.NOT_SET

    override fun queueEndOfStream() {
        if (sourceFrameCount > 0) {
            val step = inputRate.toDouble() / 192_000.0
            val finalFrame = sourceFrameCount - 1.0
            val estimatedFrames = max(1, floor(max(0.0, (finalFrame - sourcePosition) / step)).toInt() + 1)
            val bytesPerFrame = channelCount * if (outputFormat.encoding == C.ENCODING_PCM_24BIT) 3 else 4
            val out = ByteBuffer.allocateDirect(estimatedFrames * bytesPerFrame + bytesPerFrame).order(ByteOrder.LITTLE_ENDIAN)
            while (sourcePosition <= finalFrame) {
                emitInterpolatedFrame(sourcePosition, out)
                sourcePosition += step
            }
            out.flip()
            outputBuffer = out
            sourceFrameCount = 0
            sourcePosition = 0.0
            sourceFrames = FloatArray(0)
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
        produceAvailable()
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
        if (sourceFrames.size < required) sourceFrames = sourceFrames.copyOf(max(required, sourceFrames.size * 2 + channelCount * 64))
        samples.copyInto(sourceFrames, sourceFrameCount * channelCount)
        sourceFrameCount += samples.size / channelCount
    }

    private fun produceAvailable() {
        if (sourceFrameCount < 2) return
        val step = inputRate.toDouble() / 192_000.0
        val maxPosition = sourceFrameCount - 1.0
        val estimatedFrames = max(1, floor((maxPosition - sourcePosition) / step).toInt() + 1)
        val bytesPerFrame = channelCount * if (outputFormat.encoding == C.ENCODING_PCM_24BIT) 3 else 4
        val out = ByteBuffer.allocateDirect(estimatedFrames * bytesPerFrame + bytesPerFrame).order(ByteOrder.LITTLE_ENDIAN)
        while (sourcePosition < maxPosition) {
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
        val v = (sample * 8_388_607f).toInt().coerceIn(-8_388_608, 8_388_607)
        if (outputFormat.encoding == C.ENCODING_PCM_24BIT) {
            buffer.put((v and 0xFF).toByte())
            buffer.put(((v shr 8) and 0xFF).toByte())
            buffer.put(((v shr 16) and 0xFF).toByte())
        } else buffer.putFloat(sample)
    }

    private fun enhance(x0: Float, channel: Int): Float {
        val prev = if (channel == 0) lastL else lastR
        val hp = x0 - prev
        val restored = (x0 - hp * 0.004f + hp * 0.018f).coerceIn(-1f, 1f)
        if (channel == 0) lastL = x0 else lastR = x0
        val ceiling = 0.992f
        return if (abs(restored) > ceiling) {
            (ceiling + (abs(restored) - ceiling) * 0.18f) * if (restored < 0f) -1f else 1f
        } else restored
    }

    private fun compactSourceBuffer() {
        val consumed = sourcePosition.toInt().coerceAtMost(sourceFrameCount - 1)
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

    companion object { private val EMPTY_BUFFER = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()) }
}
