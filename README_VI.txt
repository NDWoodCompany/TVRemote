TVREMOTE = GIAO DIỆN WEB + BRIDGE NHỎ TRONG MẠNG LAN
=====================================================
Vì sao bản cũ trên GitHub Pages không điều khiển được:
GitHub Pages là HTTPS; trình duyệt chặn trang HTTPS gọi http://192.168.x.x (mixed content), và TV/Box không
trả CORS. JavaScript thuần không thể gửi ADB/TCP. => cần 1 "bridge" (bridge/server.js) chạy trong LAN.
Bridge vừa phục vụ giao diện, vừa gửi lệnh: Sony qua IRCC/Wake-on-LAN, Android Box qua ADB.
GitHub Pages chỉ còn để xem giao diện thử (hiện cảnh báo đỏ), KHÔNG dùng để điều khiển.

CÀI ĐẶT
1) Chọn 1 thiết bị luôn bật, cùng mạng (PC Windows, Raspberry Pi, điện thoại cũ chạy Termux...).
   Cài Node.js 18+ và adb (Android platform-tools; thêm vào PATH).
2) Android Box: bật Tùy chọn nhà phát triển > Gỡ lỗi USB, và ADB qua mạng (cổng 5555).
   Nếu không có mục ADB qua mạng: cắm USB 1 lần, chạy "adb tcpip 5555".
   Lần đầu kết nối, màn hình sẽ hỏi cho phép ADB: chọn "Luôn cho phép".
3) TV Sony: Cài đặt > Mạng > (Thiết lập mạng gia đình) > Điều khiển IP: bật, Xác thực = "Thường và Pre-Shared Key",
   đặt PSK. Bật "Khởi động từ xa" nếu muốn bật TV từ standby (tên menu khác nhau tùy đời TV).
   Muốn bật TV từ standby: nhập thêm địa chỉ MAC của TV trong Cài đặt của app.
4) Chạy bridge:  node bridge/server.js   (Windows: bấm đúp start-bridge.bat)
   Mở địa chỉ in ra (vd http://192.168.1.50:8765) trên điện thoại > vào tab Cài đặt > nhập IP, PSK... > Lưu >
   Kiểm tra kết nối. Rồi Chrome > Thêm vào màn hình chính.
5) Đẩy lên GitHub: KHÔNG commit bridge/config.json (chứa PSK; đã có trong .gitignore).

DÙNG
- Nút TV (trái trên): bật/tắt TV. Nút xanh/đỏ giữa: nguồn Box. Nút phải trên: chọn nguồn TV/HDMI 1-4.
- D-pad, Back, Exit(=Home), VOL BOX: gửi tới Box qua ADB. VOL TV, Mute: gửi tới TV. Giữ nút để lặp lại.
- Tìm kiếm: chạm nút micro (mở bảng tìm kiếm, tự nghe), hoặc nhập chữ rồi bấm:
    "Tìm trên Box"  = mở link tìm kiếm YouTube trong app đã chọn (SmartTube/YouTube).
    "Gõ vào ô nhập" = gõ chữ vào ô đang focus trên Box (mở app + chọn ô tìm kiếm trước bằng D-pad).
    Chữ có dấu tiếng Việt cần cài ADBKeyboard (com.android.adbkeyboard) trên Box; bridge tự đổi bàn phím rồi trả lại.
- Micro: Web Speech cần HTTPS nên có thể bị chặn khi mở http://IP. Khi đó dùng micro trên bàn phím (Gboard) trong ô nhập.
- Box chậm (RK312x): lệnh ADB mất ~0.3-1 giây/phím, nên giữ nút sẽ lặp chậm hơn remote thật.

CHẠY BRIDGE NGAY TRÊN ĐIỆN THOẠI ANDROID (Termux, không cần PC/box)
1) Cài Termux từ F-Droid (bản Play Store đã cũ). Chép thư mục TVRemote vào điện thoại (hoặc git clone).
2) Trong Termux: sh termux-setup.sh (1 lần), rồi sh start-termux.sh khi cần dùng remote.
3) Mở http://127.0.0.1:8765 trên điện thoại (Add to Home screen), hoặc vẫn mở trang GitHub Pages:
   trang sẽ tự tìm bridge ở 127.0.0.1:8765 (Chrome cho phép HTTPS gọi localhost; có thể hiện hộp xin quyền mạng nội bộ).
4) Cài đặt trong app: IP Box + IP/PSK của TV. Thường nên tắt tối ưu pin cho Termux trên điện thoại.

DÙNG CHO TRẺ EM / CẢ NHÀ: BRIDGE PHẢI CHẠY TỰ ĐỘNG TRÊN THIẾT BỊ LUÔN BẬT
- Windows: bấm đúp install-windows-startup.bat (chạy ẩn, tự chạy mỗi lần đăng nhập).
- Raspberry Pi/Linux: sh install-pi.sh (dịch vụ systemd, tự chạy khi bật nguồn).
- Điện thoại Android cũ cắm sạc: cài Termux + Termux:Boot (F-Droid), sh termux-setup.sh rồi sh termux-boot.sh.
Sau đó đặt IP tĩnh/DHCP reservation cho thiết bị đó trên router, và Add to Home screen địa chỉ http://IP:8765
trên điện thoại của mọi người. Mở lên là bấm, không cần làm gì thêm.

BẢN APK CHẠY NỀN TRÊN BOX (khuyên dùng – thay cho Node/Termux)
APK chạy trên chính Android box: tự khởi động cùng hệ thống, mở web remote ở http://IP-box:8765 cho điện thoại cả nhà
(Android/iPhone đều được, không cần cài gì), gửi lệnh tới TV (IRCC/WoL) và điều khiển Box qua ADB tới 127.0.0.1.
Có tuỳ chọn "TV tự bật/tắt theo Box": Box bật/mở màn hình -> TV bật; Box tắt màn hình/ngủ -> TV tắt.
1) Up TOÀN BỘ thư mục lên GitHub, nhớ cả thư mục ẩn .github và thư mục android (một số script sync bỏ qua thư mục ẩn).
   Tab Actions > build-apk tự build; file TVRemote.apk nằm ở Releases > "apk-latest".
2) Cài TVRemote.apk lên Box (USB/file manager). Mở app 1 lần, bấm "Bỏ tối ưu pin".
   Trong cài đặt hệ thống của Box: cho TVRemote "tự khởi động" (nếu firmware có mục này).
3) Box: bật Gỡ lỗi USB + ADB qua mạng (cổng 5555). Lần đầu TV hiện hộp thoại cho phép ADB: chọn "Luôn cho phép".
   Nếu Box đã root, app tự bật ADB TCP mỗi lần khởi động.
4) Trên điện thoại mở http://IP-box:8765 > tab Cài đặt: IP/PSK/MAC của TV, IP Box để 127.0.0.1 > Lưu > Kiểm tra kết nối.
