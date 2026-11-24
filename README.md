# ToneRoom for Android

ToneRoom makes voiceovers from a microphone take, an existing audio or video clip, or an English script read in a synthesized version of the owner's voice. It runs entirely on the device. The APK contains the English voice model.

## Use on a phone

- **Record** captures up to five minutes per take. **Import audio or video** reads the first audio track from a file you select. For the best result, choose a clip where the voice is already separate from music.
- **From script** uses a 4–12 second sample of your own speech. Record the suggested line or choose your own short audio file. Type up to 500 characters and tap **Generate voiceover**. Speak in the style you want when making the reference sample. Generation time and voice similarity vary by phone and sample.
- **Balanced**, **Warm** and **Clear** adjust the sound. Switch between **Original** (or **Generated**) and **Finished** during playback to compare the same point in the take. The recording finish keeps the timing of your speech.
- **Save WAV** exports a mono, 16-bit PCM voice track to the location you choose. For a supported imported video, **Save video** copies its picture frames and orientation into an MP4 with the finished voice track. Keep the original video accessible until the export finishes. Video import/export uses the codecs on the phone. A script generates a separate audio take for use as narration.
- **Takes** reopens or deletes local takes. The reference and drafts are private to the app. Export anything you want to keep before clearing application data or uninstalling. Microphone access is requested when you record. Files are selected individually through Android’s picker.

## Android Studio source

Open this directory in Android Studio. This is a standalone Kotlin and Jetpack Compose project. The Gradle wrapper, font files, native inference library, model weights and test fixtures are included. Gradle downloads standard Android dependencies on the first build.

Use JDK 17, Android SDK 35 and Build Tools 34.0.0. This version supports Android 8.0 or later on arm64-v8a, armeabi-v7a and x86_64. Package ID `uk.co.agentengines.toneroom`, version 1.0 (2).

```sh
./gradlew :app:assembleRelease
./gradlew :app:connectedReleaseAndroidTest
```

The personal release variant is non-debuggable and uses the local Android development signing configuration. The source archive excludes signing keys. Android Studio can sign a separate distribution build with an owner-managed key. Android requires the same signing certificate for an in-place update. The supplied APK certificate SHA-256 is `405af6a14d5b06d6bc64cc002cdd37855e5eea1595c755228dd385ccea9de385`.

## Audio and model

The studio finish uses a high-pass filter, broad EQ, compression and a soft peak ceiling. A reference-conditioned Pocket TTS model generates new speech. Its input is the reference waveform plus the new text. The reference is stored locally and can be replaced or removed under **From script**. The app declares no network permission.

The model is Kyutai Pocket TTS, bundled in the January 2026 int8 ONNX conversion published by sherpa-onnx. Conversion by KevinAHM and the sherpa-onnx project. The upstream model archive SHA-256 is `f4da7c7bd465f7b91e89191487fe90504396a51d6f0bcf94d0787633e2657293`. The bundled static sherpa-onnx Android AAR, version 2.0.3, has SHA-256 `7744a3a8f08f017d59c2d8ed8d9b700345561f5abe9f4e06dc6eaef3a0b977b4`. Credits and licence texts are in `app/src/main/assets/licenses` and the app’s **About ToneRoom** screen.

- [Kyutai Pocket TTS](https://huggingface.co/kyutai/pocket-tts)
- [sherpa-onnx model documentation](https://k2-fsa.github.io/sherpa/onnx/tts/pocket.html)
- [sherpa-onnx 2.0.3 Android binaries](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.3)

The instrumented checks use a generated Flite voice reading a test phrase. The rotated test video contains generated colour bars and the same synthetic speech. They exercise the model, silent-reference handling, all three finishes and video export. The fixtures are in the test APK only.
