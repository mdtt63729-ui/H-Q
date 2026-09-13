# HiResStream

A small Material 3 Android music player with real-time conservative audio enhancement.

## Extension-first provider
The app does not bundle a large music-provider implementation. From Home/Search, use **Add Extension** and select the downloaded Echo Saavn extension JAR. The installer reads its Echo manifest, validates `Extension-Id=saavn_music`, stores the package, and activates the native Android Saavn adapter. This avoids shipping a JVM extension runtime in the APK.

The adapter follows the provider contract used by the supplied Echo extension: Saavn search, home feed, song details and 320/160/96/48 kbps stream variants. The extension package itself is used as the activation/provider declaration; Android does not execute arbitrary Java 17 JVM bytecode from the JAR.

## Quality Adjustment
Settings contains only **Quality adjustment** with **128 kbps** and **320 kbps** profiles. The selected profile changes both the source stream selection and the real-time DSP profile. The provider exposes 320/160/96/48 variants, so the 128 profile uses the best available lower stream (normally 160/96) rather than falsely claiming a native 128 kbps source.

## Size optimization
Release builds enable R8 code shrinking and resource shrinking, remove unused Media3 UI/session modules, replace Coil with a tiny platform image loader, and replace kotlinx serialization with Android's built-in JSON parser. This is intended to bring the unsigned release APK substantially below the previous size; the final size is measured by the GitHub artifact itself. Android recommends R8 and resource shrinking for smaller release apps.

## Audio note
The DSP is deterministic and conservative. A lossy source cannot be mathematically restored to the original studio master. The app therefore does not claim to create genuine lossless information from a lossy stream. Actual output sample rate/bit depth remains subject to Android AudioTrack and the device DAC.

## GitHub build
The included GitHub Actions workflow builds only an **unsigned release APK** with `assembleRelease`.
