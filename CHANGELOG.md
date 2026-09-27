# Changelog

All notable changes to Murmur. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- **Android voice keyboard (experimental)** in `android/`: hold or tap the mic, and Gemini transcribes and cleans the audio in one request with the `clean_v2` prompt. The rules and validator are ported to Kotlin, with the rules-cleaned transcript as the fallback. Audio leaves the device on Android; there is no on-device speech recognition yet. See [android/README.md](android/README.md).

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
