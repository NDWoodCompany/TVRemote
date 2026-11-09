#!/data/data/com.termux/files/usr/bin/sh
# Chạy 1 lần trong Termux (bản F-Droid): sh termux-setup.sh
pkg update -y && pkg install -y nodejs android-tools
echo "Xong. Chạy bridge bằng: sh start-termux.sh"
