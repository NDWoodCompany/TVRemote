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
