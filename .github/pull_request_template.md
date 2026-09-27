## What changes for the user

<!-- One or two sentences. -->

## How it was tested

- [ ] `.venv/bin/pytest` passes, and bug fixes include a regression test that fails without the fix
- [ ] `ruff check .` passes (and `shellcheck scripts/*.sh` if scripts changed)
- [ ] Tried in the real daemon (`uv tool install --force .`, `systemctl --user restart murmurd`), if behaviour changed
- [ ] Prompt changes: `murmur eval` pass rate before → after
- [ ] `CHANGELOG.md` updated under *Unreleased*
