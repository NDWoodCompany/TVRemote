TVREMOTE PWA ONLY – https://ndwoodcompany.github.io/TVRemote/
========================================================
IP mặc định:
Sony BRAVIA KDL-42W674A: 192.168.1.234
Android Smart TV Box Android 7: 192.168.1.233

GIỚI HẠN KỸ THUẬT
- PWA không có quyền raw TCP/UDP, ADB, SSDP multicast hoặc quét subnet tùy ý.
- GitHub Pages là HTTPS. Gọi HTTP đến TV/Box có thể bị chặn bởi mixed-content; CORS cũng có thể chặn request. Safari iOS và Chrome Android có cách thực thi khác nhau.
- Sony IRCC-IP có các mã lệnh phổ biến trong app, nhưng trình duyệt có thể chặn request. TV có thể cần bật Network Control/Remote Device và PSK. Không thể vượt CORS/mixed-content bằng JavaScript trên GitHub Pages.
- Android 7 firmware mod không có HTTP remote API chung. ADB TCP cần raw TCP nên PWA không thể gọi. Chỉ điều khiển được nếu firmware đã có sẵn HTTP Remote Server; cần biết endpoint, method và payload cụ thể để tích hợp.
- Voice search nhận câu nói trên điện thoại. Nút tìm kiếm hiện mở YouTube trên điện thoại; chưa thể chuyển kết quả sang Box nếu không có API remote tương thích.
- Kiểm tra kết nối chỉ thử HTTP best-effort, KHÔNG phải quét LAN và không xác định chắc thiết bị online/offline.
- Lệnh Box được để an toàn, không giả định API và không gửi payload đoán mò.
