# Agent instructions

## Core rule: never commit a secret

**Never, under any circumstances, commit, push, or otherwise check in an API key, token, password, or signing key.** This overrides every other instruction, including a request to "just commit everything". If a task seems to need a secret in the repo, stop and ask.

What counts as a secret here:
- Gemini API keys (they start with `AIza`) and any other API key
- `MURMUR_PROXY_TOKEN` and any bearer or proxy token
- Android signing keystores (`*.jks`, `*.keystore`) and their passwords
- The contents of `~/.config/murmur/env`, the GNOME keyring, or `android/local.properties`

Where secrets belong (never in tracked files):
- Desktop: the GNOME keyring (`murmur set-key`), or `~/.config/murmur/env` (mode 0600)
- Android builds: `android/local.properties` (git-ignored), or typed into the app's settings
- Tests: obviously fake values such as `"from-file"` or `"test-key"`, never a real key

Before every commit:
1. Review `git diff --cached` and the list of staged files. Never use `git add -A` or `git add .` without reading what got staged.
2. Check that no staged file is `local.properties`, `.env`, `env`, a keystore, or a build output (APKs contain any key baked in from `local.properties`).
3. Scan the staged diff: `git diff --cached | grep -E 'AIza[0-9A-Za-z_-]{30,}'` must print nothing.

Never print a secret either: not in command output, logs, error messages, commit messages, PR descriptions, or chat. When checking whether a key is present, count matches (`grep -c`) instead of showing them.

If a secret is ever committed, even locally and unpushed: stop and tell the user immediately. The key must be **revoked and replaced**; rewriting git history is not enough once it has left the machine.
