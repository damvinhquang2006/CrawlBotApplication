# 🛡️ HƯỚNG DẪN CẤU HÌNH & BẢO MẬT DỰ ÁN KHI ĐẨY LÊN GITHUB (CONFIGATION.MD)

Tài liệu này cung cấp danh sách chi tiết các file chứa thông tin nhạy cảm (API Keys, Secret Tokens, Local SDK Paths, Database Files, Keystores), giải thích lý do phải đưa vào `.gitignore` để tránh rò rỉ bảo mật, và hướng dẫn từng bước cấu hình lại dự án sau khi clone từ GitHub về một máy tính mới.

---

## 📋 1. Bảng Tổng Hợp Các File Cần Lưu Ý & Cấu Hình Lại

| STT | Tên File / Thư Mục | Mục Đích | Thông Tin Nhạy Cảm | Trạng Thái Trong `.gitignore` | Hành Động Khi Đẩy Lên Git | Hành Động Sau Khi Clone Về |
|:---:|:---|:---|:---|:---:|:---|:---|
| **1** | `bot_demo/config.properties` | Cấu hình máy chủ Bot Crawler & REST API | `accuweather.api.key`, `server.api.auth.token`, `database.path` | **ĐÃ CHẶN** | **KHÔNG commit** file thật. Commit file mẫu `config.properties.example`. | Copy từ `config.properties.example` thành `config.properties` và điền key/token của bạn. |
| **2** | `local.properties` | Đường dẫn SDK Android trên máy cá nhân | `sdk.dir=C:\Users\<USER>\...` | **ĐÃ CHẶN** | **KHÔNG commit**. Để Android Studio tự sinh hoặc tạo thủ công. | Mở dự án trong Android Studio để tự sinh file với SDK của máy mới. |
| **3** | `bot_demo/data/*.db` | Cơ sở dữ liệu SQLite phía Server | Dữ liệu cào thực tế, lịch sử biến động, mã băm SHA-256 | **ĐÃ CHẶN** | **KHÔNG commit**. Tránh xung đột lock file CSDL giữa các máy. | Tự động sinh khi chạy `run.bat` lần đầu (tạo bảng tự động). |
| **4** | `*.jks`, `*.keystore`, `key.properties` | Khóa ký số phát hành APK/AAB Android | Mật khẩu keystore, alias, private key của lập trình viên | **ĐÃ CHẶN** | **TUYỆT ĐỐI KHÔNG commit**. Lộ keystore sẽ bị mất quyền ứng dụng. | Tạo keystore riêng trên máy cá nhân khi cần build Release. |
| **5** | `google-services.json` *(nếu có)* | Cấu hình Firebase Cloud Messaging / Analytics | API Key Firebase, Client ID, Project ID | **ĐÃ CHẶN** | **KHÔNG commit** public repo. Dùng biến môi trường CI/CD. | Tải từ Firebase Console của dự án cá nhân và đặt vào `app/`. |
| **6** | `bot_demo/target/` & `app/build/` | Thư mục build sinh ra bởi Maven và Gradle | File `.jar`, `.apk`, `.class`, dex cache | **ĐÃ CHẶN** | **KHÔNG commit**. Rác dung lượng lớn làm nặng repository. | Tự sinh khi chạy `mvn package` và Gradle build. |
| **7** | `**/__pycache__/`, `*.pyc` | Bytecode cache của Python | Bytecode tương thích riêng từng phiên bản Python | **ĐÃ CHẶN** | **KHÔNG commit**. | Tự động sinh khi chạy script crawler. |

---

## 🔒 2. Chi Tiết Các File Nhạy Cảm & Cách Quản Lý

### 2.1. File `bot_demo/config.properties` (Máy Chủ Crawler)

File này chứa thông tin kết nối và khóa xác thực bí mật của toàn bộ hệ sinh thái:
```properties
# Khóa bí mật API: Dùng để xác thực yêu cầu từ Android Client đến Server
server.api.auth.token=crawlbot_secret_token_2026

# Khóa lập trình viên AccuWeather: Rò rỉ sẽ bị sử dụng hết hạn ngạch gọi API
accuweather.api.key=YOUR_ACTUAL_API_KEY
```

> [!CAUTION]
> **Nguy cơ bảo mật nếu bị lộ lên GitHub:**
> 1. Kẻ xấu có thể chiếm quyền gọi API trigger crawl liên tục gây quá tải (DoS).
> 2. Hạn ngạch miễn phí 50 calls/ngày của AccuWeather Developer API sẽ bị cạn kiệt, khiến tính năng thời tiết ngưng hoạt động.

#### Cách xử lý an toàn:
1. Đã thêm `bot_demo/config.properties` vào `.gitignore`.
2. Tạo file mẫu an toàn [`bot_demo/config.properties.example`](file:///c:/Users/VINH_QUANG/AndroidStudioProjects/CrawlBotApplication/bot_demo/config.properties.example) với các giá trị placeholder:
   ```properties
   server.api.port=8080
   server.api.auth.token=YOUR_SECURE_TOKEN_HERE
   accuweather.api.key=YOUR_ACCUWEATHER_KEY_HERE
   accuweather.location.key=353412
   ```
3. Commit file `config.properties.example` lên GitHub. File cấu hình thực tế `config.properties` sẽ chỉ nằm trên laptop cá nhân của bạn.

---

### 2.2. File `local.properties` (Android Studio SDK)

Chứa đường dẫn tuyệt đối tới Android SDK trên laptop hiện tại của bạn:
```properties
sdk.dir=C\:\\Users\\VINH_QUANG\\AppData\\Local\\Android\\Sdk
```

> [!WARNING]
> Tên tài khoản Windows (`VINH_QUANG`) và cấu trúc thư mục của mỗi máy là hoàn toàn khác nhau. Nếu commit file này lên GitHub:
> - Người khác clone về sẽ bị lỗi build Gradle ngay lập tức: `SDK location not found`.
> - Lộ thông tin cấu trúc thư mục và tên tài khoản người dùng trên hệ điều hành.

---

### 2.3. CSDL SQLite Phía Server (`bot_demo/data/crawler_server.db`)

Chứa toàn bộ bảng `gasoline_prices`, `weather_records`, `change_events`, và mã băm SHA-256 nội dung HTML:
- File CSDL nhị phân rất dễ gây xung đột merge conflict không thể xử lý trên Git.
- Hệ thống đã được thiết kế cơ chế tự tạo bảng tự động ([`DatabaseManager.java`](file:///c:/Users/VINH_QUANG/AndroidStudioProjects/CrawlBotApplication/bot_demo/src/main/java/com/bot/db/DatabaseManager.java)) ngay khi server khởi động lần đầu nếu file chưa tồn tại.

---

## 🛠️ 3. Hướng Dẫn Từng Bước Thiết Lập Lại Sau Khi Clone Từ GitHub

Khi bạn hoặc đồng nghiệp tải mã nguồn từ GitHub về một máy tính mới, hãy làm theo các bước chuẩn xác sau:

### Bước 1: Khởi tạo file cấu hình cho Server Bot
Vào thư mục `bot_demo/` và tạo bản sao từ file mẫu:
```powershell
# Trên Windows PowerShell:
cd bot_demo
Copy-Item config.properties.example config.properties
```
Mở file `config.properties` vừa tạo và điền các thông tin của bạn:
- `server.api.port`: Giữ nguyên `8080` (hoặc đổi nếu máy bạn trùng cổng XAMPP/Apache).
- `server.api.auth.token`: Đặt mật mã token tùy ý (ví dụ: `crawlbot_secret_token_2026`).
- `accuweather.api.key`: Đăng ký tài khoản miễn phí tại [AccuWeather Developer](https://developer.accuweather.com) và dán API Key vào đây.

### Bước 2: Cài đặt thư viện Python
Tại thư mục `bot_demo/`:
```bash
py -m pip install -r python/requirements.txt
```

### Bước 3: Biên dịch Server Java
```bash
mvn clean package -DskipTests
```
Lệnh này sẽ tải các thư viện Maven (Jackson, SQLite JDBC, JUnit) và tạo ra file `target/crawler-bot-1.0.0.jar`.

### Bước 4: Mở dự án trên Android Studio & Sinh `local.properties`
1. Khởi động **Android Studio** $\rightarrow$ chọn **Open** $\rightarrow$ Trỏ tới thư mục gốc `CrawlBotApplication`.
2. Android Studio sẽ tự động phát hiện Android SDK trên máy mới và tự sinh file `local.properties` phù hợp.
3. Chờ Android Studio đồng bộ Gradle (`Gradle Sync`).

### Bước 5: Đồng bộ Token và Server URL trên Android App
Nếu bạn đã thay đổi `server.api.auth.token` hoặc `server.api.port` ở Bước 1, hãy đảm bảo trên Android App:
1. Mở app trên Emulator hoặc máy thật.
2. Trên màn hình chính, kiểm tra ô **URL Máy Chủ** (`http://10.0.2.2:8080/api/`) và ô **Token API**.
3. Điền đúng Token khớp với `server.api.auth.token` trong `config.properties` để không bị lỗi `401 Unauthorized`.

---

## 🛡️ 4. Checklist An Toàn Trước Khi Thực Hiện `git push`

Chạy các lệnh kiểm tra sau trên terminal để đảm bảo 100% không đẩy file bí mật lên remote:

```powershell
# 1. Kiểm tra danh sách các file đang được git theo dõi:
git status

# 2. Đảm bảo KHÔNG có các file sau trong danh sách "Changes to be committed":
#    [x] local.properties
#    [x] bot_demo/config.properties
#    [x] bot_demo/data/crawler_server.db
#    [x] bot_demo/target/
#    [x] app/build/
#    [x] *.keystore / *.jks

# 3. Nếu lỡ thêm nhầm file cấu hình vào git tracking, hãy hủy theo dõi ngay bằng lệnh:
git rm --cached bot_demo/config.properties
git rm --cached local.properties
```

---
*Tài liệu này được tạo nhằm đảm bảo an toàn thông tin theo tiêu chuẩn bảo mật phát triển ứng dụng (DevSecOps).*
