# Whistle on the watch: a test

Can a Galaxy Watch run Whistle, the 16.9 MB speech model the phone app uses, on the watch itself? If it can, you could speak to the watch, for example to answer an agent that needs you, without the audio leaving it. `android/whistle-probe` is a small Wear OS app that finds out. It isn't meant for everyday use.

## What could stop it

1. **Only 64-bit ARM.** Cactus ships Whistle's engine for `arm64-v8a` only. Some watches run only 32-bit apps. On those, the probe won't even install (`INSTALL_FAILED_NO_MATCHING_ABIS`), and that is the answer.
2. **CPU instructions.** Watch chips use small cores (the Galaxy Watch6's Exynos W930 has Cortex-A55s). If the engine uses instructions they lack, it crashes the moment it loads. The probe notices on its next launch and says so.
3. **Speed.** Nobody has published Whistle timings on a watch. The probe measures them.

## Run it

```bash
cd android
./gradlew :whistle-probe:assembleDebug
adb devices -l                                   # make sure you pick the watch
adb -s WATCH_SERIAL install -r whistle-probe/build/outputs/apk/debug/whistle-probe-debug.apk
adb -s WATCH_SERIAL logcat -s whistle-probe      # every result is also logged here
```

On the watch, open **Whistle test**:

- The first lines show the watch's ABIs, CPU cores and which extensions it has (dot product, FP16 maths, int8 matrix, SVE), and its RAM.
- **Download model** fetches the same pinned file as the phone app (16.9 MB) and checks its SHA256. A copy you push yourself is checked the same way before Whistle loads it. Use Wi-Fi: over Bluetooth it's slow. Or copy it from your computer:
  ```bash
  adb -s WATCH_SERIAL push whistle.cact /data/local/tmp/
  adb -s WATCH_SERIAL shell run-as dev.hkgill.gillspeak.whistleprobe cp /data/local/tmp/whistle.cact files/
  ```
- **Benchmark, 5 s** loads Whistle and transcribes five seconds of near-silence. It shows how long loading took, how long transcribing took, and the real-time factor (RTF: 0.5 means twice as fast as the audio). The text is meaningless.
- **Record 5 s, transcribe** asks for the microphone, records five seconds and shows what Whistle heard, with the same timings. The audio stays in memory and is never saved or sent.

## Reading the results

| You see | It means |
|---|---|
| The APK won't install: `INSTALL_FAILED_NO_MATCHING_ABIS` | The watch runs only 32-bit apps. Whistle can't run on it until Cactus ships a 32-bit build. |
| "Last time the app died while running Whistle" | The engine probably uses CPU instructions the watch lacks. Send the CPU lines and `adb logcat` output. |
| `Failed: UnsatisfiedLinkError` | The native library didn't load. |
| RTF well under 1 and the right words | It works. Next: a voice reply on the watch. |
| RTF over 1 | It runs, but slower than you speak. It might still do for short replies. |
