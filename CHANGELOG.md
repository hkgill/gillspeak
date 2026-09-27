# Changelog

All notable changes to Murmur. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/).

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
- Notification sounds could be garbage-collected before they finished playing.
- Empty transcripts and hold events are now logged instead of disappearing silently.
- Three eval cases could not be passed by a correct answer (case-sensitive "milk"/"summarise" at the start of a line; "Parakeet model" forbidden case-insensitively). `must_contain` entries may now list alternatives.

## [0.4.0] - 2026-09-26

### Added
- First release: `murmurd` daemon with warm Parakeet-TDT 0.6B v3 ASR (sherpa-onnx), Silero VAD trimming, rules clean-up, LLM gate, Gemini Flash-Lite clean-up with validator and rules fallback, Wayland/X11 injection with clipboard restore, SQLite history, `murmur` CLI, SHA256-pinned model downloads, Fedora setup script.

[0.5.0]: https://github.com/hkgill/gillspeak/compare/17bd954...v0.5.0
[0.4.0]: https://github.com/hkgill/gillspeak/commit/17bd954
