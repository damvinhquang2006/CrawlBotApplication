# 🤖 Java + Python Web Crawler & Update Monitor Bot (v2.0)

Bot tự động theo dõi và crawl thông tin từ các bài báo/trang web tĩnh cập nhật hàng ngày (ví dụ: **dự báo thời tiết**, **giá xăng dầu**, **AccuWeather API**, **VnExpress**, **Petrolimex**). Cứ mỗi khi nguồn dữ liệu có cập nhật HTML/JSON mới, bot sẽ tự động phát hiện bằng mã băm nội dung SHA-256 ổn định và in thông báo dữ liệu ra terminal dưới định dạng cấu trúc **JSON**.

---

## 🏗️ Kiến Trúc Hệ Thống (Java + Python Embedded)

```
                    +----------------------------------------------------+
                    |                Java Bot Application                |
                    |  - ScheduledExecutorService (định kỳ)              |
                    |  - UrlSecurityValidator: Chống SSRF & Whitelist    |
                    |  - Quản lý trạng thái SHA-256 băm                  |
                    |  - Phân tích & hiển thị thông báo JSON             |
                    +-------------------------+--------------------------+
                                              |
                       ProcessBuilder         |  Concurrent Async Stream Readers
                       (Hard Timeout 30s)     |  (Khắc phục triệt để Deadlock)
                                              v
                    +----------------------------------------------------+
                    |               Python Crawler Engine                |
                    |  (crawler.py: requests session + BeautifulSoup)    |
                    |  - Browser-grade headers & chống anti-bot          |
                    |  - DNS validation & safe redirect tracking (SSRF)  |
                    |  - VnExpress 2-Step Crawler: Chuyên đề -> Bài mới  |
                    |  - AccuWeather Developer API (Hourly JSON)         |
                    |  - Parser xăng dầu thông minh (Header + Data Type) |
                    |  - Tính toán mã băm SHA-256 ổn định                |
                    +-------------------------+--------------------------+
                                              |
                                              | Trả về JSON qua stdout
                                              v
                +--------------------------------------------------------+
                |     Cứ khi HTML/JSON thay đổi (Mã hash khác nhau)      |
                |     ==> Lập tức in thông báo dạng JSON ra Terminal     |
                +--------------------------------------------------------+
```

---

## 🛡️ Các Nâng Cấp Kỹ Thuật Quan Trọng (v2.0)

### 1. Khắc phục lỗi phân tích bảng giá xăng (`parse_gasoline`)
- **Trước đây:** Mặc định gán `zone_1_price = cols[1]`, `zone_2_price = cols[2]`. Khi gặp bảng 3 cột của VnExpress (`Mặt hàng | Giá bán | So với kỳ trước`), cột chênh lệch `+1.140` bị gán sai thành `zone_2_price`!
- **Hiện tại:** 
  - **Header Inspection:** Quét hàng tiêu đề để nhận diện đúng cột `product`, `price`, `zone_1_price`, `zone_2_price`, `diff`, `old_price`.
  - **Data-Type Validation:** Kiểm tra kiểu dữ liệu ô (cell heuristics). Các giá trị bắt đầu bằng dấu `+`/`-` hoặc chứa từ "tăng/giảm" chắc chắn được định danh là `diff` (chênh lệch), tuyệt đối không bao giờ bị nhầm thành giá vùng 2.
  - **Text Extractor:** Trích xuất giá và mức điều chỉnh tự động từ các đoạn văn bản nếu bài báo không dùng thẻ `<table>`.

### 2. Khắc phục triệt để Deadlock và Timeout (`PythonBridge.java`)
- **Trước đây:** Đọc tuần tự stdout rồi mới đọc stderr. Khi tiến trình Python in nhiều log/traceback vào stderr, bộ đệm pipe OS bị đầy dẫn tới deadlock. Không có timeout gây treo tiến trình vĩnh viễn nếu mạng lag.
- **Hiện tại:** 
  - Đọc bất đồng bộ đồng thời 2 stream `stdout` và `stderr` qua `CompletableFuture` trên `ExecutorService`, giải phóng bộ đệm liên tục.
  - Áp dụng timeout cứng (`waitFor(timeoutSeconds, TimeUnit.SECONDS)`). Nếu quá hạn, tiến trình sẽ bị hủy ngay lập tức (`destroyForcibly()`) và báo lỗi chi tiết.

### 3. Phòng vệ chống tấn công SSRF (Server-Side Request Forgery)
- **Java (`UrlSecurityValidator.java`)**: Kiểm tra scheme (chỉ `http`/`https`), phân giải DNS và chặn dải IP nội bộ/nguy hiểm (`127.0.0.0/8`, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `169.254.0.0/16`, `::1`, `fc00::/7`).
- **Python (`validate_safe_url` & safe redirect)**: Xác thực từng bước redirect (tối đa 5 hops). Nếu redirect trỏ tới IP private, ngắt kết nối ngay lập tức.
- **Chế độ kiểm thử an toàn:** Chỉ cho phép truy cập `localhost`/`127.0.0.1` khi người dùng cố ý bật cờ `bot.allow.local.mock=true`.

### 4. Hỗ trợ VnExpress - Cơ chế Crawler 2 bước (Two-Step Crawler)
- Domain chuẩn: `https://vnexpress.net`.
- Hỗ trợ URL chuyên đề: `https://vnexpress.net/chu-de/gia-xang-dau-3026`.
- Tự động quét chuyên đề, lọc ra bài báo mới nhất về điều chỉnh giá xăng (loại bỏ các bài không liên quan như giá dầu thô thế giới hay tin tức doanh nghiệp), crawl bài báo đó và bóc tách cấu trúc giá.

### 5. Tích hợp AccuWeather Developer API
- Hỗ trợ endpoint chính thức từ [AccuWeather Developer Portal](https://developer.accuweather.com):
  - Dự báo theo giờ (Hourly Forecast API): `https://dataservice.accuweather.com/forecasts/v1/hourly/1hour/{location_key}`
- Cấu hình qua `accuweather.api.key` và `accuweather.location.key` (mặc định: `353412` - Hà Nội).
- Bóc tách đầy đủ: Nhiệt độ (°C), RealFeel, mô tả thời tiết tiếng Việt, xác suất mưa (%), chỉ số UV.

---

## 📁 Cấu Trúc Dự Án

```
bot_demo/
├── pom.xml                                  # Cấu hình Maven (Java 17+, Jackson, JUnit 5)
├── config.properties                        # File cấu hình ngoài (URL, Timeout, API key, Targets)
├── run.bat                                  # File chạy nhanh Bot trên Windows
├── start_mock_server.bat                    # File chạy Mock Server phục vụ test local
├── python/
│   ├── crawler.py                           # Engine crawl thông minh, an toàn SSRF & xuất JSON
│   └── requirements.txt                     # Thư viện Python (requests, beautifulsoup4)
├── src/
│   ├── main/
│   │   ├── java/com/bot/
│   │   │   ├── BotApplication.java         # Điểm khởi chạy & CLI tương tác
│   │   │   ├── bridge/PythonBridge.java    # Bridge gọi Python chống Deadlock & Timeout
│   │   │   ├── config/BotConfig.java       # Nạp properties & validate SSRF
│   │   │   ├── model/
│   │   │   │   ├── TargetSite.java         # Model trang theo dõi
│   │   │   │   └── CrawlResponse.java      # Model kết quả JSON
│   │   │   ├── monitor/
│   │   │   │   └── WebMonitorService.java  # Dịch vụ định kỳ & phát hiện thay đổi
│   │   │   └── security/
│   │   │       └── UrlSecurityValidator.java # Bộ lọc chống SSRF
│   │   └── resources/
│   │       └── config.properties
│   └── test/
│       └── java/com/bot/
│           ├── bridge/PythonBridgeTest.java # Integration test cho crawler & bridge
│           └── security/UrlSecurityValidatorTest.java # Unit test chống SSRF
└── test_server/
    ├── mock_server.py                       # Server HTTP local mô phỏng cập nhật web
    └── mock_pages/
        ├── weather.html
        └── gas.html
```

---

## ⚡ Yêu Cầu Môi Trường & Cài Đặt

1. **Java**: JDK 17 trở lên.
2. **Apache Maven**: `mvn`.
3. **Python**: Python 3.8+ (đã cài `requests` và `beautifulsoup4`).
   ```bash
   py -m pip install -r python/requirements.txt
   ```

---

## 🚀 Hướng Dẫn Sử Dụng

### 1. Chạy theo dõi Live các nguồn thực tế:
Bot mặc định theo dõi 4 nguồn:
1. `thoitiet_live`: Dự báo thời tiết Hà Nội (`https://thoitiet.vn/ha-noi`)
2. `giaxang_petrolimex`: Bảng giá Petrolimex 2 vùng (`https://webgia.com/gia-xang-dau/petrolimex/`)
3. `vnexpress_gas`: Chuyên đề & bài báo mới nhất VnExpress (`https://vnexpress.net/chu-de/gia-xang-dau-3026`)
4. `accuweather_hanoi`: AccuWeather Developer API (`https://dataservice.accuweather.com/forecasts/v1/hourly/1hour/353412`)

Chạy bằng lệnh:
```bash
java -jar target/crawler-bot-1.0.0.jar
```
hoặc nhấp đúp file `run.bat`.

### 2. Chạy bộ kiểm thử tự động (Unit & Integration Tests):
```bash
mvn test
```
Tất cả 7 test cases bao gồm kiểm thử SSRF và kiểm thử trích xuất dữ liệu VnExpress sẽ được tự động thực thi.

---

## ⌨️ Các Lệnh Tương Tác Trên Terminal Bot

- `check`: Buộc bot kiểm tra ngay lập tức mà không cần đợi hết chu kỳ.
- `list`: Hiển thị danh sách các trang đang giám sát kèm mã băm SHA-256 hiện tại.
- `help`: Hiển thị hướng dẫn.
- `exit` hoặc `quit`: Dừng bot an toàn.
