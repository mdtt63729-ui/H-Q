package com.hiresstream.app.audio

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

class AudioEngine {
    val processor = RealtimeEnhancerAudioProcessor()

    fun renderersFactory(context: Context): DefaultRenderersFactory = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
            enableOffload: Boolean
        ): AudioSink {
            return DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(true)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setOffloadMode(DefaultAudioSink.OFFLOAD_MODE_DISABLED)
                .setAudioProcessors(arrayOf<AudioProcessor>(processor))
                .build()
        }
    }
}
