# Murmur

Press a hotkey, talk, and clean text lands at your cursor in any app.

- **Local speech-to-text**: NVIDIA Parakeet-TDT 0.6B v3 (int8) through sherpa-onnx, on the CPU. Audio never leaves the laptop.
- **Smart clean-up**: only the *text* goes to Gemini Flash-Lite, which applies self-corrections ("Thursday, sorry Friday"), strips fillers and fixes punctuation. Short, clean utterances skip the LLM completely.
- **Never loses words**: if the LLM is slow, offline or returns something odd, the rules-cleaned transcript is pasted instead.

Target: Fedora Workstation (GNOME on Wayland). X11 works through xclip/xdotool.

## Install

```bash
git clone … && cd gillspeak
scripts/setup-fedora.sh
```

The script:

1. installs `wl-clipboard ydotool libnotify pipewire-utils portaudio-devel uv`;
2. sets up `ydotoold` with a socket your user owns (`/run/ydotoold/socket`) and tests it;
3. installs murmur with `uv tool install` (puts `murmur` and `murmurd` in `~/.local/bin`);
4. downloads the Parakeet and Silero VAD models (SHA256-checked);
5. installs and starts the `murmurd` systemd user service;
6. adds GNOME shortcuts, warning you if something already uses Ctrl+Space (IBus often does);
7. asks for your Gemini API key and runs `murmur doctor`.

Manual install: `uv tool install --python 3.12 .`, then `murmur download-models`, copy `systemd/murmurd.service` to `~/.config/systemd/user/` and `systemctl --user enable --now murmurd`.

### Gemini API key

```bash
murmur set-key        # reads from stdin, stores in the GNOME keyring (libsecret)
murmur reload
```

If no keyring is available, the key goes to `~/.config/murmur/env` (mode 0600), which the service loads with `EnvironmentFile=`.
Use a **dedicated** key restricted to the Generative Language API, and set a billing budget alert.
Free-tier prompts may be used by Google to improve its products, and your dictations include private messages, so use the paid tier once past prototyping.

Run `murmur doctor` to list the available Flash-Lite model IDs, then pin one in `llm.model`. Aliases such as `gemini-flash-lite-latest` can change underneath you.

## Use

| Shortcut | Command | What it does |
|---|---|---|
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

`murmur/prompts/clean_v1.txt` holds the system prompt. If you change it, add `clean_v2.txt`, bump `PROMPT_VERSION` in `cleaner.py` (history records which version was used), and re-run `murmur eval`.
