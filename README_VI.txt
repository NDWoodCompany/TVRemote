TVREMOTE: APP ANDROID CHẠY NỀN TRÊN BOX + WEB REMOTE
=====================================================
Không cần bridge/Node/Termux nữa: chính app TVRemote.apk (cài trên Android box) là "bridge".
Nó tự chạy cùng hệ thống, phục vụ web remote tại http://IP-box:8765 cho mọi điện thoại trong nhà
(Android/iPhone), gửi lệnh tới TV Sony (IRCC, Wake-on-LAN) và điều khiển Box qua ADB tới 127.0.0.1.
Trang github.io chỉ là bản xem thử; nó hỏi IP Box rồi chuyển sang địa chỉ thật.

NÚT NGUỒN ĐỎ: Box đang sáng -> tắt TV rồi cho Box ngủ. Box đang ngủ -> đánh thức Box, bật TV, chờ TV lên rồi chọn cổng
(mặc định HDMI 2, đổi trong Cài đặt). Nút TV (trái trên) chỉ bật/tắt riêng TV.
Tuỳ chọn "TV tự bật/tắt theo Box": Box sáng/tắt màn hình bằng cách khác (remote vật lý...) thì TV cũng theo.

TỰ TÌM TV: lần đầu (chưa lưu IP) hoặc khi TV không trả lời, app kiểm tra IP/MAC đã lưu còn trong Wi-Fi không;
nếu không thì quét mạng /24, tìm TV Sony, lưu IP + MAC (MAC đọc từ bảng ARP, Box Android 7 đọc được).
Nút "Tự tìm TV" trong Cài đặt để quét lại. Không tìm được thì nhập tay IP/MAC.

CÀI ĐẶT
1) Up TOÀN BỘ thư mục lên GitHub (cả .github và android; xoá thư mục bridge cũ nếu còn). Tab Actions > build-apk tự build,
   TVRemote.apk nằm ở Releases > "apk-latest".
2) Cài APK lên Box, mở 1 lần, bấm "Bỏ tối ưu pin"; cho phép tự khởi động nếu firmware có mục này.
3) Box: bật Gỡ lỗi USB + ADB qua mạng (cổng 5555). Lần đầu TV hiện hộp thoại ADB: chọn "Luôn cho phép".
   Box đã root thì app tự bật ADB TCP mỗi lần khởi động.
4) TV Sony: bật IP Control (PSK nếu muốn), bật "Khởi động từ xa" để bật TV từ standby.
5) Điện thoại: mở http://IP-box:8765 > Add to Home screen > tab Cài đặt: Tự tìm TV, PSK, cổng HDMI > Lưu.
