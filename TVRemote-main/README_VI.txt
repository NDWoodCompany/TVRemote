TVRemote Native 2.0
====================

Mục tiêu:
- Ứng dụng Android native chạy trực tiếp trên Samsung Galaxy S20 5G.
- Không dùng WebView, HTML, Chrome hoặc GitHub Pages trong giao diện điều khiển.
- S20 điều khiển Sony BRAVIA qua Sony IRCC/Wake-on-LAN.
- S20 điều khiển Android TV Box AK312 qua ADB TCP/IP.

Giao diện hiện có:
- Tab Điều khiển: Scene nguồn, TV Power, HDMI 1/2, âm lượng TV/Box, Mute, D-pad, OK, Back, Home, Exit, mở ứng dụng tìm kiếm, giọng nói.
- Tab Bàn phím: phím số và các phím điều hướng kênh.
- Tab Cài đặt: IP TV, IP Box, Sony PSK, MAC TV, lưu cấu hình, kiểm tra ADB.
- Foreground Service, thông báo thường trực, giữ Wi-Fi/WakeLock, nhận BOOT_COMPLETED.

Thiết lập sau khi cài:
1. Kết nối S20 và AK312 vào cùng mạng Wi-Fi.
2. Trong app, mở Cài đặt, nhập IP Box (mặc định 127.0.0.1 nên cần đổi thành IP thật của AK312, ví dụ 192.168.1.233).
3. Nhập IP Sony nếu tự dò chưa tìm thấy (ví dụ 192.168.1.234).
4. Bấm Kiểm tra ADB. Lần đầu, nếu Box hiện hộp thoại RSA thì chọn Always allow from this computer và Allow.
5. Cấu hình Sony IP Control/PSK nếu TV yêu cầu.

Lưu ý:
- Android có thể yêu cầu xác nhận RSA ADB lần đầu.
- Nhận dạng giọng nói phụ thuộc dịch vụ Speech Recognition đã cài trên S20.
- Mã nguồn này chưa được build thành APK trong môi trường hiện tại vì không có Gradle/Android SDK. Có thể build bằng GitHub Actions workflow trong .github/workflows/build-apk.yml hoặc Android Studio.
- Không thể cài APK im lặng trên Android thông thường; Android sẽ hiển thị trình cài đặt để người dùng xác nhận.
