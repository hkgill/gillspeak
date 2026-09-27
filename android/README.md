# Murmur for Android (experimental)

A voice keyboard for Android: hold the mic, talk, let go, and clean text lands in the focused field.

- **Hold** the mic to talk and release to insert, or **tap** it to latch and tap again to finish.
- One Gemini request transcribes the audio *and* cleans it with the desktop `clean_v2` prompt. Unlike the desktop app, **audio leaves the device**; there is no on-device speech recognition yet.
- The desktop rules (`rules.py`) and validator (`validate.py`) are ported to Kotlin. If Gemini's cleaned text fails validation, the rules-cleaned transcript is inserted instead.
- ⌨ goes back to your previous keyboard, ↶ removes the last dictation, and a failed request keeps the audio: tap the status line to retry.
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

Then open the app, allow the microphone, turn on **Murmur voice** in keyboard settings and switch to it. On Samsung phones, *Settings → General management → Keyboard list and default → Keyboard button on navigation bar* makes switching quicker.

## Develop

- `app/src/main/assets/clean_v2.txt` is a copy of `murmur/prompts/clean_v2.txt`; `PromptSyncTest` fails if they drift. `audio_preface.txt` adds the audio-specific instructions (return a verbatim `transcript` and a cleaned `text` as JSON).
- `RulesTest` holds cases copied from `tests/test_rules.py` and `tests/test_validate.py`. Android's regex engine is ICU, not the JVM's: don't use `(?U)` (ICU rejects it at load time, which JVM tests won't catch).
- Debug builds have a self-test that runs a WAV through the whole pipeline without speaking:

  ```bash
  adb push clip.wav /data/local/tmp/t.wav
  adb shell run-as dev.hkgill.murmur sh -c 'mkdir -p files && cp /data/local/tmp/t.wav files/t.wav'
  adb shell am start -S -n dev.hkgill.murmur/.MainActivity --es selftest t.wav
  adb logcat -s Murmur
  ```
