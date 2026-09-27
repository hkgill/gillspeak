<p align="center"><img src="../docs/assets/icon.png" width="96" height="96" alt="gillspeak mark: a speech bubble holding a g"></p>

# gillspeak for Android (experimental)

Dictation for Android: hold the mic, talk, let go, and clean text lands in the focused field. Two ways in:

<p align="center"><img src="../docs/assets/bubble-states.png" width="640" alt="the floating bubble's states: idle, listening, transcribing, retry"></p>

- **Floating bubble** (recommended): keep your usual keyboard; a small "G" appears over it whenever you type. Hold it to talk, or tap to start and tap again to finish. While recording it stretches into a red pill with a live waveform and timer. Drag it anywhere, including onto the keyboard; size and shape (square or a wide bar) are set in the app. It uses an accessibility service to see when a keyboard and a text field are on screen and to type into that field; it skips password fields.
- **gillspeak keyboard**: a full-screen mic keyboard, for when you'd rather switch keyboards.

- **Local only by default: nothing leaves your phone.** Speech recognition is Parakeet TDT 0.6B v3 on the phone (the desktop model, through sherpa-onnx), followed by the desktop clean-up rules. The ~640 MB model is downloaded once by Android's DownloadManager (Wi-Fi only, resumable) and each file is SHA256-checked. English and 24 other European languages.
- **Optional cloud engines**, chosen in the app: **Gemini** (sends the audio to Google; one request transcribes and cleans with the desktop `clean_v2` prompt; handles Punjabi and mixed languages) and **Groq** (sends the audio to Groq's Whisper, then a text-only clean-up when the gate says it's worth it). The desktop rules, gate and validator are ported to Kotlin; if a cleaned text fails validation, the rules-cleaned transcript is inserted instead.
- Keyboard: ⌨ goes back to your previous keyboard, ↶ removes the last dictation, and a failed request keeps the audio: tap the status line to retry.
- The app screen holds setup, the API key, model, dictionary and the last 30 dictations (kept only on the phone).

Tested on a Galaxy S25 and a Galaxy Z Fold5 (Android 16). Needs Android 11+.

## Build and install

Needs the Android SDK (platform 36) and a JDK 17–21. Android Studio's bundled JBR works; JDK 25 is too new for the Android Gradle plugin.

```bash
cd android
cat > local.properties <<EOF
sdk.dir=$HOME/Android/Sdk
gemini.apiKey=YOUR_KEY    # optional: baked into the APK; or paste it in the app instead
EOF
JAVA_HOME=/path/to/jdk21 ./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` is git-ignored. A key baked into the APK can be extracted by anyone who has the file, so don't share builds that contain one.

Wireless debugging (Android 11+): Developer options → Wireless debugging → Pair device with pairing code, then `adb pair IP:PAIRPORT CODE` and `adb connect IP:PORT` (the port on the main Wireless debugging screen).

Then open the app and allow the microphone. For the bubble, turn on **gillspeak bubble** under Settings → Accessibility → Installed apps (apps installed from a file rather than adb may first need *App info → ⋮ → Allow restricted settings*). For the keyboard, turn on **gillspeak keyboard** in keyboard settings and switch to it. On Samsung phones, *Settings → General management → Keyboard list and default → Keyboard button on navigation bar* makes switching quicker.

## Develop

- `app/src/main/assets/clean_v2.txt` is a copy of `gillspeak/prompts/clean_v2.txt`; `PromptSyncTest` fails if they drift. `audio_preface.txt` adds the audio-specific instructions (return a verbatim `transcript` and a cleaned `text` as JSON).
- `RulesTest` holds cases copied from `tests/test_rules.py` and `tests/test_validate.py`. Android's regex engine is ICU, not the JVM's: don't use `(?U)` (ICU rejects it at load time, which JVM tests won't catch).
- `MicController` is the mic state machine (hold, latch, retry) shared by the keyboard and the bubble. The bubble waits 150 ms before starting the mic so a drag never starts a recording, and refuses to send all-silent audio (what Android returns when it blocks background recording).
- Logs: `adb logcat -s gillspeak` shows why the bubble is or isn't shown, where each Gemini request spent its time (upload, wait, thinking tokens), and how text was inserted.
- Debug builds have test hooks, reachable from adb only (a debug-only receiver that requires `DUMP`):

  ```bash
  adb push clip.wav /data/local/tmp/t.wav
  adb shell run-as dev.hkgill.gillspeak sh -c 'mkdir -p files && cp /data/local/tmp/t.wav files/t.wav'
  adb shell am broadcast -n dev.hkgill.gillspeak/.DebugReceiver --es selftest t.wav   # optional: --es engine local|groq|gemini
  adb shell am broadcast -n dev.hkgill.gillspeak/.DebugReceiver --ez download_model true
  adb logcat -s gillspeak
  ```
