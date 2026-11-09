TVRemote Native – Clean v2.2
=============================

Đây là mã nguồn Android native, giao diện điều khiển được dựng bằng Android Views/Kotlin.
Không dùng HTML, CSS, JavaScript, PWA hoặc WebView.

Thiết bị mục tiêu:
- Điện thoại điều khiển: Samsung Galaxy S20 5G, Android 13.
- TV: Sony BRAVIA KDL-42W674A.
- Android TV Box: AK312_ATV7, Android 7.1.2.

Cấu trúc:
- android/: dự án Android Gradle duy nhất.
- .github/workflows/build-apk.yml: GitHub Actions build APK debug và cập nhật GitHub Release tag apk-latest.
- Main.kt: giao diện native, Sony IRCC/WOL, dò TV, ADB TCP, dịch vụ nền.

Build:
- JDK 17, Gradle 8.7, Android Gradle Plugin 8.2.2, Kotlin 1.9.22.
- Chạy: gradle -p android assembleDebug
- APK: android/app/build/outputs/apk/debug/app-debug.apk

Cài đặt:
1. Cài APK lên S20 và mở ứng dụng.
2. Vào Cài đặt, nhập IP Sony và IP Box (ví dụ IP Box 192.168.1.233).
3. Sony PSK chỉ nhập nếu TV đã bật xác thực PSK.
4. MAC TV là tùy chọn, dùng cho Wake-on-LAN khi cần.
5. Gói ứng dụng tìm kiếm mặc định là com.teamsmart.videomanager.tv; đổi theo ứng dụng có trên Box.
6. Lưu cấu hình, bấm Kiểm tra ADB. Lần đầu, chấp nhận RSA trên Box và chọn Always allow from this computer.
7. Nếu tự dò TV không tìm được, nhập IP TV thủ công.

An toàn nguồn TV:
- IRCC Power là lệnh toggle. App chỉ gửi lệnh nguồn khi đọc được trạng thái active/standby; nếu trạng thái không rõ thì báo lỗi, không tự đoán để tránh bật/tắt nhầm.
- WOL không được gửi ngay trước lệnh Power toggle để tránh trường hợp WOL làm TV thức dậy rồi lệnh toggle tắt TV trở lại.

Chạy nền:
- Dịch vụ foreground hiển thị thông báo thường trực và giữ Wi-Fi lock.
- Android/One UI có thể vẫn giới hạn dịch vụ nền theo cài đặt pin; người dùng cần cho phép chạy nền/không tối ưu pin nếu muốn duy trì lâu dài.
- App không tự bật/tắt TV theo trạng thái màn hình S20.
- Android không cho app thông thường tự cài APK im lặng; thao tác cài/cập nhật cần xác nhận của người dùng.

Giới hạn đã biết:
- Dò thiết bị qua ARP không được Android 13 đảm bảo cung cấp đầy đủ MAC; nhập IP TV/Box thủ công vẫn là phương án đáng tin cậy.
- Wake-on-LAN phụ thuộc cấu hình mạng và chế độ standby của TV.
- Nhận dạng giọng nói tiếng Việt phụ thuộc dịch vụ Speech Recognition trên S20.
- Gõ tiếng Việt vào Box yêu cầu cài ADBKeyboard trên Box; điều khiển phím cơ bản không cần thư viện này.

Kiểm tra:
- Đã kiểm tra tĩnh cấu trúc dự án, workflow, import, đường dẫn nguồn, cấu hình và các nhánh lệnh nguồn.
- Chưa chạy được Android SDK/Gradle hoặc thử nghiệm trên S20/AK312 trong môi trường tạo file. Do đó chưa thể xác nhận APK biên dịch và điều khiển thực tế cho đến khi GitHub Actions và thiết bị xác nhận.
