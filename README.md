<p align="center"><img src="docs/assets/icon.png" width="96" height="96" alt="gillspeak mark: a speech bubble holding a g"></p>

# gillspeak

[![CI](https://github.com/hkgill/gillspeak/actions/workflows/ci.yml/badge.svg)](https://github.com/hkgill/gillspeak/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Python 3.12+](https://img.shields.io/badge/python-3.12%2B-blue.svg)
![Fedora GNOME](https://img.shields.io/badge/platform-Fedora%20GNOME%20Wayland-294172.svg)

Hold a key, talk, and clean text lands at your cursor in any app. **Local only: nothing leaves your computer.**

- **Private by default**: speech recognition (NVIDIA Parakeet-TDT 0.6B v3, int8, through sherpa-onnx) and clean-up both run on your CPU. No account, no API key, no network needed.
- **Clean text**: rules remove fillers and stutters, apply spoken commands ("new line", "bullet point", "question mark") and your dictionary. Short, clean dictations come out ready to send.
- **Hold-to-talk**: hold Right Ctrl + Right Alt, speak, let go. Or tap Ctrl+Space to start and stop.
- **Optional cloud polish**: if you choose to, Gemini Flash-Lite can also handle self-corrections ("Thursday, sorry Friday") and number formatting. It's off unless you turn it on, and even then only transcript *text* is sent, never audio. See [Optional: cloud clean-up with Gemini](#optional-cloud-clean-up-with-gemini).
- **Never loses words**: if the optional cloud step is slow, offline or returns something odd, the rules-cleaned text is pasted instead.

## Supported platforms

| Platform | Status |
|---|---|
| **Fedora Workstation, GNOME on Wayland** | ✅ Supported: one-script install, tested on real hardware and in CI |
| Other distros with GNOME on Wayland | ⚠️ Should work with the manual install; package names differ and shortcuts need adding by hand |
| KDE, Sway, Hyprland (Wayland) | ⚠️ Untested. Bind `gillspeak toggle` in your desktop's settings; hold-to-talk doesn't depend on the desktop |
| X11 sessions | ⚠️ Code path exists (`xclip`, `xdotool`) but is only tested with fakes |
| Debian / Ubuntu | ⚠️ Untested. May need `ydotool` 1.x; older 0.1.x releases use a different command syntax |
| Android 11+ | 🧪 Experimental companion app in [`android/`](android/README.md): a floating dictation bubble over any keyboard, local only by default |
| macOS, Windows | ❌ Not supported |

<p align="center"><img src="docs/assets/bubble-states.png" width="600" alt="the Android bubble's states: idle, listening, transcribing, retry"></p>

Needs Python 3.12+, a microphone, and about 1 GB of RAM for the speech model. Reports from other setups are welcome: open an issue with your `gillspeak doctor` output.

## Install

```bash
git clone https://github.com/hkgill/gillspeak.git && cd gillspeak
scripts/setup-fedora.sh
```

The script:

1. installs `wl-clipboard ydotool libnotify pipewire-utils portaudio-devel uv`;
2. sets up `ydotoold` with a socket your user owns (`/run/ydotoold/socket`) and tests it;
3. installs gillspeak with `uv tool install` (puts `gillspeak` and `gillspeakd` in `~/.local/bin`);
4. downloads the Parakeet and Silero VAD models (SHA256-checked);
5. installs `gillspeak-keyd`, the hold-to-talk helper (root service; see below);
6. installs and starts the `gillspeakd` systemd user service;
7. adds GNOME shortcuts, warning you if something already uses Ctrl+Space (IBus often does);
8. offers the optional Gemini clean-up (off unless you say yes) and runs `gillspeak doctor`.

Upgrading from Murmur (the old name)? Run the script again: it removes the old service, command and shortcuts, and gillspeak moves your config, dictionary, history, models and stored key over on first run.

Manual install: `uv tool install --python 3.12 .`, then `gillspeak download-models`, copy `systemd/gillspeakd.service` to `~/.config/systemd/user/` and `systemctl --user enable --now gillspeakd`. For hold-to-talk, run `scripts/install-keyd.sh`.

**Update:** `git pull && scripts/setup-fedora.sh --no-models`. `gillspeak doctor` warns if the hold-to-talk helper is older than your install.

**Uninstall:** `scripts/uninstall.sh` removes the services, shortcuts and command but keeps your config, history and models; add `--purge` to remove those and the stored API key too.

### Optional: cloud clean-up with Gemini

gillspeak works fully offline and needs no key. If you want AI polish on longer or corrected dictations (self-corrections, "$4,250", grammar), you can opt in to Gemini Flash-Lite: gillspeak then sends the transcript **text** (never audio) of those dictations to Google. Set `provider = "gemini"` under `[llm]` in `~/.config/gillspeak/config.toml` (the setup script can do it for you) and store a key. The free tier works.

1. Sign in at [Google AI Studio](https://aistudio.google.com/apikey) with a Google account and click **Create API key**. No credit card is needed for the free tier.
2. Store it and tell the daemon:

   ```bash
   gillspeak set-key        # paste the key, then Enter; stored in the GNOME keyring (libsecret)
   gillspeak reload
   gillspeak doctor         # checks the key and lists the Flash-Lite model IDs your key can use
   ```

   If no keyring is available, the key goes to `~/.config/gillspeak/env` (mode 0600). gillspeakd reads it on every `gillspeak reload`, so a rotated key takes effect without a restart.
3. The default model is `gemini-3.5-flash-lite` (56/58 = 96.6% on the eval set with the `clean_v2` prompt, ~1 s median). If `gillspeak doctor` says your key can't use it, pick another ID from its list and set `model` under `[llm]` in `~/.config/gillspeak/config.toml`. Prefer versioned IDs: aliases such as `gemini-flash-lite-latest` can change underneath you.

**Is the free tier enough?** Usually. Google shows your project's exact limits in AI Studio (Projects → rate limits); for Flash-Lite they have been around 30 requests a minute and a few hundred a day. gillspeak uses far less than you'd expect:

- short, clean dictations (under 12 words, no "sorry"/"I mean", no lists) never call Gemini;
- one dictation is one request, with no retries;
- if you hit a limit (HTTP 429), gillspeak pastes the rules-cleaned text, pauses Gemini for 60 s, and shows a notification. Nothing is lost.

`gillspeak stats` shows how many dictations used the LLM. `gillspeak eval --provider gemini` scores the clean-up on the bundled eval set; it sends one request per test case, spaced 4 s apart (`--delay`), and stops after a second 429; use `--limit 10` to spend fewer requests.

**Privacy on the free tier.** Google may use free-tier prompts and responses to improve its products, and humans may review them. Your audio never leaves the laptop, but the transcript text does, and dictations often include private messages. For daily use with private messages, enable billing on the project (Flash-Lite costs a fraction of a cent per dictation), or keep the default `llm.provider = "none"`, where nothing leaves your computer. Either way, use a **dedicated** key restricted to the Generative Language API, and set a budget alert if billing is on.

## Use

| Shortcut | Command | What it does |
|---|---|---|
| Hold Right Ctrl + Right Alt | | Hold to talk; release to paste (needs `gillspeak-keyd`, below) |
| Ctrl+Space | `gillspeak toggle` | Start/stop dictation |
| Ctrl+Alt+Space | `gillspeak toggle --raw` | Dictate without the LLM |
| Ctrl+Shift+Space | `gillspeak toggle --mode formal` | Dictate in a formal tone |
| Ctrl+Alt+Esc | `gillspeak cancel` | Discard the current recording |
| | `gillspeak paste-last` | Paste the last result again |
| | `gillspeak history [-n 20] [--diff]` | Raw vs cleaned text |
| | `gillspeak stats [--days 30]` | Words, LLM %, p50/p90 latency, cost |
| | `gillspeak status` / `gillspeak reload` | Daemon state / reload config and dictionary |
| | `gillspeak doctor` | Check the setup, with a fix hint for each problem |
| | `gillspeak bench clip.wav …` | ASR load time, RTF, latency, RSS (and WER if `clip.txt` exists) |
| | `gillspeak eval [--limit N] [--delay 4]` | Run the bundled LLM evaluation set (target ≥ 95%); requests are spaced out and a 429 waits once, then stops |

Spoken commands: "new line", "new paragraph", "bullet point", "question mark", and "full stop"/"period" at the end of an utterance. Turn any of them off with `rules.disabled_commands`.

**Hold-to-talk** comes from `gillspeak-keyd`, a small root service (`scripts/install-keyd.sh`, also run by the setup script). GNOME shortcuts can't bind modifier keys on their own, so it reads the keyboards directly, and it is the only process that does. It sends gillspeakd only events about that chord, over a socket only you can open: start (both keys held alone for 0.3 s), end (released), cancel (a third key joined, as in Ctrl+Alt+T, so the recording is discarded), and down/up (the chord is physically held/released: a paste waits for "up", so it never lands as Ctrl+Alt+V). No other key is ever reported. A quick tap does nothing. Root runs a root-owned copy in `/usr/local/libexec`, not your tool install. Turn it off with `hotkey.hold_to_talk = false` and `gillspeak reload`; remove it with `scripts/install-keyd.sh --uninstall`.

**Terminals** paste with Ctrl+Shift+V. Either add Ctrl+V as a paste shortcut in Ptyxis/GNOME Terminal (recommended), or bind another shortcut to `gillspeak toggle --chord ctrl+shift+v`.

## Configure

- `~/.config/gillspeak/config.toml`: every option, with comments. It is created on first run; see `DEFAULT_CONFIG_TOML` in `gillspeak/config.py`.
- `~/.config/gillspeak/dictionary.toml`: spoken → written replacements, and `bias` terms that go to ASR hotwords and to the LLM as preferred spellings:

```toml
[replace]
"super base" = "Supabase"
"cube control" = "kubectl"

[bias]
terms = ["Supabase", "Fedora", "Parakeet"]
```

Changes to `[audio]`, `[asr]` or the bias terms need `systemctl --user restart gillspeakd`. Everything else applies after `gillspeak reload`.

## How it works

```
gillspeak toggle ──JSON over $XDG_RUNTIME_DIR/gillspeak.sock──► gillspeakd
  Recorder (300 ms pre-roll) → Silero VAD trim → Parakeet (warm) → rules
  → [optional, off by default: gate → Gemini Flash-Lite (2.5 s budget) → validator]
  → wl-copy + ydotool Ctrl+V → SQLite history
```

Everything on the default path runs on your computer. The bracketed step only runs if you set `llm.provider = "gemini"`.

- **CLI** (`gillspeak/cli.py`) imports only the standard library, so a hotkey reaches the daemon in well under 100 ms.
- **Rules** (`rules.py`) remove fillers and stutters, apply spoken commands and the dictionary, and fix spacing.
- **Gate** (`gate.py`), when cloud clean-up is on, calls the LLM only for ≥ 12 words, corrections ("sorry", "I mean", "scratch that"…), lists, non-default modes, or `llm.always`.
- **Validator** (`validate.py`) rejects empty output, output that is too short or too long, preambles ("Sure, here's…"), and output where a number from the input has gone missing.
- **Injector** (`inject.py`) passes text to `wl-copy` through stdin (never argv), sends the paste chord with ydotool keycodes, then puts your previous clipboard back, unless you copied something else in the meantime. If ydotool is down, the text stays on the clipboard and a notification tells you to press Ctrl+V.
- **History** (`~/.local/share/gillspeak/history.db`) keeps text for `history.keep_days` (0 = metrics only). Audio is never written to disk unless `debug.save_audio = true`.

## Develop

```bash
uv venv -p 3.12 && uv pip install -e '.[dev]'
.venv/bin/pytest
```

The tests mock the audio stream, subprocesses and HTTP, so they need neither models nor a desktop session. `GILLSPEAK_TEST_MODELS=~/.local/share/gillspeak/models pytest tests/test_models_integration.py` also runs the real Silero and Parakeet models.

`gillspeak/prompts/clean_v2.txt` holds the system prompt. If you change it, add `clean_v3.txt`, bump `PROMPT_VERSION` in `cleaner.py` (history records which version was used), and re-run `gillspeak eval`.

## Contributing

Issues and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for setup, the test rules (every bug fix needs a regression test) and prompt changes, and [SECURITY.md](SECURITY.md) to report vulnerabilities privately. Changes are listed in [CHANGELOG.md](CHANGELOG.md). Everyone is expected to follow the [code of conduct](CODE_OF_CONDUCT.md).

## License

[MIT](LICENSE)
