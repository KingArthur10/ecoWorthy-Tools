#!/usr/bin/env bash
# Builds the hub and installs it as a systemd *user* service. No root needed.
# Needs a Java 21+ runtime on the machine (Fedora: java-25-openjdk-headless).
set -euo pipefail
cd "$(dirname "$0")/.."

./gradlew -q :hub:installDist
dest="$HOME/.local/opt/battery-hub"
rm -rf "$dest" && mkdir -p "$dest"
cp -r hub/build/install/battery-hub/. "$dest/"
mkdir -p "$HOME/.local/bin" "$HOME/.config/systemd/user"
ln -sf "$dest/bin/battery-hub" "$HOME/.local/bin/battery-hub"
cp hub/dist/battery-hub.service "$HOME/.config/systemd/user/"
systemctl --user daemon-reload
systemctl --user enable --now battery-hub.service

echo "Installed. Dashboard: http://127.0.0.1:8640/   Logs: journalctl --user -u battery-hub -f"
echo "To keep it running while you're logged out (always-on PC): sudo loginctl enable-linger $USER"
