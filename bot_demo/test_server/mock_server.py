#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Mock Web Server for Testing Bot
Serves mock weather & gas price HTML pages on http://localhost:8085 (default)
Provides an interactive menu to simulate page updates in real-time.
"""

import os
import sys
import threading
import time
from http.server import SimpleHTTPRequestHandler, HTTPServer
import random

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8085
DIRECTORY = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'mock_pages')


class CustomHandler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=DIRECTORY, **kwargs)

    def log_message(self, format, *args):
        # Suppress noisy HTTP request logs unless error
        pass


def update_weather_page():
    weather_file = os.path.join(DIRECTORY, 'weather.html')
    temp = random.randint(20, 36)
    conditions = ["Trời nắng ráo", "Có mây rải rác", "Mưa rào nhẹ", "Mây cụm mát mẻ", "Trời nhiều mây"]
    cond = random.choice(conditions)
    humidity = random.randint(60, 95)
    now_str = time.strftime('%H:%M:%S %d/%m/%Y')

    content = f"""<!DOCTYPE html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <title>Bản tin Dự báo Thời tiết Hà Nội</title>
</head>
<body>
    <main>
        <h1>Dự báo thời tiết Hà Nội hôm nay</h1>
        <div class="current-temperature">{temp}.0°C</div>
        <div class="overview-caption-item">{cond}</div>
        <div class="details">
            <p class="overview-item">Độ ẩm: {humidity}%</p>
            <p class="overview-item">Gió: Đông Nam {random.randint(5, 25)} km/h</p>
            <p class="overview-item">Chỉ số UV: {random.randint(1, 10)}</p>
        </div>
        <p>Bản tin cập nhật lúc: {now_str}</p>
    </main>
</body>
</html>
"""
    with open(weather_file, 'w', encoding='utf-8') as f:
        f.write(content)
    print(f"\n[Mock Server] Đã cập nhật weather.html: {temp}°C, {cond}, cập nhật lúc {now_str}")


def update_gas_page():
    gas_file = os.path.join(DIRECTORY, 'gas.html')
    ron95 = 21000 + random.randint(-500, 500)
    e5 = 20000 + random.randint(-500, 500)
    now_str = time.strftime('%H:%M:%S %d/%m/%Y')

    content = f"""<!DOCTYPE html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <title>Giá xăng dầu bán lẻ hôm nay</title>
</head>
<body>
    <main>
        <h1>Bảng giá bán lẻ xăng dầu Petrolimex hôm nay</h1>
        <p>Thời gian áp dụng từ {now_str}</p>
        <table border="1">
            <thead>
                <tr>
                    <th>Mặt hàng</th>
                    <th>Vùng 1</th>
                    <th>Vùng 2</th>
                </tr>
            </thead>
            <tbody>
                <tr>
                    <td>Xăng RON 95-III</td>
                    <td>{ron95:,}</td>
                    <td>{ron95 + 420:,}</td>
                </tr>
                <tr>
                    <td>Xăng E5 RON 92-II</td>
                    <td>{e5:,}</td>
                    <td>{e5 + 400:,}</td>
                </tr>
                <tr>
                    <td>Dầu DO 0,05S-II</td>
                    <td>19,820</td>
                    <td>20,210</td>
                </tr>
            </tbody>
        </table>
    </main>
</body>
</html>
"""
    with open(gas_file, 'w', encoding='utf-8') as f:
        f.write(content)
    print(f"\n[Mock Server] Đã cập nhật gas.html: RON 95 = {ron95:,} đ, lúc {now_str}")


def interactive_cli():
    time.sleep(1)
    print("=" * 60)
    print(f" Mock Server đang chạy tại: http://localhost:{PORT}")
    print(f" URL Thời tiết: http://localhost:{PORT}/weather.html")
    print(f" URL Giá xăng : http://localhost:{PORT}/gas.html")
    print("=" * 60)
    print(" Nhập lệnh để giả lập cập nhật nội dung web:")
    print("  [1] Cập nhật thời tiết (đổi nhiệt độ, trạng thái)")
    print("  [2] Cập nhật giá xăng (đổi giá RON 95, E5)")
    print("  [q] Thoát server")
    print("=" * 60)

    while True:
        try:
            cmd = input("\n[Mock Server Command] > ").strip()
            if cmd == '1':
                update_weather_page()
            elif cmd == '2':
                update_gas_page()
            elif cmd.lower() == 'q':
                print("[Mock Server] Đang dừng server...")
                os._exit(0)
            else:
                print("Lệnh không hợp lệ. Chọn 1, 2 hoặc q.")
        except (EOFError, KeyboardInterrupt):
            break


def main():
    if sys.stdout.encoding != 'utf-8':
        try:
            sys.stdout.reconfigure(encoding='utf-8')
        except Exception:
            pass

    server = HTTPServer(('127.0.0.1', PORT), CustomHandler)
    server_thread = threading.Thread(target=server.serve_forever, daemon=True)
    server_thread.start()

    interactive_cli()


if __name__ == '__main__':
    main()
