#!/usr/bin/env bash
# Remove gillspeak. Keeps your config, dictionary, history and models unless --purge.
# Usage: scripts/uninstall.sh [--purge]
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PURGE=0
for arg in "$@"; do
  case "$arg" in
    --purge) PURGE=1 ;;
    *) echo "unknown option $arg" >&2; exit 1 ;;
  esac
done

say() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }

say "Old Murmur install (the name before 0.6.0)"
"$REPO/scripts/remove-legacy-murmur.sh"

say "gillspeakd user service"
systemctl --user disable --now gillspeakd.service 2>/dev/null || true
rm -f "$HOME/.config/systemd/user/gillspeakd.service"
systemctl --user daemon-reload

say "Hold-to-talk helper"
"$REPO/scripts/install-keyd.sh" --uninstall

say "ydotool drop-in (ydotool itself stays installed)"
for dropin in /etc/systemd/system/*.service.d/gillspeak.conf; do
  [[ -e "$dropin" ]] || continue
  sudo rm -f "$dropin"
  sudo rmdir --ignore-fail-on-non-empty "$(dirname "$dropin")"
  echo "removed $dropin"
done
sudo systemctl daemon-reload

if command -v gsettings >/dev/null; then
  say "GNOME shortcuts"
  KEY=org.gnome.settings-daemon.plugins.media-keys
  current="$(gsettings get "$KEY" custom-keybindings)"
  kept="$(python3 -c 'import ast,sys; print([p for p in ast.literal_eval(sys.argv[1].removeprefix("@as ")) if "/gillspeak" not in p])' "$current")"
  gsettings set "$KEY" custom-keybindings "$kept"
  for i in 0 1 2 3; do
    gsettings reset-recursively "$KEY.custom-keybinding:/org/gnome/settings-daemon/plugins/media-keys/custom-keybindings/gillspeak$i/" 2>/dev/null || true
  done
fi

say "gillspeak command"
uv tool uninstall gillspeak 2>/dev/null || echo "gillspeak was not installed with uv tool"
rm -f "$HOME/.local/share/applications/io.github.hkgill.gillspeak.desktop"

if [[ $PURGE == 1 ]]; then
  say "Purging config, history, models, cache and the stored API key"
  rm -rf "$HOME/.config/gillspeak" "$HOME/.local/share/gillspeak" "$HOME/.cache/gillspeak"
  if command -v secret-tool >/dev/null; then
    secret-tool clear service gillspeak username gemini_api_key 2>/dev/null || true
    secret-tool clear service gillspeak username proxy_token 2>/dev/null || true
    # Entries stored under the old name, before the rename.
    secret-tool clear service murmur username gemini_api_key 2>/dev/null || true
    secret-tool clear service murmur username proxy_token 2>/dev/null || true
  fi
else
  echo
  echo "Kept ~/.config/gillspeak, ~/.local/share/gillspeak (history, models) and the keyring entry."
  echo "Run with --purge to remove them too."
fi
echo "gillspeak removed."
