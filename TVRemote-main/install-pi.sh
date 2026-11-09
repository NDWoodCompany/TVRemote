#!/bin/sh
# Raspberry Pi / Linux: cai bridge thanh dich vu systemd tu chay khi bat may.  Chay: sh install-pi.sh
D="$(cd "$(dirname "$0")" && pwd)"
sudo tee /etc/systemd/system/tvremote.service >/dev/null <<UNIT
[Unit]
Description=TVRemote bridge
After=network-online.target
[Service]
WorkingDirectory=$D/bridge
ExecStart=$(command -v node) server.js
Restart=always
User=$(whoami)
[Install]
WantedBy=multi-user.target
UNIT
sudo systemctl daemon-reload && sudo systemctl enable --now tvremote && echo "Da cai dich vu tvremote (cong 8765)."
