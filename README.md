# Murmur

[![CI](https://github.com/hkgill/gillspeak/actions/workflows/ci.yml/badge.svg)](https://github.com/hkgill/gillspeak/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Python 3.12+](https://img.shields.io/badge/python-3.12%2B-blue.svg)
![Fedora GNOME](https://img.shields.io/badge/platform-Fedora%20GNOME%20Wayland-294172.svg)

Hold a key, talk, and clean text lands at your cursor in any app.

- **Local speech-to-text**: NVIDIA Parakeet-TDT 0.6B v3 (int8) through sherpa-onnx, on the CPU. Audio never leaves the laptop.
- **Smart clean-up**: only the *text* goes to Gemini Flash-Lite, which applies self-corrections ("Thursday, sorry Friday"), strips fillers and fixes punctuation. Short, clean utterances skip the LLM completely.
- **Never loses words**: if the LLM is slow, offline or returns something odd, the rules-cleaned transcript is pasted instead.

- **Hold-to-talk**: hold Right Ctrl + Right Alt, speak, let go. Or tap Ctrl+Space to start and stop.

## Supported platforms

| Platform | Status |
|---|---|
| **Fedora Workstation, GNOME on Wayland** | ✅ Supported: one-script install, tested on real hardware and in CI |
| Other distros with GNOME on Wayland | ⚠️ Should work with the manual install; package names differ and shortcuts need adding by hand |
| KDE, Sway, Hyprland (Wayland) | ⚠️ Untested. Bind `murmur toggle` in your desktop's settings; hold-to-talk doesn't depend on the desktop |
| X11 sessions | ⚠️ Code path exists (`xclip`, `xdotool`) but is only tested with fakes |
| Debian / Ubuntu | ⚠️ Untested. May need `ydotool` 1.x; older 0.1.x releases use a different command syntax |
| Android 11+ | 🧪 Experimental voice keyboard in [`android/`](android/README.md); uses Gemini for speech recognition, so audio leaves the phone |
| macOS, Windows | ❌ Not supported |

Needs Python 3.12+, a microphone, and about 1 GB of RAM for the speech model. Reports from other setups are welcome: open an issue with your `murmur doctor` output.

## Install

```bash
git clone https://github.com/hkgill/gillspeak.git && cd gillspeak
scripts/setup-fedora.sh
```

The script:

1. installs `wl-clipboard ydotool libnotify pipewire-utils portaudio-devel uv`;
2. sets up `ydotoold` with a socket your user owns (`/run/ydotoold/socket`) and tests it;
3. installs murmur with `uv tool install` (puts `murmur` and `murmurd` in `~/.local/bin`);
4. downloads the Parakeet and Silero VAD models (SHA256-checked);
5. installs `murmur-keyd`, the hold-to-talk helper (root service; see below);
6. installs and starts the `murmurd` systemd user service;
7. adds GNOME shortcuts, warning you if something already uses Ctrl+Space (IBus often does);
8. asks for your Gemini API key and runs `murmur doctor`.

Manual install: `uv tool install --python 3.12 .`, then `murmur download-models`, copy `systemd/murmurd.service` to `~/.config/systemd/user/` and `systemctl --user enable --now murmurd`. For hold-to-talk, run `scripts/install-keyd.sh`.

**Update:** `git pull && scripts/setup-fedora.sh --no-models`. `murmur doctor` warns if the hold-to-talk helper is older than your install.

**Uninstall:** `scripts/uninstall.sh` removes the services, shortcuts and command but keeps your config, history and models; add `--purge` to remove those and the stored API key too.

### Gemini API key (the free tier works)

Murmur only needs a Gemini API key for the clean-up step; speech recognition is local and free. Without a key it still works, pasting the rules-cleaned transcript.

1. Sign in at [Google AI Studio](https://aistudio.google.com/apikey) with a Google account and click **Create API key**. No credit card is needed for the free tier.
2. Store it and tell the daemon:

   ```bash
   murmur set-key        # paste the key, then Enter; stored in the GNOME keyring (libsecret)
   murmur reload
   murmur doctor         # checks the key and lists the Flash-Lite model IDs your key can use
   ```

   If no keyring is available, the key goes to `~/.config/murmur/env` (mode 0600). murmurd reads it on every `murmur reload`, so a rotated key takes effect without a restart.
3. The default model is `gemini-3.5-flash-lite` (56/58 = 96.6% on the eval set with the `clean_v2` prompt, ~1 s median). If `murmur doctor` says your key can't use it, pick another ID from its list and set `model` under `[llm]` in `~/.config/murmur/config.toml`. Prefer versioned IDs: aliases such as `gemini-flash-lite-latest` can change underneath you.

**Is the free tier enough?** Usually. Google shows your project's exact limits in AI Studio (Projects → rate limits); for Flash-Lite they have been around 30 requests a minute and a few hundred a day. Murmur uses far less than you'd expect:

- short, clean dictations (under 12 words, no "sorry"/"I mean", no lists) never call Gemini;
- one dictation is one request, with no retries;
- if you hit a limit (HTTP 429), Murmur pastes the rules-cleaned text, pauses Gemini for 60 s, and shows a notification. Nothing is lost.

`murmur stats` shows how many dictations used the LLM. `murmur eval` sends one request per test case, spaced 4 s apart (`--delay`), and stops after a second 429; use `--limit 10` to spend fewer requests.

**Privacy on the free tier.** Google may use free-tier prompts and responses to improve its products, and humans may review them. Your audio never leaves the laptop, but the transcript text does, and dictations often include private messages. Fine for trying Murmur out; for daily use, enable billing on the project (Flash-Lite costs a fraction of a cent per dictation), or set `llm.provider = "none"` to keep everything local. Either way, use a **dedicated** key restricted to the Generative Language API, and set a budget alert if billing is on.

## Use

| Shortcut | Command | What it does |
|---|---|---|
| Hold Right Ctrl + Right Alt | | Hold to talk; release to paste (needs `murmur-keyd`, below) |
| Ctrl+Space | `murmur toggle` | Start/stop dictation |
| Ctrl+Alt+Space | `murmur toggle --raw` | Dictate without the LLM |
| Ctrl+Shift+Space | `murmur toggle --mode formal` | Dictate in a formal tone |
| Ctrl+Alt+Esc | `murmur cancel` | Discard the current recording |
| | `murmur paste-last` | Paste the last result again |
| | `murmur history [-n 20] [--diff]` | Raw vs cleaned text |
| | `murmur stats [--days 30]` | Words, LLM %, p50/p90 latency, cost |
| | `murmur status` / `murmur reload` | Daemon state / reload config and dictionary |
| | `murmur doctor` | Check the setup, with a fix hint for each problem |
| | `murmur bench clip.wav …` | ASR load time, RTF, latency, RSS (and WER if `clip.txt` exists) |
| | `murmur eval [--limit N] [--delay 4]` | Run the bundled LLM evaluation set (target ≥ 95%); requests are spaced out and a 429 waits once, then stops |

Spoken commands: "new line", "new paragraph", "bullet point", "question mark", and "full stop"/"period" at the end of an utterance. Turn any of them off with `rules.disabled_commands`.

**Hold-to-talk** comes from `murmur-keyd`, a small root service (`scripts/install-keyd.sh`, also run by the setup script). GNOME shortcuts can't bind modifier keys on their own, so it reads the keyboards directly, and it is the only process that does. It sends murmurd only events about that chord, over a socket only you can open: start (both keys held alone for 0.3 s), end (released), cancel (a third key joined, as in Ctrl+Alt+T, so the recording is discarded), and down/up (the chord is physically held/released: a paste waits for "up", so it never lands as Ctrl+Alt+V). No other key is ever reported. A quick tap does nothing. Root runs a root-owned copy in `/usr/local/libexec`, not your tool install. Turn it off with `hotkey.hold_to_talk = false` and `murmur reload`; remove it with `scripts/install-keyd.sh --uninstall`.

**Terminals** paste with Ctrl+Shift+V. Either add Ctrl+V as a paste shortcut in Ptyxis/GNOME Terminal (recommended), or bind another shortcut to `murmur toggle --chord ctrl+shift+v`.

## Configure

- `~/.config/murmur/config.toml`: every option, with comments. It is created on first run; see `DEFAULT_CONFIG_TOML` in `murmur/config.py`.
- `~/.config/murmur/dictionary.toml`: spoken → written replacements, and `bias` terms that go to ASR hotwords and to the LLM as preferred spellings:

```toml
[replace]
"super base" = "Supabase"
"cube control" = "kubectl"

[bias]
terms = ["Supabase", "Fedora", "Parakeet"]
```

Changes to `[audio]`, `[asr]` or the bias terms need `systemctl --user restart murmurd`. Everything else applies after `murmur reload`.

## How it works

```
murmur toggle ──JSON over $XDG_RUNTIME_DIR/murmur.sock──► murmurd
  Recorder (300 ms pre-roll) → Silero VAD trim → Parakeet (warm) → rules
  → gate → Gemini Flash-Lite (2.5 s budget, no retries) → validator
  → wl-copy + ydotool Ctrl+V → SQLite history
```

- **CLI** (`murmur/cli.py`) imports only the standard library, so a hotkey reaches the daemon in well under 100 ms.
- **Rules** (`rules.py`) remove fillers and stutters, apply spoken commands and the dictionary, and fix spacing.
- **Gate** (`gate.py`) calls the LLM only for ≥ 12 words, corrections ("sorry", "I mean", "scratch that"…), lists, non-default modes, or `llm.always`.
- **Validator** (`validate.py`) rejects empty output, output that is too short or too long, preambles ("Sure, here's…"), and output where a number from the input has gone missing.
- **Injector** (`inject.py`) passes text to `wl-copy` through stdin (never argv), sends the paste chord with ydotool keycodes, then puts your previous clipboard back, unless you copied something else in the meantime. If ydotool is down, the text stays on the clipboard and a notification tells you to press Ctrl+V.
- **History** (`~/.local/share/murmur/history.db`) keeps text for `history.keep_days` (0 = metrics only). Audio is never written to disk unless `debug.save_audio = true`.

## Develop

```bash
uv venv -p 3.12 && uv pip install -e '.[dev]'
.venv/bin/pytest
```

The tests mock the audio stream, subprocesses and HTTP, so they need neither models nor a desktop session. `MURMUR_TEST_MODELS=~/.local/share/murmur/models pytest tests/test_models_integration.py` also runs the real Silero and Parakeet models.

`murmur/prompts/clean_v2.txt` holds the system prompt. If you change it, add `clean_v3.txt`, bump `PROMPT_VERSION` in `cleaner.py` (history records which version was used), and re-run `murmur eval`.

## Contributing

Issues and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for setup, the test rules (every bug fix needs a regression test) and prompt changes, and [SECURITY.md](SECURITY.md) to report vulnerabilities privately. Changes are listed in [CHANGELOG.md](CHANGELOG.md). Everyone is expected to follow the [code of conduct](CODE_OF_CONDUCT.md).

## License

[MIT](LICENSE)
