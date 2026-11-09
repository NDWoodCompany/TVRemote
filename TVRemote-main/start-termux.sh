#!/data/data/com.termux/files/usr/bin/sh
termux-wake-lock 2>/dev/null
cd "$(dirname "$0")/bridge" && node server.js
