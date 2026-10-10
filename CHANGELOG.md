# Changelog

All notable changes to gillspeak (called Murmur up to 0.5.1). The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Changed
- **Murmur is now gillspeak, everywhere.** The commands are `gillspeak`, `gillspeakd` and `gillspeak-keyd`, the services `gillspeakd.service` and `gillspeak-keyd.service`, and the folders `~/.config/gillspeak`, `~/.local/share/gillspeak` and `~/.cache/gillspeak`. **Upgrading keeps everything:** on first run gillspeak moves the old `murmur` folders over (config, dictionary, history, models) and copies the API key from the old keyring entry. Re-run `scripts/setup-fedora.sh`: it removes the old service, command, shortcuts, ydotool drop-in and hold-to-talk helper (`scripts/remove-legacy-murmur.sh`) before installing the new ones. The Android app's package id is now `dev.hkgill.gillspeak`, so it installs as a new app.

- **Local only by default: nothing leaves your computer or phone.** Desktop: `llm.provider` now defaults to `"none"` (speech recognition and rule clean-up are local; Gemini clean-up is opt-in, sends transcript text only, and the setup script asks before enabling it). `gillspeak eval --provider gemini` scores Gemini without turning it on. Android: the Local engine is the default and listed first; Gemini and Groq are labelled as optional cloud engines that send audio. Existing configs keep the provider they set.

### Added
- **Android voice keyboard (experimental)** in `android/`: hold or tap the mic, and Gemini transcribes and cleans the audio in one request with the `clean_v2` prompt. The rules and validator are ported to Kotlin, with the rules-cleaned transcript as the fallback. Audio leaves the device on Android; there is no on-device speech recognition yet. A floating "G" bubble works over any keyboard (accessibility service): hold or tap to dictate, a red pill with a live waveform while recording, draggable and resizable. See [android/README.md](android/README.md).
- **Android voice commands from the side button.** Choose gillspeak as the digital assistant app and holding the side button opens it over whatever is on screen: the screen dims, a teal wave runs out of the side button round the edge, and the bubble listens until you stop talking. It opens apps, sets timers and alarms, turns the torch on or off, controls media, gets directions and searches the web; texts and calls open Messages or Phone filled in for you to send. Commands are matched by rules on the phone (only a fixed list ever runs), with whichever speech engine you chose. See "Voice commands" in [android/README.md](android/README.md).
- **Signed Android release APKs.** `assembleRelease` signs with a keystore configured in the git-ignored `local.properties`; APKs are attached to GitHub releases, never committed. See "Release a signed APK" in [android/README.md](android/README.md).

### Removed
- **Android: no API key is built into the app.** `gemini.apiKey` and `groq.apiKey` in `local.properties` are no longer read; Gemini and Groq use only the key you paste into the app.

## [0.5.1] - 2026-09-27

Fixes from an independent review (Codex, `gpt-6-astra`, high effort). Every fix has a regression test that fails on 0.5.0.

### Fixed
- **Pastes could vanish during hold-to-talk.** A dictation finishing while Right Ctrl + Right Alt were held (recording the next one, or after the 5-minute auto-stop) pasted as Ctrl+Alt+V. `murmur-keyd` now also reports when the chord is physically down/up, and murmurd holds pastes until it's released. **Re-run `scripts/install-keyd.sh`** (`murmur doctor` reminds you).
- **Truncated clean-up was pasted as if complete.** Gemini output that hit its token limit (`MAX_TOKENS`) is now treated as a failure, and the full rules-cleaned transcript is pasted instead.
- **The number check accepted changed values.** "250" counted as present in "1250"; numbers must now match as whole tokens (spaced digits and times like "6 45" → "6:45" are still accepted).
- **Dictated numbers were stored in history metadata and logs** (`rejected:missing_number:0412…`), surviving `keep_days = 0`. Reasons are now value-free codes, and existing rows are scrubbed on the next purge.
- **History retention only ran at startup or after a dictation.** It now runs hourly, and immediately on `murmur reload`.
- **`hold_to_talk = false` + `murmur reload` didn't turn hold-to-talk off** (nor did changing `keyd_socket`). Reload now starts, stops or re-points the listener.
- **A rotated API key in `~/.config/murmur/env` was ignored until restart**: the service imported the file into its environment at startup. The unit no longer does (murmurd reads the file itself); re-run the setup script, or delete the `EnvironmentFile=` line from `~/.config/systemd/user/murmurd.service`.
- **Overlapping pastes** (`murmur paste-last` while a dictation finished) could paste the wrong text and corrupt clipboard restore. Pastes are now serialized.
- **An unexpected error in clean-up lost the whole dictation**; it now falls back to the rules text like every other failure. Malformed proxy responses are reported as clean-up errors.
- **A microphone that failed to start leaked its audio stream** on every retry.

## [0.5.0] - 2026-09-27

### Added
- Hold-to-talk on **Right Ctrl + Right Alt**: hold to record, release to paste; a third key (Ctrl+Alt+T…) cancels. Powered by `murmur-keyd`, a small root helper that reads the keyboards and passes murmurd only start/end/cancel events over a private socket. Installed by `scripts/install-keyd.sh` (also run by the setup script); configured under `[hotkey]`.
- `murmur doctor` checks the hold-to-talk helper, warns when its root-owned copy is older than the installed package, and warns when `/dev/input` devices are readable by every user.
- `murmur eval --delay` (default 4 s) spaces requests for free-tier keys; on HTTP 429 it waits 30 s and retries once, then stops instead of sending the rest.
- `scripts/uninstall.sh` (`--purge` also removes config, history, models and the stored key).
- `scripts/ptt-portal-test.py`, a diagnostic for GNOME's GlobalShortcuts portal press/release signals.
- MIT license, CI (lint, Python 3.12–3.14, Fedora container), contributing guide, security policy, issue templates.

### Changed
- Clean-up prompt `clean_v2`: explicit self-correction markers ("let me rephrase", "make that"…), number formatting ($4,250, 15,000, 9:30; small counts stay as words), proper-noun and acronym capitalisation, and preferred spellings matched case-insensitively. Eval pass rate with `gemini-3.5-flash-lite`: 84.5% (v1) → 96.6% (v2), above the 95% bar.
- Rejected LLM output and empty transcripts are logged without their text; the text is at DEBUG level only.
- Default model is `gemini-3.5-flash-lite` (was the `gemini-flash-lite-latest` alias).
- `audio.keep_mic_open` defaults to `false`: the microphone is only open while dictating.
- The eval set ships inside the package, so `murmur eval` works from an installed tool.

### Fixed
- Hold-to-talk pasted nothing when one key was released before the other (the paste arrived as Ctrl+Alt+V).
- `murmur-keyd` could spin at 100% CPU if a device read returned end-of-file.
- The Gemini connection wasn't pre-warmed during the first five minutes after boot (a never-used cleaner looked recently used), so the first dictation paid for the TLS handshake.
- Notification sounds could be garbage-collected before they finished playing.
- Empty transcripts and hold events are now logged instead of disappearing silently.
- Three eval cases could not be passed by a correct answer (case-sensitive "milk"/"summarise" at the start of a line; "Parakeet model" forbidden case-insensitively). `must_contain` entries may now list alternatives.

## [0.4.0] - 2026-09-26

### Added
- First release: `murmurd` daemon with warm Parakeet-TDT 0.6B v3 ASR (sherpa-onnx), Silero VAD trimming, rules clean-up, LLM gate, Gemini Flash-Lite clean-up with validator and rules fallback, Wayland/X11 injection with clipboard restore, SQLite history, `murmur` CLI, SHA256-pinned model downloads, Fedora setup script.

[0.5.1]: https://github.com/hkgill/gillspeak/compare/v0.5.0...v0.5.1
[0.5.0]: https://github.com/hkgill/gillspeak/compare/17bd954...v0.5.0
[0.4.0]: https://github.com/hkgill/gillspeak/commit/17bd954
