package com.hiresstream.app.audio

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * Local-only Hi-Res playback engine.
 * The processor never performs network I/O; all enhancement is executed on-device.
 */
class AudioEngine {
    val processor = RealtimeEnhancerAudioProcessor()

    fun renderersFactory(context: Context): DefaultRenderersFactory = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean
        ): AudioSink = DefaultAudioSink.Builder(context)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf<AudioProcessor>(processor))
            .build()
    }
}
