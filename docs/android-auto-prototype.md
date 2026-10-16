# Android Auto developer prototype

This is **a debug-only speech/template experiment, not a deployable Android Auto assistant**.
The debug APK has a separate **GillSpeak car preview** phone launcher entry. It renders the
same message and action model as the car template, using synthetic audio and text only.
It never requests permission, opens a microphone, downloads a model, or runs inference.
No car components or dependencies are included in the release APK.

## Product decision before Android Auto distribution

Official Android documentation checked on 16 October 2026:

- [Supported categories](https://developer.android.com/training/cars#supported-app-categories)
  and [car quality requirements](https://developer.android.com/docs/quality-guidelines/car-app-quality)
  provide no general-assistant category. A general dictation/assistant app must not claim
  media, navigation, POI, IoT, weather, messaging, games or calling just to appear in the launcher.
- [Calling experiences](https://developer.android.com/training/cars/communication/calling)
  require genuine app-owned calling integrated with Jetpack Telecom, including remote-surface
  callbacks and Telecom use outside Android Auto. Calling apps are limited to Play internal
  and closed testing. GillSpeak's existing SIM dialler intents do not establish that eligibility.
  A closed testing track by itself does not make a general assistant eligible either.
- [CarAudioRecord](https://developer.android.com/reference/androidx/car/app/media/CarAudioRecord)
  supplies car microphone PCM at host API 5+, with microphone permission and audio focus.
  A host returning `-1` means dismissal/interruption; partial audio must be discarded.
- [Android Auto](https://developer.android.com/training/cars) projects a **phone** app.
  This prototype uses `app`/`app-projected`, not the AAOS `app-automotive` artifact.
- [Car App Library releases](https://developer.android.com/jetpack/androidx/releases/car-app)
  recommend 1.8.0-rc01 for a security fix over earlier versions; the debug dependencies are pinned to it.

Consequently `CarPrototypeService` is deliberately **unregistered**: no `CarAppService`
manifest entry, car category, automotive discovery metadata, or permissive host validator.
The current build will **not appear in the Android Auto launcher**, including the DHU.
The supported route available now is an in-process Car App Library test context and the
synthetic phone preview. There is no claim that a category-free service can be launched
by a real host. Do not add a false category to work around this boundary.

Before host integration, choose an actually eligible product with meaningful category
functionality, or obtain an explicitly supported platform route for general assistants.
Then review manifest/discovery declarations, narrowly trusted hosts, category-specific
requirements, distribution access and the privacy/driver-distraction flow. App-owned
calling would be a separate product implementation, not a label change.

This work does not replace Google's global assistant, hotword or steering-wheel action.
It does not change the phone's assistant role, keyboard, bubble, watch support, or permissions.

## What is implemented

- A real Car App Library `MessageTemplate`/`Screen`, with one or two host-rendered actions:
  Speak, Finish, Cancel or Clear. No keyboard, scrolling transcript history, arbitrary
  command execution, external intents, calls or speech playback. Result text is capped
  at 160 characters. Template use alone is not driver-distraction approval.
- Explicit Speak is the only capture entry. Permission, installed Parakeet model and
  host API level are checked first. Missing setup tells the user to use the phone only
  when parked. A new/reconnected screen never starts capture or inference.
- The unregistered host adapter uses `CarAudioRecord` and exclusive transient audio
  focus. There is no phone-mic fallback. It stops on Finish, Cancel, any audio focus
  loss, pause/stop/destruction, host dismissal, read failure or a 15-second limit.
- In-memory 16 kHz mono PCM16 is wrapped as WAV and passed to the existing `LocalAsr`.
  The existing dictionary/rules, optional `localPolish` setting, clean-up prompt,
  gate and validator protect Gemma polish. Model files, integrity checks, locks and
  idle-release ownership remain with `LocalAsr`/`LocalLlm`; the prototype does not
  download, delete, duplicate or unload them. This is transcript clean-up, not a new
  chat assistant or model-directed action executor.
- Offline-only processing. If Gemma is disabled, missing, fails, or produces a rejected
  polish, the rules transcript is shown with a short reason. No provider/service fallback.
  Missing Parakeet blocks capture. Audio and transcripts are not persisted, logged,
  inserted into other apps or added to the phone's recent dictations by this path.
- Cancellation discards the result and requests cancellation only for this request's
  Gemma conversation. Native ASR/model loading may finish privately; no replacement
  request is admitted until that work has returned. A 60-second processing deadline
  uses the same discard path. Old focus callbacks, timeouts and queued results cannot
  affect a later request. Reconnect never resumes a cancelled request.

## Build and preview

Use the SDK/JDK setup in [the Android guide](../android/README.md#build-and-install).
No model files, key, connected device, car or microphone are needed for these checks:

```bash
cd android
./gradlew --no-daemon testDebugUnitTest assembleDebug verifyCarReleaseIsolation
./gradlew --no-daemon lintDebug
```

`verifyCarReleaseIsolation` builds a release APK and rejects prototype/car-library
classes in its DEX files; CI runs it too. `assembleRelease` is unsigned without the separately configured signing settings.
The preview and car adapter live entirely under `src/debug`; tests live under
`src/testDebug`. The ordinary phone app's `src/main` behavior is unchanged.

On a separately authorized test device with a debug build already installed, launch
**GillSpeak car preview** from the phone launcher. It offers Ready, speech-model-missing,
permission-missing and old-host fixtures, plus Disconnect/Reconnect. Speak simulates
capture until Finish or 15 seconds. Cancel during capture or processing, switch away,
then return: the old text must never appear. These fixtures intentionally do not test
ASR quality or a physical microphone. No installs or hardware capture are performed by
the automated tests or as part of this implementation.

## Verification and remaining acceptance

JVM tests cover bounded WAV capture, explicit Finish and timeout, duplicate taps,
cancel-before-start, dismissal, audio-focus loss, silent/malformed/unavailable audio,
missing setup, cancelled ASR/Gemma, reconnect, stale callbacks and queued results.
Robolectric builds every real template state, constructs a `TestCarContext` screen,
and exercises the synthetic launcher's lifecycle. Production source/manifest isolation
is checked separately; release builds verify no debug class is required.

Full `lintDebug` currently reports two pre-existing `GestureBackNavigation` errors in
`ListenActivity` and `MainActivity`. They reproduce on clean main and are outside this
prototype's scope; this change does not suppress them or change phone navigation.

These are implementation checks, not real-car or model-quality acceptance. DHU/emulator
execution is not available in this environment, and a legitimate category/distribution
route remains unresolved. After resolving that product decision, use the official
[DHU testing guide](https://developer.android.com/training/cars/testing/dhu) for the
actual Android Auto projection route. Separately authorize and verify:

1. Trusted-host discovery/binding, launch, API negotiation and template rendering.
2. Real car PCM byte order/routing, permission denial/revocation, microphone dismissal,
   media interruption/focus restoration and reconnect while recording/processing.
3. Phone-local Parakeet/Gemma on representative arm64 hardware, with airplane mode,
   missing/deleted models, model load failure, memory pressure and native cancellation.
4. Locked phone, background/focus restrictions, USB/wireless drop, phone process death,
   host back/home, theme/screen sizes, text truncation and category quality review.

Until those gates are met, use the synthetic preview while parked; do not advertise
production Android Auto support or sideload this experiment for use while driving.
