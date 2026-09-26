#!/usr/bin/env bash
# Murmur setup for Fedora Workstation (GNOME). Safe to re-run.
# Usage: scripts/setup-fedora.sh [--no-shortcuts] [--no-models]
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SHORTCUTS=1
MODELS=1
for arg in "$@"; do
  case "$arg" in
    --no-shortcuts) SHORTCUTS=0 ;;
    --no-models) MODELS=0 ;;
    *) echo "unknown option $arg" >&2; exit 1 ;;
  esac
done

say() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }

say "System packages"
PKGS=(python3 python3-devel portaudio-devel wl-clipboard ydotool libnotify pipewire-utils uv)
if [[ "${XDG_SESSION_TYPE:-}" == "x11" ]]; then PKGS+=(xclip xdotool); fi
sudo dnf install -y "${PKGS[@]}"

say "ydotool daemon (needs /dev/uinput; socket owned by $USER)"
UNIT="$(rpm -ql ydotool | grep -E '/systemd/system/.*\.service$' | head -n1 || true)"
UNIT_NAME="$(basename "${UNIT:-ydotool.service}")"
sudo mkdir -p "/etc/systemd/system/${UNIT_NAME}.d"
sudo tee "/etc/systemd/system/${UNIT_NAME}.d/murmur.conf" >/dev/null <<CONF
[Service]
RuntimeDirectory=ydotoold
RuntimeDirectoryMode=0755
ExecStart=
ExecStart=/usr/bin/ydotoold --socket-path=/run/ydotoold/socket --socket-own=$(id -u):$(id -g) --socket-perm=0600
CONF
sudo systemctl daemon-reload
sudo systemctl enable --now "$UNIT_NAME"
sudo systemctl restart "$UNIT_NAME"
for _ in 1 2 3 4 5; do [[ -S /run/ydotoold/socket ]] && break; sleep 0.5; done
if YDOTOOL_SOCKET=/run/ydotoold/socket ydotool key 42:1 42:0 2>/dev/null; then
  echo "ydotool OK"
else
  echo "WARNING: ydotool test failed; see 'systemctl status $UNIT_NAME'" >&2
fi

say "Install murmur (uv tool)"
uv tool install --force --python 3.12 "$REPO"
export PATH="$HOME/.local/bin:$PATH"

if [[ $MODELS == 1 ]]; then
  say "Download models"
  murmur download-models
fi

say "systemd user service"
mkdir -p "$HOME/.config/systemd/user"
cp "$REPO/systemd/murmurd.service" "$HOME/.config/systemd/user/murmurd.service"
systemctl --user daemon-reload
systemctl --user enable --now murmurd
systemctl --user restart murmurd

if [[ $SHORTCUTS == 1 ]] && command -v gsettings >/dev/null; then
  say "GNOME shortcuts"
  if gsettings list-recursively 2>/dev/null | grep -iE "<(Primary|Control)>space" | grep -v murmur; then
    echo "WARNING: the bindings above already use Ctrl+Space; change them or edit the murmur shortcuts." >&2
  fi
  if gsettings get org.freedesktop.ibus.general.hotkey triggers 2>/dev/null | grep -qi "<control>space"; then
    echo "WARNING: IBus uses Ctrl+Space to switch input methods." >&2
    echo "         Fix: gsettings set org.freedesktop.ibus.general.hotkey triggers \"['<Super>space']\"" >&2
  fi
  BASE=/org/gnome/settings-daemon/plugins/media-keys/custom-keybindings
  SCHEMA=org.gnome.settings-daemon.plugins.media-keys.custom-keybinding
  MURMUR="$HOME/.local/bin/murmur"
  add_binding() {  # id name command binding
    local path="$BASE/$1/"
    gsettings set "$SCHEMA:$path" name "$2"
    gsettings set "$SCHEMA:$path" command "$3"
    gsettings set "$SCHEMA:$path" binding "$4"
    local current
    current="$(gsettings get org.gnome.settings-daemon.plugins.media-keys custom-keybindings)"
    if [[ "$current" != *"'$path'"* ]]; then
      if [[ "$current" == "@as []" || "$current" == "[]" ]]; then
        current="['$path']"
      else
        current="${current%]}, '$path']"
      fi
      gsettings set org.gnome.settings-daemon.plugins.media-keys custom-keybindings "$current"
    fi
  }
  add_binding murmur0 "Murmur toggle" "$MURMUR toggle" "<Control>space"
  add_binding murmur1 "Murmur toggle (raw)" "$MURMUR toggle --raw" "<Control><Alt>space"
  add_binding murmur2 "Murmur toggle (formal)" "$MURMUR toggle --mode formal" "<Control><Shift>space"
  add_binding murmur3 "Murmur cancel" "$MURMUR cancel" "<Control><Alt>Escape"
  echo "Shortcuts: Ctrl+Space toggle, Ctrl+Alt+Space raw, Ctrl+Shift+Space formal, Ctrl+Alt+Esc cancel"
fi

say "Gemini API key"
echo "Use a dedicated key restricted to the Generative Language API, with a billing budget alert."
read -r -p "Store (or replace) the Gemini API key now? [y/N] " yn
if [[ "${yn,,}" == y* ]]; then
  murmur set-key && murmur reload || true
fi

say "Checks"
murmur doctor || true
cat <<'TIP'

Tip: terminals paste with Ctrl+Shift+V. Either add Ctrl+V as a paste shortcut in your
terminal's preferences (recommended), or bind a second shortcut to
`murmur toggle --chord ctrl+shift+v`.
TIP
