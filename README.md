# HiResStream v3

Material 3 Android music player with native Kotlin playback, Saavn stream resolution, and a real-time audio enhancement pipeline.

## Quality Adjustment
Settings contains one control: **Quality adjustment** with **128 kbps** and **320 kbps** profiles.

Changing the selection changes two things immediately:
1. The real-time DSP profile (`P128` / `P320`).
2. The source stream selected for the active track (`160/96` fallback for 128 profile, `320/160/96` fallback for 320 profile).

If a track is already playing, the app reloads the current track at the selected stream quality and preserves the playback position/play state as closely as the source permits.

## GitHub build
The included GitHub Actions workflow runs on push/PR and builds **only an unsigned release APK** with `assembleRelease`. Artifact:
`app-release-unsigned.apk`

No release signing key or keystore is included. Android release builds are unsigned unless a release signing configuration is explicitly supplied.

## Audio note
The enhancement stage is deterministic and conservative. A lossy source cannot be mathematically restored to the original studio master. The app therefore does not claim to create genuine lossless information from a lossy stream.

The 24-bit/192 kHz label is an output target/processing architecture; actual hardware output is negotiated by Android/AudioTrack and the device DAC.
