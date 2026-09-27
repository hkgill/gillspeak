#!/usr/bin/env bash
# Install gillspeak-keyd, the root helper behind hold-to-talk (Right Ctrl + Right Alt). Safe to re-run.
# Usage: scripts/install-keyd.sh [--uninstall]
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OWNER="$(id -un)"

if [[ "${1:-}" == "--uninstall" ]]; then
  sudo systemctl disable --now gillspeak-keyd.service 2>/dev/null || true
  sudo rm -f /etc/systemd/system/gillspeak-keyd.service /usr/local/libexec/gillspeak-keyd
  sudo systemctl daemon-reload
  echo "gillspeak-keyd removed"
  exit 0
fi

# Root runs a root-owned copy, never the user-writable tool install.
sudo install -D -o root -g root -m 0755 "$REPO/gillspeak/keyd.py" /usr/local/libexec/gillspeak-keyd
sed "s/@OWNER@/$OWNER/g" "$REPO/systemd/gillspeak-keyd.service" \
  | sudo tee /etc/systemd/system/gillspeak-keyd.service >/dev/null
sudo systemctl daemon-reload
sudo systemctl enable gillspeak-keyd.service
sudo systemctl restart gillspeak-keyd.service
for _ in 1 2 3 4 5 6; do [[ -S /run/gillspeak-keyd/socket ]] && break; sleep 0.5; done
if [[ -S /run/gillspeak-keyd/socket ]]; then
  echo "gillspeak-keyd running; socket owned by $OWNER"
  echo "Hold Right Ctrl + Right Alt to dictate; release to paste."
else
  echo "WARNING: gillspeak-keyd did not start; see 'journalctl -u gillspeak-keyd'" >&2
  exit 1
fi
