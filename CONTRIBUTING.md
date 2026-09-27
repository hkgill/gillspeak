# Contributing to Murmur

Thanks for helping. Bug reports, fixes, docs and testing on other distros are all welcome.

## Set up

```bash
git clone https://github.com/hkgill/gillspeak.git && cd gillspeak
uv venv -p 3.12 && uv pip install -e '.[dev]' ruff
.venv/bin/pytest            # ~2 s, no models, microphone, desktop or network needed
.venv/bin/ruff check .
```

To try your change in the real daemon: `uv tool install --force --python 3.12 .` then `systemctl --user restart murmurd`. If you changed `murmur/keyd.py`, re-run `scripts/install-keyd.sh` (root runs a copy of it). `murmur doctor` tells you when that copy is stale.

## Tests

- **Every bug fix comes with a regression test** that fails without the fix. Say what broke in its docstring (see `tests/test_keyd.py::test_unplugged_keyboard_cancels_hold_and_does_not_spin`).
- The default suite must not touch real hardware, the network or your home directory. `tests/conftest.py` isolates XDG paths and the daemon socket; fake subprocesses, HTTP (`respx`) and audio the way existing tests do.
- Real-model tests are opt-in: `MURMUR_TEST_MODELS=~/.local/share/murmur/models .venv/bin/pytest tests/test_models_integration.py`.

## Changing the clean-up prompt

Prompts are versioned. Add `murmur/prompts/clean_vN.txt`, bump `PROMPT_VERSION` in `cleaner.py`, and include the before/after `murmur eval` pass rate in the PR. The acceptance bar is 95%. On a free-tier key, `murmur eval --limit 20` keeps you under the rate limit while iterating.

## Style

- `ruff check .` must pass (CI enforces it). Match the surrounding code; comments explain *why*, not *what*.
- `murmur/cli.py` imports only the standard library at module level: it runs on every hotkey press.
- `murmur/keyd.py` runs as root and must stay standalone (stdlib only, no imports from the package).
- Scripts must pass `shellcheck`.

## Pull requests

Keep them focused, describe the user-visible change, and add a line to `CHANGELOG.md` under *Unreleased*. CI runs lint, the suite on Python 3.12–3.14, and the suite plus a wheel build in a Fedora container.

## Security

Please report vulnerabilities privately; see [SECURITY.md](SECURITY.md).
