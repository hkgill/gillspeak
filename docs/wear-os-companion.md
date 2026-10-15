# GillSpeak Watch6 companion preview

This preview consists of two Android applications with the **same package ID and certificate**: the existing ARM64 phone app (`:app`) and the Wear OS app (`:wear`). Both use `dev.hkgill.gillspeak`, version `0.3.2-watch-preview`, code 5. The watch APK has no native model libraries and works with Wear OS API 30+. It declares itself non-standalone and requires a paired Android phone.

## Use

1. Install the phone APK as an in-place update **only if its certificate matches the installed app**. Never uninstall the phone app to resolve a signer mismatch; that would remove models and settings. A public release certificate and a local debug certificate are different identities.
2. Install the matching watch APK on the intended paired Watch6. Check the target with `adb devices -l`; never assume another connected Android device is the watch.
3. On the phone, open GillSpeak → **Watch companion** → **Enable for 30 minutes**. Gemma must already be downloaded in Speech and understanding. No model is downloaded automatically by the companion.
4. Open GillSpeak on the watch. **Check phone** confirms the session/model. Type a question and tap **Ask phone**. Scroll to read the reply; the rotating bezel can scroll too. **Cancel** ends the request. Leaving the watch app cancels pending work; it keeps the screen awake while awaiting a response, for at most 90 seconds.
5. You can stop the session on the phone or through its foreground notification. Android may show the foreground service only in its active-apps UI if notifications are disabled. This preview does not request a notification permission or alter any existing permission. There is no auto-start on boot.

The phone's foreground session is designed to keep serving while its screen is off or locked. The session checks an elapsed-time deadline when receiving requests, so sleep cannot extend acceptance beyond 30 minutes. Each answer acquires a wake lock for at most 95 seconds. No phone microphone is used. Force-stop, reboot, Samsung battery management, lost Bluetooth proximity, or process termination can interrupt the session. Enable it again on the phone if needed. **A successful build or phone installation is not an end-to-end watch deployment or proof of locked-phone behavior.** See the delivered validation report for what was actually exercised.

## Architecture and privacy

- `:wear-protocol` is a pure Kotlin JVM module shared by both applications. Version 1 uses bounded binary messages, UUID request IDs, strict UTF-8 decoding, 1,500-character questions and 6,000-character answers. Protocol or application version mismatches fail instead of interpreting unknown messages.
- Official Google Play services Wearable **20.0.1** supplies `CapabilityClient`, `MessageClient`, and `WearableListenerService`. Each side advertises a distinct versioned capability. The watch selects exactly one reachable nearby phone; the phone accepts only the nearby watch capability. Play services additionally enforces identical package IDs and signing certificates. This identifies the paired companion app, not an arbitrary Bluetooth device.
- The phone listener validates the path, envelope, timestamp and node before dispatching on the main thread. It does not launch a foreground service from the background. If no user-enabled session exists, the watch gets setup guidance.
- The foreground service admits one watch inference at a time. Recent request IDs and cancellation tombstones prevent duplicate execution and cancel-before-request races. The native slot stays occupied until its worker exits, including after cancellation. Retries are manual with a new ID. Questions are never persisted as DataItems or automatically replayed on reconnection.
- `AnswerTask` wraps the **existing phone `LocalLlm.ask`** and its Gemma 4 E2B / LiteRT-LM implementation. The model lock remains shared with normal phone dictation/commands. There is no watch model or cloud inference endpoint. The assistant uses a fresh conversation with a short-answer prompt and no tool/action execution.
- Cancellation is scoped to the owning request. Checks before loading, after loading, and before native generation prevent a cancelled queued request from generating later. Native loading itself cannot be interrupted by the SDK; the slot is held until it returns. Reply correlation rejects late, cancelled, mismatched-node and mismatched-ID replies.
- Nearby capability checks restrict use to the local companion, but Google Play services controls Data Layer routing (Bluetooth or another transport). This is **not a claim of packet-level Bluetooth-only transport**. There is no application cloud AI service.
- The app does not create question history, record audio, read phone contacts for watch answers, or log user questions/replies. Transient watch Activity saved state contains the visible question/answer for recreation; app backups are disabled. The debug-only DUMP-protected test receiver can run a fixed synthetic arithmetic question and log its answer.

## Voice feasibility

`LocalAsr.transcribe(context, wav)` already accepts a 44-byte-header, 16 kHz mono PCM16 WAV and runs Parakeet on the phone. A future explicitly started watch `AudioRecord` session could capture that format, transfer a bounded recording using the Data Layer Channel API, validate the WAV header/format/length, then call the existing ASR and Gemma paths on the phone. Audio needs a separate size-limited transfer because even a few seconds exceeds the text message budget. Cancellation, interrupted channel transfers, in-memory cleanup, a recording duration limit, user-visible recording state and watch microphone consent must be implemented and device-tested together.

This preview deliberately implements **typed input**. It does not invoke `SpeechRecognizer`, a recognition intent, or a microphone. System keyboard voice entry is external to GillSpeak and may use the keyboard provider's cloud; the watch screen warns about that. No real audio or microphone permission is needed for the preview.

## Build and test

Use JDK 17 and the existing Android SDK 36. This branch retains AGP 8.12.0, Kotlin 2.2.21 and Gradle 9.3.1 to avoid unrelated toolchain changes. The shared test suite exercises malformed messages, bounds, freshness, cancellation races, duplicate/busy admission, native failure/missing models, response correlation and deadline behavior.

```sh
cd android
./gradlew :app:testDebugUnitTest :wear-protocol:test :wear:testDebugUnitTest \
  :app:assembleDebug :wear:assembleDebug :app:lintDebug :wear:lintDebug
```

The watch's transport/state logic is in the shared module; `:wear:testDebugUnitTest` currently has no separate UI tests. JVM tests do not establish a round-screen layout, bezel/keyboard behavior, pairing, transport delivery or phone OEM background behavior.

`android/local.properties` may set `debug.storeFile` to an **existing** debug keystore outside the checkout. It is ignored and must not be committed. Before building for an installed phone, verify that key against the installed APK certificate with `apksigner verify --print-certs`. Both APKs must use that same key. Do not generate a new key or copy signing secrets into artifacts. This preview is not a Store or GitHub release; any future Play submission must review the declared `specialUse` foreground service.

## Watch installation and remaining device acceptance

If the Watch6 is not listed and authorized in `adb devices -l`, installation is blocked on watch user action. Enable Developer options → ADB debugging / Wireless debugging on the **watch**, pair its debugging session with the workstation using the address and code shown on the watch, and authorize it on the watch. Wear OS 3 can have a different debugging flow from Wear OS 4+. Do not change pairing or accept a new authorization prompt on someone's behalf. Once the intended watch is confirmed:

```sh
adb -s WATCH_SERIAL shell getprop ro.product.model
adb -s WATCH_SERIAL install -r gillspeak-watch-debug.apk
```

Never install the watch APK on the phone: the package IDs intentionally match. Test a typed question with the phone visible, backgrounded and locked; Cancel during cold model load and generation; app/session stop; missing model; Bluetooth loss/reconnection; stale reply rejection; and the 30-minute session expiry. Check the round-screen layout and keyboard on the actual Watch6. Do not activate microphones or accept new runtime permissions without the user's approval.

## Official references

- [Data Layer overview and package/certificate enforcement](https://developer.android.com/training/wearables/data/overview)
- [Message and channel client choices](https://developer.android.com/training/wearables/data/client-types)
- [Handling Data Layer events and listener services](https://developer.android.com/training/wearables/data/events)
- [Google Play services release notes: Wearable 20.0.1](https://developers.google.com/android/guides/releases)
- [Foreground service types and special use](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Background foreground-service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Debugging a Wear OS app](https://developer.android.com/training/wearables/get-started/debugging)
