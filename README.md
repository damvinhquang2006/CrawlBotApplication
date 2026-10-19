# 📱 CrawlBot Application — Hệ Thống Giám Sát Thời Tiết & Giá Xăng Dầu Hàng Ngày

Ứng dụng di động Android kết hợp máy chủ nhúng Java & công cụ cào web Python thông minh. Hệ thống tự động thu thập thông tin thời tiết Hà Nội và giá bán lẻ xăng dầu Petrolimex / VnExpress từ các nguồn web tĩnh hàng ngày, phát hiện biến động qua mã băm SHA-256 ổn định, lưu trữ vào SQLite Room trên thiết bị, và phát thông báo đẩy (Push Notification) đến người dùng.

---

## 🏗️ 1. Kiến Trúc Hệ Thống (Client - Server)

```
+-----------------------------------------------------------------------------------+
|                        📱 ANDROID CLIENT APPLICATION                              |
|  - Giao diện 4 Tab: Giá xăng, Thời tiết, JSON Viewer, Lịch sử biến động           |
|  - Room SQLite Database (Offline-First, lưu trữ cục bộ trên máy)                   |
|  - ServerApiClient (HttpURLConnection, Token-based Security, Timeout 10s)         |
|  - WorkManager SyncWorker (Đồng bộ ngầm định kỳ 15 phút, tiết kiệm pin)           |
|  - NotificationHelper (Bắn thông báo đẩy khi giá xăng thay đổi)                  |
+-----------------------------------------+-----------------------------------------+
                                          |
                      HTTP / REST API     |  GET /api/daily-summary
                      (Port 8080)         |  POST /api/crawl/trigger
                                          v
+-----------------------------------------------------------------------------------+
|                   🖥️ JAVA EMBEDDED REST API SERVER (bot_demo)                     |
|  - HttpServer nhúng (Đa luồng Daemon Worker, Rate-limiting, Bearer Auth Token)   |
|  - ScheduledExecutorService (Quét tự động định kỳ 12 giờ)                         |
|  - UrlSecurityValidator (Chống tấn công SSRF & Whitelist Domain)                 |
|  - DatabaseManager (SQLite Server lưu trữ lịch sử và mã băm SHA-256)              |
+-----------------------------------------+-----------------------------------------+
                                          |  ProcessBuilder (Hard Timeout 30s)
                                          |  CompletableFuture Async Stream Readers
                                          v
+-----------------------------------------------------------------------------------+
|                        🐍 PYTHON CRAWLER ENGINE (crawler.py)                      |
|  - Thư viện: requests (Session + Browser User-Agent) + BeautifulSoup4             |
|  - Phân tích bảng giá xăng thông minh (Header Inspection + Cell Type Heuristics)  |
|  - Bộ thu thập 2 bước VnExpress: Chuyên đề giá xăng -> Bài báo mới nhất          |
|  - Tích hợp AccuWeather Developer API (Dự báo thời tiết theo giờ)                 |
|  - Trả dữ liệu JSON chuẩn hóa qua stdout                                          |
+-----------------------------------------------------------------------------------+
```

---

## ⚙️ 2. Yêu Cầu Môi Trường (Prerequisites)

Trước khi tiến hành build và chạy dự án, hãy đảm bảo laptop của bạn đã cài đặt các công cụ sau:

1. **Android Studio**: Bản Ladybug / Jellyfish / Hedgehog hoặc mới hơn.
2. **Android SDK**: API Level 26 (Android 8.0) đến API 34+ (Android 14).
3. **Java Development Kit (JDK)**: JDK 17 LTS (hoặc sử dụng JDK tích hợp sẵn trong Android Studio: `jbr-17`).
4. **Python**: Python 3.8+ (đã tích hợp vào biến môi trường PATH của hệ thống, gõ lệnh `py` hoặc `python` được).
5. **Apache Maven**: Lệnh `mvn` khả dụng trên terminal.
6. **Thiết bị kiểm thử**:
   - **Android Emulator**: Google Pixel chạy Android 11.0+ (API 30+), có Google Play Services.
   - **Hoặc điện thoại Android thật**: Bật chế độ *Tùy chọn cho nhà phát triển* & *USB Debugging*.

---

## 🛠️ 3. Hướng Dẫn Build & Chuẩn Bị (Build Guide)

### Bước 3.1: Cài đặt thư viện Python cho Crawler
Mở terminal (PowerShell hoặc CMD) tại thư mục gốc của dự án:
```powershell
cd bot_demo
py -m pip install -r python/requirements.txt
```
*(Yêu cầu đã cài đặt `requests` và `beautifulsoup4`).*

### Bước 3.2: Thiết lập cấu hình bảo mật cho Server
Sao chép file mẫu [`config.properties.example`](file:///c:/Users/VINH_QUANG/AndroidStudioProjects/CrawlBotApplication/bot_demo/config.properties.example) thành file cấu hình thực tế:
```powershell
Copy-Item config.properties.example config.properties
```
*(Xem chi tiết hướng dẫn bảo mật tại file [`CONFIGATION.md`](file:///c:/Users/VINH_QUANG/AndroidStudioProjects/CrawlBotApplication/CONFIGATION.md)).*

### Bước 3.3: Biên dịch & Đóng gói Server Java (`bot_demo`)
Tại thư mục `bot_demo`:
```powershell
mvn clean package -DskipTests
```
Lệnh này sẽ tự động tải các dependencies (Jackson, SQLite JDBC, JUnit 5) và tạo file JAR thực thi tại `bot_demo/target/crawler-bot-1.0.0.jar`.

### Bước 3.4: Mở dự án trong Android Studio & Sync Gradle
1. Khởi động Android Studio, chọn **File** $\rightarrow$ **Open** $\rightarrow$ Chọn thư mục `CrawlBotApplication`.
2. Chờ Android Studio tự động phát hiện SDK, sinh file `local.properties` và chạy **Gradle Sync**.
3. Nếu cần kích hoạt sync thủ công: Nhấn biểu tượng con voi **"Sync Project with Gradle Files"** trên thanh công cụ.
4. Biên dịch thử Android App: Vào menu **Build** $\rightarrow$ **Make Project** (Phím tắt: `Ctrl + F9`).

---

## 🚀 4. Hướng Dẫn Run Chuẩn Xác (Run Guide)

> [!IMPORTANT]
> **QUY TẮC VÀNG:** Bạn **BẮT BUỘC** phải khởi động Java Bot Server trên laptop **TRƯỚC** khi mở ứng dụng Android trên Emulator hoặc máy thật. Nếu Server chưa chạy, ứng dụng sẽ báo lỗi timeout kết nối sau 10000ms.

### Bước 4.1: Khởi động Server Bot trên Laptop
1. Vào thư mục `bot_demo` và nhấp đúp file [`run.bat`](file:///c:/Users/VINH_QUANG/AndroidStudioProjects/CrawlBotApplication/bot_demo/run.bat) (hoặc gõ lệnh trong terminal):
   ```powershell
   java -Dfile.encoding=UTF-8 -jar target/crawler-bot-1.0.0.jar
   ```
2. Quan sát log xuất hiện trên terminal, xác nhận xuất hiện dòng thông báo:
   ```text
   >>> [REST API] Server REST API da khoi dong tai: http://localhost:8080/api/
   >>> [REST API - SECURITY] Xac thuc API Token duoc BAT (Authorization: Bearer <token> hoac X-API-Key)
   ```
3. Giữ nguyên cửa sổ terminal này để Server tiếp tục lắng nghe.

### Bước 4.2: Khởi động Ứng dụng Android từ Android Studio
1. Mở cửa sổ Android Studio.
2. Tại thanh công cụ trên cùng, chọn thiết bị đích (ví dụ: `Pixel 7 API 34`).
3. Nhấn nút tam giác màu xanh **Run 'app'** (Phím tắt: `Shift + F10`).
4. Ứng dụng sẽ được build và tự động cài đặt lên Emulator.

### Bước 4.3: Cấu hình địa chỉ mạng (URL) trên App

```
+-----------------------------------+--------------------------------------------+
| Loại Thiết Bị Kiểm Thử            | Cấu Hình Server URL Trên Màn Hình App      |
+-----------------------------------+--------------------------------------------+
| 💻 Android Emulator (Máy ảo)       | http://10.0.2.2:8080/api/ (Mặc định)       |
| 📱 Thiết Bị Thật (Cáp / Mạng WiFi) | http://<IP_LAN_MAY_TINH>:8080/api/         |
+-----------------------------------+--------------------------------------------+
```

- **Khi dùng Android Emulator**:
  - Địa chỉ IP `10.0.2.2` là alias đặc biệt của trình giả lập Android trỏ ngược về `127.0.0.1` (localhost) trên laptop.
  - Bạn **giữ nguyên mặc định** `http://10.0.2.2:8080/api/` trên ô nhập của app.
- **Khi dùng Điện thoại Android thật**:
  - Mở CMD trên laptop gõ `ipconfig` để lấy địa chỉ IPv4 LAN (ví dụ: `192.168.1.50`).
  - Đảm bảo điện thoại và laptop kết nối chung một mạng Wi-Fi.
  - Trên màn hình Android App, sửa ô Server URL thành: `http://192.168.1.50:8080/api/`.
  - Mở cổng 8080 trên Windows Defender Firewall nếu bị chặn.

---

## 🧪 5. Hướng Dẫn Kiểm Thử (Testing Guide)

### 5.1. Chạy Bộ Kiểm Thử Tự Động (Automated Unit & Integration Tests)
Mở terminal tại thư mục `bot_demo`:
```powershell
mvn test
```
Hệ thống sẽ tự động thực thi 8 test cases:
- `PythonBridgeTest`: Kiểm tra kết nối subprocess Python, cào dữ liệu thực tế VnExpress và cơ chế chặn SSRF.
- `UrlSecurityValidatorTest`: Kiểm tra whitelist domain và chặn dải IP mạng riêng nội bộ.
- `DatabaseManagerTest`: Kiểm tra khởi tạo schema SQLite, giao dịch ghi nhận biến động giá xăng và lịch sử cập nhật.

### 5.2. Kiểm Thử Tính Năng Thực Tế Trên Android App

| Mục Test | Thao Tác | Kết Quả Mong Đợi |
|:---|:---|:---|
| **Đồng bộ dữ liệu** | Bấm nút **"ĐỒNG BỘ"** trên thanh công cụ | Thanh tiến trình chạy, thanh trạng thái hiển thị *"Đồng bộ & phát thông tin thành công lúc HH:mm:ss"*. Bảng giá xăng và thời tiết hiển thị ngay lập tức. |
| **Kích hoạt Crawl tức thì** | Bấm nút **"CRAWL BOT"** | Gửi lệnh POST `/api/crawl/trigger` lên Server. Server khởi chạy Python cào web, sau 3.5 giây app tự động đồng bộ dữ liệu mới nhất. |
| **Xem Tab 1: Giá Xăng** | Chuyển sang Tab **"GIÁ XĂNG DẦU"** | Hiển thị danh sách các loại xăng Petrolimex & VnExpress (RON 95-III, E5 RON 92, DO 0.05S, dầu hỏa) kèm giá vùng 1, vùng 2 và mức chênh lệch. |
| **Xem Tab 2: Thời Tiết** | Chuyển sang Tab **"THỜI TIẾT"** | Hiển thị thẻ thời tiết trực quan: Nhiệt độ (°C), Tình trạng trời, Độ ẩm (%), Hướng gió, Chỉ số UV, Nguồn bài báo. |
| **Xem Tab 3: JSON Viewer** | Chuyển sang Tab **"XEM JSON"** | Hiển thị JSON có định dạng đẹp (pretty-print) của Bản tin tổng hợp, Thời tiết, Giá xăng. Có nút **"Sao chép JSON"** vào bộ nhớ đệm clipboard. |
| **Xem Tab 4: Lịch Sử Biến Động** | Chuyển sang Tab **"LỊCH SỬ BIẾN ĐỘNG"** | Hiển thị danh sách các đợt điều chỉnh giá xăng (Giá cũ $\rightarrow$ Giá mới, chênh lệch tăng/giảm và thông báo mô tả). |
| **Bật/Tắt Tự Động Đồng Bộ** | Bấm nút **"Phát Tự Động: BẬT / TẮT"** | Chuyển màu nút sang Xanh / Xám. Khi BẬT, Android WorkManager tự động lập lịch đồng bộ ngầm định kỳ 15 phút ngay cả khi tắt app. |
| **Thông Báo Biến Động (Push Notification)** | Giả lập cập nhật giá xăng hoặc cào đợt giá mới | Hệ thống hiển thị thông báo Notification trên khay thông báo Android với tiêu đề *"Cập nhật giá xăng dầu"* kèm nội dung mức điều chỉnh. |

---

## 🐞 6. Hướng Dẫn Debug Chuyên Sâu (Debugging Guide)

### 6.1. Debug Ứng Dụng Android trên Android Studio

#### Chạy Chế Độ Debugger:
1. Đặt Breakpoint (nhấp chuột vào lề trái cạnh số dòng code) tại các điểm quan trọng:
   - [`MainActivity.java`](file:///c:/Users/VINH_QUANG/AndroidStudioProjects/CrawlBotApplication/app/src/main/java/com/example/crawlbotapplication/MainActivity.java): Trong hàm `triggerCrawlOnServer()` và `performSync()`.
   - [`ServerApiClient.java`](file:///c:/Users/VINH_QUANG/AndroidStudioProjects/CrawlBotApplication/app/src/main/java/com/example/crawlbotapplication/network/ServerApiClient.java): Trong hàm `executeGet()` và `triggerServerCrawl()`.
   - [`SyncManager.java`](file:///c:/Users/VINH_QUANG/AndroidStudioProjects/CrawlBotApplication/app/src/main/java/com/example/crawlbotapplication/service/SyncManager.java): Trong hàm `saveGasolineToDb()` và `saveWeatherToDb()`.
2. Bấm biểu tượng con bọ xanh **Debug 'app'** (Phím tắt: `Shift + F9`).
3. Khi app dừng tại breakpoint, sử dụng các phím:
   - `F8` (Step Over): Chạy sang dòng tiếp theo.
   - `F7` (Step Into): Nhảy vào bên trong phương thức.
   - `Alt + F8` (Evaluate Expression): Tính toán giá trị biến hoặc biểu thức tùy biến.

#### Theo Dõi Nhật Ký Logcat:
1. Mở tab **Logcat** ở cạnh dưới Android Studio.
2. Trong thanh tìm kiếm của Logcat, lọc theo các từ khóa:
   - `tag:ServerApiClient`: Theo dõi URL kết nối, HTTP code và thời gian phản hồi.
   - `tag:SyncWorker`: Theo dõi tiến trình đồng bộ ngầm của WorkManager.
   - `level:error`: Chỉ lọc các ngoại lệ và lỗi nghiêm trọng.

#### Kiểm Tra Cơ Sở Dữ Liệu Trực Tiếp (App Inspection):
1. Vào menu **View** $\rightarrow$ **Tool Windows** $\rightarrow$ **App Inspection**.
2. Chọn tab **Database Inspector**.
3. Chọn database `crawl_bot_db`. Bạn có thể:
   - Xem dữ liệu bảng `gasoline_items`, `weather_items`, `change_events` theo thời gian thực (Live Updates).
   - Chạy trực tiếp các câu lệnh SQL kiểm tra: `SELECT * FROM gasoline_items WHERE diff != '0';`.

---

### 6.2. Debug Máy Chủ Bot trên Terminal

Khi chạy file `run.bat`, bạn có thể nhập các lệnh sau trực tiếp vào terminal để điều khiển và debug:
- `check`: Ép Bot cào và kiểm tra dữ liệu tất cả các trang web ngay lập tức mà không cần chờ chu kỳ 12 tiếng.
- `list`: Hiển thị toàn bộ các URL đang giám sát kèm mã băm SHA-256 nội dung hiện thời.
- `help`: Xem danh sách câu lệnh hỗ trợ.
- `exit` hoặc `quit`: Dừng bot và đóng Server REST API an toàn.

#### Kiểm Tra File CSDL SQLite Phía Server:
File SQLite server nằm tại [`bot_demo/data/crawler_server.db`](file:///c:/Users/VINH_QUANG/AndroidStudioProjects/CrawlBotApplication/bot_demo/data/crawler_server.db). Bạn có thể dùng công cụ [DB Browser for SQLite](https://sqlitebrowser.org/) hoặc extension DBeaver để mở và xem dữ liệu cào được.

---

## ⚠️ 7. Bảng Xử Lý Sự Cố Thường Gặp (Troubleshooting)

### Sự Cố 1: `fail to connect to /10.0.2.2 (port: 8080) ... after 10000ms`
- **Nguyên nhân:**
  1. Bạn chưa chạy máy chủ Java Bot Server (`run.bat`) trên laptop.
  2. Server khởi động nhưng bị lỗi tranh chấp cổng 8080 nên REST API bị tắt.
- **Cách khắc phục:**
  1. Mở terminal kiểm tra xem cổng 8080 có đang mở: `netstat -ano | findstr :8080`.
  2. Chạy `bot_demo/run.bat` và xác nhận log xuất hiện dòng `>>> [REST API] Server REST API da khoi dong tai: http://localhost:8080/api/`.

### Sự Cố 2: `[CANH BAO NGUY HIEM] Address already in use: bind`
- **Nguyên nhân:** Cổng 8080 đang bị ứng dụng khác chiếm (XAMPP Apache, Tomcat, Oracle XE, hoặc tiến trình Java cũ chưa tắt).
- **Cách khắc phục:**
  1. Tìm tiến trình chiếm cổng: `netstat -ano | findstr :8080`.
  2. Tắt tiến trình đó: `taskkill /PID <PID> /F`.
  3. Hoặc đổi cổng REST API sang cổng khác trong file [`bot_demo/config.properties`](file:///c:/Users/VINH_QUANG/AndroidStudioProjects/CrawlBotApplication/bot_demo/config.properties):
     ```properties
     server.api.port=8088
     ```
     Đồng thời cập nhật Server URL trên ứng dụng Android tương ứng: `http://10.0.2.2:8088/api/`.

### Sự Cố 3: `401 Unauthorized: Sai hoặc thiếu token xác thực bảo mật API`
- **Nguyên nhân:** Khóa `server.api.auth.token` trên Server khác với mã Token nhập trên ô cài đặt của Android App.
- **Cách khắc phục:** Kiểm tra file `bot_demo/config.properties` xem giá trị `server.api.auth.token` là gì (mặc định: `crawlbot_secret_token_2026`) và điền chính xác vào ô **Token API** trên màn hình Android App.

### Sự Cố 4: `CLEARTEXT communication to 10.0.2.2 not permitted by network security policy`
- **Nguyên nhân:** Android 9.0+ chặn kết nối HTTP không mã hóa (chỉ cho phép HTTPS) theo mặc định.
- **Cách khắc phục:** Dự án đã được cấu hình cờ `android:usesCleartextTraffic="true"` sẵn trong [`AndroidManifest.xml`](file:///c:/Users/VINH_QUANG/AndroidStudioProjects/CrawlBotApplication/app/src/main/AndroidManifest.xml). Hãy chắc chắn file manifest giữ nguyên cấu hình này.

---

## 🔒 8. Quản Lý Cấu Hình Khi Đẩy Lên GitHub

Để đảm bảo không lộ thông tin nhạy cảm (API Keys, Token, Keystores, CSDL cá nhân), vui lòng đọc kỹ tài liệu:
👉 **[CONFIGATION.md — Hướng Dẫn Cấu Hình & Bảo Mật Dự Án Khi Đẩy Lên GitHub](file:///c:/Users/VINH_QUANG/AndroidStudioProjects/CrawlBotApplication/CONFIGATION.md)**
