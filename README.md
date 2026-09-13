# HiResStream

A small Material 3 Android music player with real-time conservative audio enhancement.

## Extension-first provider
The app does not bundle a large music-provider implementation. On first launch the app automatically checks the supplied Echo Saavn GitHub release and downloads the latest `.eapk` package. It stores the package locally and activates the native Android Saavn adapter. This avoids shipping a JVM extension runtime in the APK.

The adapter follows the provider contract used by the supplied Echo extension: Saavn search, home feed, song details and 320/160/96/48 kbps stream variants. The extension package itself is used as the activation/provider declaration; Android does not execute arbitrary Java 17 JVM bytecode from the JAR.

## Automatic Hi-Res Upgrade
There is no manual quality setting. Playback automatically selects the best source variant available for each track, measures the playback format, and sends the Upgraded path through a real-time 192 kHz reconstruction/resampling stage. On Android 12+, the enhanced engine emits 24-bit packed PCM to the audio sink.

The full-screen player provides an **Original / Upgraded** A/B switch. Original keeps the provider stream untouched; Upgraded applies the real-time processing and shows the measured source details plus the 24-bit/192 kHz engine target. This does not turn lossy audio into the original studio master.

## Size optimization
Release builds enable R8 code shrinking and resource shrinking, remove unused Media3 UI/session modules, replace Coil with a tiny platform image loader, and replace kotlinx serialization with Android's built-in JSON parser. This is intended to bring the unsigned release APK substantially below the previous size; the final size is measured by the GitHub artifact itself. Android recommends R8 and resource shrinking for smaller release apps.

## Audio note
The DSP is deterministic and conservative. A lossy source cannot be mathematically restored to the original studio master. The app therefore does not claim to create genuine lossless information from a lossy stream. Actual output sample rate/bit depth remains subject to Android AudioTrack and the device DAC.

## GitHub build
The included GitHub Actions workflow builds only an **unsigned release APK** with `assembleRelease`.


## Upgraded mode — fully local processing

Upgraded mode is a local/on-device audio pipeline. The app does not send audio to a cloud enhancement API or remote DSP service. The network is used only to obtain the source stream/metadata when playing online (and for extension updates); Media3 decodes the stream locally, then `RealtimeEnhancerAudioProcessor` performs the resampling and DSP locally before output. Offline downloads use the same local processing path.

Pipeline:
`source stream/file → Media3 local decoder → local 192 kHz float resampler/DSP → AudioSink/AudioTrack`

The 192 kHz figure is the engine target, not a guarantee that the phone's physical DAC/route runs at 192 kHz. Upgrading also cannot recreate information that was never present in a lossy source.


## Upgraded playback compatibility
The Upgraded path is fully local. To avoid device-specific failures from forced 192 kHz/float output, the local processor now uses a stable 48 kHz PCM16 device-facing path while retaining local decode, resampling and DSP enhancement.
