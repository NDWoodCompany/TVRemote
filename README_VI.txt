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


CAP NHAT v2 - CHAY NEN / POWER SONY / VOICE
1. APK TVRemote tren Android Box:
   - Dich vu BridgeService chay foreground, co thong bao "TVRemote dang chay".
   - Bam Back tai man hinh APK se thu nho ung dung xuong nen, khong dong Activity.
   - Neu firmware tu dong diet app khi vuot Recent, dich vu co hen gio yeu cau khoi dong lai.
   - Trong Android Box, vao Cai dat > Ung dung > TVRemote > Pin va cho phep khong toi uu pin neu firmware co muc nay.
   - Khong bam Force stop. Force stop se chan service va receiver khoi dong lai cho den khi mo app thu cong.
   - Tat/bo qua toi uu pin va cho phep tu khoi dong neu firmware Box co cac tuy chon nay.

2. Giao dien web:
   - Trang web tren dien thoai la bo dieu khien. Neu dong tab/trinh duyet hoan toan, giao dien khong the tu chay ngam nhu APK native.
   - Tuy nhien BridgeService tren Box van tiep tuc chay va lang nghe cong 8765; mo lai http://IP-BOX:8765 de tiep tuc dieu khien.
   - Tren Android, co the them trang vao man hinh chinh (PWA) de mo nhu ung dung rieng. PWA khong thay the Foreground Service.

3. Nut TV:
   - Dung ma IRCC Power chuan cua Sony de toggle bat/tat.
   - Khi TV bao standby, gui Wake-on-LAN truoc roi gui Power.
   - Neu TV khong phan hoi trang thai qua /sony/system, lenh Power van duoc gui; khong the bao dam trang thai bat/tat neu TV/firmware chan phan hoi.

4. Voice:
   - Nhan dang giong noi cua trinh duyet can HTTPS va trinh duyet ho tro SpeechRecognition.
   - Dia chi http://IP-BOX:8765 la HTTP noi bo, co the bi Android Chrome chan microphone SpeechRecognition.
   - Khi khong du dieu kien, TVRemote mo san o nhap va hien huong dan dung nut micro tren ban phim Android (Gboard).
   - Tren iPhone/Safari, ho tro Web Speech API co the khac nhau; neu khong co, dung micro cua ban phim iOS.


CAP NHAT v3 - POWER BOX 2 TRANG THAI / GIU DICH VU
- Nút nguồn đỏ: nếu màn hình Box đang sáng, TV được đưa về standby trước, sau đó Box chỉ sleep màn hình (KEYCODE_SLEEP 223). Không dùng KEYCODE_POWER dự phòng vì phím này là toggle và có thể bật màn hình lại.
- Bấm nút đỏ lần nữa: gửi KEYCODE_WAKEUP 224 để đánh thức màn hình Box, đợi 1,2 giây rồi bật TV và chuyển HDMI đã chọn.
- Khi TV đã standby, lệnh tắt sẽ không gửi thêm Power toggle. Khi TV đã active, mới gửi Power để tắt. Khi không đọc được trạng thái, không gửi lệnh mù để tránh bật nhầm TV đang ngủ.
- Bỏ xử lý screen-on/off trùng trong 10 giây sau khi nút nguồn chủ động thay đổi trạng thái Box.
- BridgeService tiếp tục chạy foreground, giữ partial wake lock và Wi-Fi lock. Nếu firmware Box tự tắt Wi-Fi khi sleep, remote qua mạng có thể tạm thời không kết nối được; cần bật chế độ sleep giữ mạng trong cài đặt Box/router nếu có.
- Voice Search vẫn mở ứng dụng tìm kiếm đã chọn trên Box sau khi nhận được câu nói. Việc mở YouTube/SmartTube sẽ thay giao diện đang hiển thị trên Box nhưng không dừng BridgeService. Giao diện điều khiển trên điện thoại vẫn dùng được. Nếu trình duyệt không hỗ trợ SpeechRecognition hoặc đang dùng HTTP LAN, hãy dùng micro của bàn phím điện thoại để nhập câu nói vào ô tìm kiếm.
