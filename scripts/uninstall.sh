#!/usr/bin/env bash
# Remove Murmur. Keeps your config, dictionary, history and models unless --purge.
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

say "murmurd user service"
systemctl --user disable --now murmurd.service 2>/dev/null || true
rm -f "$HOME/.config/systemd/user/murmurd.service"
systemctl --user daemon-reload

say "Hold-to-talk helper"
"$REPO/scripts/install-keyd.sh" --uninstall

say "ydotool drop-in (ydotool itself stays installed)"
for dropin in /etc/systemd/system/*.service.d/murmur.conf; do
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
  kept="$(python3 -c 'import ast,sys; print([p for p in ast.literal_eval(sys.argv[1].removeprefix("@as ")) if "/murmur" not in p])' "$current")"
  gsettings set "$KEY" custom-keybindings "$kept"
  for i in 0 1 2 3; do
    gsettings reset-recursively "$KEY.custom-keybinding:/org/gnome/settings-daemon/plugins/media-keys/custom-keybindings/murmur$i/" 2>/dev/null || true
  done
fi

say "murmur command"
uv tool uninstall murmur 2>/dev/null || echo "murmur was not installed with uv tool"
rm -f "$HOME/.local/share/applications/io.github.hkgill.Murmur.desktop"

if [[ $PURGE == 1 ]]; then
  say "Purging config, history, models, cache and the stored API key"
  rm -rf "$HOME/.config/murmur" "$HOME/.local/share/murmur" "$HOME/.cache/murmur"
  if command -v secret-tool >/dev/null; then
    secret-tool clear service murmur username gemini_api_key 2>/dev/null || true
    secret-tool clear service murmur username proxy_token 2>/dev/null || true
  fi
else
  echo
  echo "Kept ~/.config/murmur, ~/.local/share/murmur (history, models) and the keyring entry."
  echo "Run with --purge to remove them too."
fi
echo "Murmur removed."
