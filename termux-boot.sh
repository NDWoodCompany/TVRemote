#!/data/data/com.termux/files/usr/bin/sh
# Dien thoai Android cu cam sac: bridge tu chay khi may khoi dong (can app Termux:Boot tu F-Droid, mo 1 lan)
D="$(cd "$(dirname "$0")" && pwd)"
mkdir -p ~/.termux/boot
printf '#!/data/data/com.termux/files/usr/bin/sh\ntermux-wake-lock\ncd "%s/bridge" && node server.js\n' "$D" > ~/.termux/boot/tvremote.sh
chmod +x ~/.termux/boot/tvremote.sh
echo "Xong. Tat toi uu pin cho Termux va Termux:Boot, roi khoi dong lai may."
