#!/usr/bin/env bash
# gillspeak was called Murmur before 0.6.0. This removes what an old Murmur install left behind: its user
# service, uv tool, desktop entry, GNOME shortcuts, ydotool drop-in and the root hold-to-talk helper.
# Your config, dictionary, history and models are not touched: gillspeak moves ~/.config/murmur,
# ~/.local/share/murmur and ~/.cache/murmur over on first run, and copies the keyring entry.
# Safe to re-run; does nothing when there is no old install. Run by setup-fedora.sh and uninstall.sh.
set -euo pipefail

found=0
note() { found=1; echo "  removed $*"; }

if [[ -e "$HOME/.config/systemd/user/murmurd.service" ]]; then
  systemctl --user disable --now murmurd.service 2>/dev/null || true
  rm -f "$HOME/.config/systemd/user/murmurd.service"
  systemctl --user daemon-reload
  note "murmurd user service"
fi

if command -v uv >/dev/null && uv tool list 2>/dev/null | grep -q '^murmur '; then
  uv tool uninstall murmur >/dev/null 2>&1 || true
  note "murmur command (uv tool)"
fi

if [[ -e "$HOME/.local/share/applications/io.github.hkgill.Murmur.desktop" ]]; then
  rm -f "$HOME/.local/share/applications/io.github.hkgill.Murmur.desktop"
  note "Murmur desktop entry"
fi

if command -v gsettings >/dev/null; then
  KEY=org.gnome.settings-daemon.plugins.media-keys
  current="$(gsettings get "$KEY" custom-keybindings 2>/dev/null || echo "@as []")"
  if [[ "$current" == */custom-keybindings/murmur* ]]; then
    kept="$(python3 -c 'import ast,sys; print([p for p in ast.literal_eval(sys.argv[1].removeprefix("@as ")) if "/custom-keybindings/murmur" not in p])' "$current")"
    gsettings set "$KEY" custom-keybindings "$kept"
    for i in 0 1 2 3; do
      gsettings reset-recursively "$KEY.custom-keybinding:/org/gnome/settings-daemon/plugins/media-keys/custom-keybindings/murmur$i/" 2>/dev/null || true
    done
    note "Murmur GNOME shortcuts"
  fi
fi

for dropin in /etc/systemd/system/*.service.d/murmur.conf; do
  [[ -e "$dropin" ]] || continue
  sudo rm -f "$dropin"
  sudo rmdir --ignore-fail-on-non-empty "$(dirname "$dropin")"
  sudo systemctl daemon-reload
  note "$dropin"
done

if [[ -e /etc/systemd/system/murmur-keyd.service || -e /usr/local/libexec/murmur-keyd ]]; then
  sudo systemctl disable --now murmur-keyd.service 2>/dev/null || true
  sudo rm -f /etc/systemd/system/murmur-keyd.service /usr/local/libexec/murmur-keyd
  sudo systemctl daemon-reload
  note "murmur-keyd root helper"
fi

[[ $found == 1 ]] || echo "  no old Murmur install found"
