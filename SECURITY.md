# Security policy

## Reporting a vulnerability

Please **don't open a public issue**. Use GitHub's private reporting: **Security → Report a vulnerability** on <https://github.com/hkgill/gillspeak>. Expect an acknowledgement within a week. Fixes go into the next release, with credit if you want it.

## Supported versions

Only the latest release on `main` gets security fixes.

## What's sensitive in Murmur

Reports in these areas are especially welcome:

- **`murmur-keyd`** runs as root and reads every keyboard. It must only ever emit `start`/`end`/`cancel`, its socket must stay `0600` and owned by the configured user, and root must only run the root-owned copy in `/usr/local/libexec`, never code from a user-writable path. The systemd unit drops all capabilities except `CAP_CHOWN`, has no network, and mounts the system read-only.
- **Keystroke injection** (`ydotoold`): its socket is `0600` and owned by you. Text is passed to `wl-copy`/`ydotool` on stdin, never on the command line.
- **Your transcripts**: audio never leaves the machine; transcript text is sent to Gemini only when the gate decides clean-up is needed (or never, with `llm.provider = "none"`). History is stored locally in `~/.local/share/murmur/history.db` for `history.keep_days`.
- **The API key** is stored in the GNOME keyring, or in `~/.config/murmur/env` with mode `0600`. Logs never include it.

`murmur doctor` warns if `/dev/input` devices are readable by all users, which some other dictation tools set up and which lets any program log keystrokes.
