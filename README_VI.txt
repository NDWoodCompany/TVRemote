TVREMOTE NATIVE: điện thoại điều khiển Sony BRAVIA + Android TV Box qua Wi-Fi/LAN.

CẤU HÌNH ĐỂ KẾT NỐI BOX ỔN ĐỊNH
1) Điện thoại và Box phải cùng Wi-Fi/cùng dải IP (vd cả hai 192.168.1.x). Tắt "Smart network switch / Chuyển sang dữ liệu di động"
   của Samsung. (App đã tự ép dùng Wi-Fi cho kết nối LAN.)
2) Router: đặt IP cố định (DHCP reservation) cho Box và TV. Box nên cắm dây mạng nếu có (Wi-Fi của box rẻ hay ngủ/rớt).
3) Box (RK312x Android 7): Cài đặt > Tùy chọn nhà phát triển: bật Gỡ lỗi USB, bật ADB qua mạng (cổng 5555).
   Không có mục này thì cắm USB vào PC chạy "adb tcpip 5555" (mất sau khi khởi động lại Box, trừ khi Box root:
   setprop persist.adb.tcp.port 5555). Từ PC thử "adb connect IP-box:5555" để biết Box có nhận không.
4) Lần đầu kết nối, màn hình TV hiện hộp thoại "Cho phép gỡ lỗi USB?": tick "Luôn cho phép" rồi OK.
   Gỡ cài đặt app sẽ mất khóa ADB và phải cho phép lại.
5) Trong app: tab cài đặt nhập IP Box (hoặc bấm biểu tượng quét ở góc phải trên hay nút TỰ TÌM TV + BOX: app tìm IP + MAC của TV và Box rồi tự điền vào cài đặt), bấm LƯU rồi TEST TV + BOX.
   Nếu Box đang ngủ sâu và tắt Wi-Fi/ADB thì không đánh thức được bằng mạng; nhập MAC Box để thử Wake-on-LAN (cần dây mạng).
6) Mất kết nối giữa chừng: app tự đóng kết nối treo và dựng lại (ADB có timeout 5-6 giây). Vẫn lỗi thì bấm ĐẶT LẠI ADB.

TV SONY: bật IP Control (Network > Home network setup > IP control), Authentication = Normal and Pre-Shared Key nếu muốn PSK,
bật Remote start để bật TV từ standby; nhập MAC TV (WoL).

NÚT BẤM: nguồn đỏ = Box thức→tắt TV rồi Box ngủ / Box ngủ→bật Box, bật TV, chọn HDMI (nhấn giữ = chỉ Box).
Nút TV = nguồn TV. Nút nguồn vào (phải trên) chọn TV/HDMI 1-4. Hai thanh VOL: trái TV, phải Box. Mic = nói trên điện thoại rồi tìm trên Box.
Bàn phím = gửi chữ sang Box. YouTube/TV360 = mở ứng dụng trên Box (TV360 tự dò gói có chữ "tv360"). Giữ mũi tên/VOL để lặp.

TỰ TÌM IP + MAC: TV = quét /24 tìm máy trả lời API Sony; MAC lấy theo thứ tự bảng ARP, REST getNetworkSettings (cần PSK nếu TV yêu cầu), cuối cùng suy từ UDN (ghi chú "chưa xác nhận").
Box = máy mở cổng ADB 5555; MAC đọc qua ADB từ chính giao diện đang giữ IP (chính xác nhất, cần đã bấm "Luôn cho phép" trên TV), nếu không được mới dùng ARP.
