#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Python Crawler Engine - Advanced & Secure
- Anti-bot / Browser-grade headers & Session management
- SSRF (Server-Side Request Forgery) protection with DNS validation & safe redirect tracking
- Multi-source intelligent parser for gasoline prices:
  * Header inspection & column classification (Zone 1, Zone 2, Price, Diff/Change)
  * Data type validation (never confuse +1.140 diff with zone_2_price)
  * VnExpress 2-step crawler: topic -> latest article -> parse table/text
- AccuWeather Developer API integration: hourly forecast JSON parsing
- Stable content hashing for HTML/JSON change detection
"""

import sys
import os
import argparse
import hashlib
import json
import re
import socket
import ipaddress
from datetime import datetime
from urllib.parse import urlparse, urljoin
import requests
from bs4 import BeautifulSoup

# Ensure UTF-8 output across Windows and all platforms
if sys.stdout.encoding != 'utf-8':
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        pass
if sys.stderr.encoding != 'utf-8':
    try:
        sys.stderr.reconfigure(encoding='utf-8')
    except Exception:
        pass


# ==============================================================================
# SSRF PROTECTION & SECURE NETWORK REQUESTS
# ==============================================================================

def is_dangerous_ip(ip_str: str, allow_local_mock: bool = False) -> bool:
    """Checks whether an IP address belongs to loopback, private, or link-local ranges."""
    try:
        ip = ipaddress.ip_address(ip_str)
        if ip.is_loopback:
            return not allow_local_mock
        return (
            ip.is_private or
            ip.is_link_local or
            ip.is_reserved or
            ip.is_multicast or
            ip.is_unspecified
        )
    except ValueError:
        return True


def validate_safe_url(url: str, allow_local_mock: bool = False):
    """
    Validates URL scheme and resolves hostname to ensure it does not target
    internal network infrastructure (SSRF protection).
    """
    parsed = urlparse(url)
    if parsed.scheme.lower() not in ('http', 'https'):
        raise ValueError(f"SSRF Protection: Forbidden scheme '{parsed.scheme}'. Only HTTP/HTTPS allowed.")

    hostname = parsed.hostname
    if not hostname:
        raise ValueError(f"SSRF Protection: Invalid URL host in '{url}'.")

    hostname_lower = hostname.lower()
    if hostname_lower in ('localhost', '127.0.0.1', '::1'):
        if allow_local_mock:
            return
        raise ValueError("SSRF Protection: Access to localhost/loopback is blocked.")

    # DNS Resolution & IP checking
    port = parsed.port or (443 if parsed.scheme.lower() == 'https' else 80)
    try:
        addr_infos = socket.getaddrinfo(hostname, port, socket.AF_UNSPEC, socket.SOCK_STREAM)
        for family, socktype, proto, canonname, sockaddr in addr_infos:
            ip_str = sockaddr[0]
            if is_dangerous_ip(ip_str, allow_local_mock):
                raise ValueError(
                    f"SSRF Protection: Host '{hostname}' resolves to dangerous/internal IP {ip_str}."
                )
    except socket.gaierror as e:
        raise ValueError(f"DNS Resolution failed for host '{hostname}': {e}")


def safe_fetch(url: str, timeout: int = 15, allow_local_mock: bool = False, max_redirects: int = 5) -> requests.Response:
    """
    Fetches content via HTTP/HTTPS while validating every redirect step against SSRF
    and applying browser-grade headers to avoid anti-bot blocks.
    """
    session = requests.Session()
    session.headers.update({
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
                      '(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
        'Accept-Language': 'vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7',
        'Sec-Ch-Ua': '"Chromium";v="124", "Google Chrome";v="124", "Not-A.Brand";v="99"',
        'Sec-Ch-Ua-Mobile': '?0',
        'Sec-Ch-Ua-Platform': '"Windows"',
        'Sec-Fetch-Dest': 'document',
        'Sec-Fetch-Mode': 'navigate',
        'Sec-Fetch-Site': 'none',
        'Sec-Fetch-User': '?1',
        'Upgrade-Insecure-Requests': '1'
    })

    current_url = url
    redirect_count = 0

    while True:
        validate_safe_url(current_url, allow_local_mock=allow_local_mock)
        resp = session.get(current_url, timeout=timeout, allow_redirects=False)

        # Handle anti-bot responses explicitly
        if resp.status_code in (403, 429, 503):
            soup_check = BeautifulSoup(resp.text[:2000], 'html.parser')
            page_text = soup_check.get_text().lower()
            if any(k in page_text for k in ['captcha', 'cloudflare', 'waf', 'access denied', 'unusual traffic', 'perimeterx', 'datadome']):
                raise RuntimeError(
                    f"[BOT_PROTECTION_BLOCKED] Website triggered anti-bot protection (HTTP {resp.status_code}) on {current_url}"
                )

        if resp.is_redirect or resp.is_permanent_redirect:
            redirect_count += 1
            if redirect_count > max_redirects:
                raise RuntimeError(f"Too many redirects (exceeded limit of {max_redirects})")
            location = resp.headers.get('Location')
            if not location:
                break
            current_url = urljoin(current_url, location)
            continue
        else:
            break

    resp.raise_for_status()

    # Enforce UTF-8 if charset is unset or standard ISO-8859-1 fallback
    if resp.encoding is None or resp.encoding.lower() == 'iso-8859-1':
        resp.encoding = resp.apparent_encoding or 'utf-8'

    return resp


# ==============================================================================
# CONTENT HASHING FOR UPDATE DETECTION
# ==============================================================================

def compute_content_hash(soup: BeautifulSoup) -> str:
    """Computes a deterministic hash of main content, ignoring transient ads/scripts."""
    clone = BeautifulSoup(str(soup), 'html.parser')
    for tag in clone(['script', 'style', 'meta', 'noscript', 'svg', 'iframe', 'header', 'footer']):
        tag.decompose()

    target = (
        clone.find('article') or
        clone.find('main') or
        clone.find(id=re.compile(r'(content|main|article|table)', re.I)) or
        clone.find(class_=re.compile(r'(content|main|article|table|weather|petrol)', re.I)) or
        clone.find('body') or
        clone
    )

    clean_text = ' '.join(target.get_text(separator=' ').split())
    return hashlib.sha256(clean_text.encode('utf-8')).hexdigest()


# ==============================================================================
# INTELLIGENT GASOLINE PARSER (Header Inspection + Data Type Validation)
# ==============================================================================

def analyze_columns(headers: list) -> dict:
    """
    Analyzes table column headers to accurately assign indices:
    - product
    - price (or zone_1)
    - zone_2
    - diff (change compared to previous period, e.g. +1.140, -500)
    - old_price
    """
    col_map = {
        'product': -1,
        'zone_1': -1,
        'zone_2': -1,
        'price': -1,
        'diff': -1,
        'old_price': -1
    }

    for idx, raw_h in enumerate(headers):
        h = raw_h.strip().lower()

        # Product column
        if any(k in h for k in ['mặt hàng', 'loại xăng', 'sản phẩm', 'tên', 'danh mục', 'loại dầu']):
            col_map['product'] = idx
        # Zone 2 column
        elif any(k in h for k in ['vùng 2', 'vùng ii', 'zone 2', 'vùng-2']):
            col_map['zone_2'] = idx
        # Zone 1 column
        elif any(k in h for k in ['vùng 1', 'vùng i', 'zone 1', 'vùng-1']):
            col_map['zone_1'] = idx
        # Diff / Change column
        elif any(k in h for k in ['so với', 'thay đổi', 'chênh lệch', 'tăng/giảm', 'tăng giảm', '+/-', 'biến động']):
            col_map['diff'] = idx
        # Old price column
        elif any(k in h for k in ['giá cũ', 'kỳ trước']):
            col_map['old_price'] = idx
        # Price column (retail price / new price)
        elif any(k in h for k in ['giá bán', 'giá mới', 'giá hiện hành', 'đơn giá', 'giá niêm yết', 'mức giá']):
            col_map['price'] = idx

    # If first column is not mapped, it is usually product
    if col_map['product'] == -1 and len(headers) > 0:
        col_map['product'] = 0

    return col_map


def is_diff_value(val_str: str) -> bool:
    """
    Checks if a string represents a price diff/change (e.g. "+1.140", "-500", "+0", "tăng 300")
    rather than a full fuel price.
    """
    if not val_str:
        return False
    v = val_str.strip().lower()
    if v.startswith('+') or v.startswith('-'):
        return True
    if any(k in v for k in ['tăng', 'giảm', 'hạ']):
        return True
    # If the number is very small compared to normal fuel prices (e.g., < 2000 while fuel is > 20000)
    clean_num = re.sub(r'[^\d]', '', v)
    if clean_num:
        try:
            n = int(clean_num)
            if n < 2000 and ('+' in v or '-' in v or '0' == v):
                return True
        except ValueError:
            pass
    return False


def parse_gasoline_table(table, url: str) -> list:
    """
    Parses a single HTML table using header inspection & data-type validation.
    Guarantees that diff columns (+1.140) are NEVER confused with zone_2_price.
    """
    rows = table.find_all('tr')
    if not rows:
        return []

    # 1. Determine headers
    header_cols = []
    header_row_idx = -1
    for r_idx, row in enumerate(rows[:3]):
        th_elements = row.find_all('th')
        cells = row.find_all(['th', 'td'])
        texts = [c.get_text(strip=True) for c in cells]
        combined = " ".join(texts).lower()

        # Check if row looks like an actual product data row rather than a header
        is_data_row = any(p in combined for p in ['ron 92', 'ron 95', 'e5 ', 'e10 ', 'do 0', '0,05s', '0,001s'])

        if not is_data_row:
            has_header_keywords = any(k in combined for k in ['mặt hàng', 'sản phẩm', 'loại xăng', 'vùng 1', 'vùng 2', 'giá bán', 'đơn giá', 'so với', 'thay đổi', 'chênh lệch'])
            if th_elements or has_header_keywords:
                header_cols = texts
                header_row_idx = r_idx
                break

    col_map = analyze_columns(header_cols) if header_cols else {}

    results = []
    start_row = header_row_idx + 1 if header_row_idx != -1 else 0

    for row in rows[start_row:]:
        cells = [c.get_text(strip=True) for c in row.find_all(['td', 'th'])]
        if len(cells) < 2:
            continue

        first_text = cells[0].lower()
        if not any(k in first_text for k in ['xăng', 'dầu', 'ron', 'e5', 'e10', 'diesel', 'do ', 'hỏa', 'mazut']):
            continue

        item = {
            "product": cells[0],
            "price": None,
            "zone_1_price": None,
            "zone_2_price": None,
            "diff": None,
            "old_price": None
        }

        # Case A: If column mapping was determined from headers
        if col_map and col_map.get('product', -1) != -1:
            prod_idx = col_map['product']
            item["product"] = cells[prod_idx] if prod_idx < len(cells) else cells[0]

            if col_map.get('zone_1', -1) != -1 and col_map['zone_1'] < len(cells):
                item["zone_1_price"] = cells[col_map['zone_1']]
                item["price"] = item["zone_1_price"]

            if col_map.get('zone_2', -1) != -1 and col_map['zone_2'] < len(cells):
                z2 = cells[col_map['zone_2']]
                # Verify that zone_2 is NOT a diff value
                if not is_diff_value(z2):
                    item["zone_2_price"] = z2

            if col_map.get('price', -1) != -1 and col_map['price'] < len(cells):
                item["price"] = cells[col_map['price']]
                if not item["zone_1_price"]:
                    item["zone_1_price"] = item["price"]

            if col_map.get('diff', -1) != -1 and col_map['diff'] < len(cells):
                item["diff"] = cells[col_map['diff']]

            if col_map.get('old_price', -1) != -1 and col_map['old_price'] < len(cells):
                item["old_price"] = cells[col_map['old_price']]

        # Case B: Heuristic fallback with cell-value type checking
        if item["price"] is None and len(cells) >= 2:
            item["price"] = cells[1]
            item["zone_1_price"] = cells[1]

            if len(cells) >= 3:
                third_cell = cells[2]
                if is_diff_value(third_cell):
                    # It's a diff column (+1.140), NOT zone 2!
                    item["diff"] = third_cell
                    item["zone_2_price"] = None
                else:
                    # Check if it looks like a legitimate zone 2 price
                    item["zone_2_price"] = third_cell

            if len(cells) >= 4:
                fourth_cell = cells[3]
                if is_diff_value(fourth_cell):
                    item["diff"] = fourth_cell
                elif not item["zone_2_price"]:
                    item["zone_2_price"] = fourth_cell

        results.append(item)

    return results


def parse_gasoline_from_text(soup: BeautifulSoup) -> list:
    """
    Extracts gasoline pricing from paragraph text when news articles
    (like VnExpress) report price changes inline instead of using an HTML table.
    """
    extracted = []
    paragraphs = [p.get_text(separator=' ', strip=True) for p in soup.find_all('p')]

    # Regex targeting Vietnamese retail gasoline news sentences
    pattern = re.compile(
        r'(?:giá\s+|các\s+mặt\s+hàng\s+|mỗi\s+lít\s+)?(xăng\s+[A-Za-z0-9\s\-]+?|dầu\s+[A-Za-z0-9\s\-]+?|e5\s+ron\s*92[A-Za-z0-9\s\-]*?|e10\s+ron\s*95[A-Za-z0-9\s\-]*?)\s+(giảm|hạ|tăng|lên|xuống)\s+([\d\.\,]+)?\s*(?:đồng)?[,\s]*(?:xuống|lên|ở\s+mức|có\s+giá\s+mới|là|ở)?\s*([\d\.\,]{4,10})\s*đồng',
        re.I
    )

    for p in paragraphs:
        for match in pattern.finditer(p):
            prod, action, diff, price = match.groups()
            prod_clean = prod.strip().title()
            if not prod_clean.lower().startswith(('xăng', 'dầu')):
                prod_clean = 'Xăng ' + prod_clean

            # Skip duplicates
            if any(e['product'].lower() == prod_clean.lower() for e in extracted):
                continue

            sign = '+' if ('tăng' in action.lower() or 'lên' in action.lower()) else '-'
            diff_str = f"{sign}{diff.strip()}" if diff else None
            price_str = price.strip() if price else None

            extracted.append({
                "product": prod_clean,
                "price": price_str,
                "zone_1_price": price_str,
                "zone_2_price": None,
                "diff": diff_str,
                "old_price": None
            })

    return extracted


def parse_gasoline(soup: BeautifulSoup, url: str) -> dict:
    """Parses gasoline tables or articles with header inspection and text fallback."""
    title_tag = soup.find('h1') or soup.find('title')
    title = title_tag.get_text(strip=True) if title_tag else "Bảng giá xăng dầu"

    prices = []
    # 1. Parse tables
    for table in soup.find_all('table'):
        table_results = parse_gasoline_table(table, url)
        if table_results:
            prices.extend(table_results)

    # 2. If no table results, fallback to text regex extraction
    if not prices:
        prices = parse_gasoline_from_text(soup)

    # Reported update time
    update_time = None
    time_match = re.search(r'(cập nhật|thời gian|lúc|ngày)[:\s]+([\d\:\-\/\s]{8,25})', soup.get_text(), re.I)
    if time_match:
        update_time = time_match.group(2).strip()

    return {
        "title": title,
        "source_url": url,
        "reported_time": update_time,
        "currency": "VND / Lít",
        "items_count": len(prices),
        "items": prices
    }


# ==============================================================================
# VNEXPRESS TWO-STEP CRAWLER (Topic -> Latest Article)
# ==============================================================================

def crawl_vnexpress_gasoline(topic_url: str, allow_local_mock: bool = False) -> dict:
    """
    Two-step crawler for VnExpress Gasoline Topic (https://vnexpress.net/chu-de/gia-xang-dau-3026):
    1. Fetches topic page to find the latest article on gasoline price adjustment.
    2. Fetches the article itself and parses structured prices, diffs, and lead summary.
    """
    resp_topic = safe_fetch(topic_url, allow_local_mock=allow_local_mock)
    soup_topic = BeautifulSoup(resp_topic.text, 'html.parser')

    # Find the latest article link with smart scoring
    scored_candidates = []
    for a in soup_topic.select('.title-news a, article.item-news a'):
        href = a.get('href', '')
        title = a.get_text(strip=True)
        if href and href.startswith('https://vnexpress.net/') and href.endswith('.html'):
            combined = (href + " " + title).lower()
            score = 0
            # Prioritize retail price adjustments
            if any(k in combined for k in ['giá xăng', 'điều chỉnh', 'giảm giá xăng', 'tăng giá xăng', 'xăng e5', 'xăng e10', 'ron 95', 'giá dầu diesel']):
                score += 15
            elif any(k in combined for k in ['xang', 'xăng', 'diesel', 'petrolimex']):
                score += 5

            # Penalize unrelated articles in topic (world oil barrel prices, court news, app promos)
            if any(k in combined for k in ['dầu thô', 'usd', 'thùng', 'tham ô', 'đại gia', 'iphone', 'tích điểm', 'dự thảo']):
                score -= 10

            scored_candidates.append((href, title, score))

    if scored_candidates:
        # Sort by score descending
        scored_candidates.sort(key=lambda x: x[2], reverse=True)
        latest_article_url, latest_article_title = scored_candidates[0][0], scored_candidates[0][1]
    else:
        # Fallback to direct parse of topic page
        data = parse_gasoline(soup_topic, topic_url)
        data["mode"] = "topic_direct"
        return data

    # Step 2: Fetch the actual article
    resp_article = safe_fetch(latest_article_url, allow_local_mock=allow_local_mock)
    soup_article = BeautifulSoup(resp_article.text, 'html.parser')

    article_h1 = soup_article.find('h1')
    full_title = article_h1.get_text(strip=True) if article_h1 else latest_article_title

    # Publication date / time
    pub_date = None
    date_elem = soup_article.find(class_='date') or soup_article.find(class_=re.compile(r'date', re.I))
    if date_elem:
        pub_date = date_elem.get_text(strip=True)

    # Lead summary
    lead = None
    desc_elem = soup_article.find(class_='description')
    if desc_elem:
        lead = desc_elem.get_text(strip=True)

    # Parse pricing details
    parsed_gas = parse_gasoline(soup_article, latest_article_url)

    return {
        "title": full_title,
        "topic_url": topic_url,
        "article_url": latest_article_url,
        "published_time": pub_date,
        "summary": lead,
        "currency": "VND / Lít",
        "items_count": parsed_gas["items_count"],
        "items": parsed_gas["items"]
    }


# ==============================================================================
# ACCUWEATHER DEVELOPER API INTEGRATION
# ==============================================================================

def fetch_accuweather_api(location_key: str, api_key: str, allow_local_mock: bool = False) -> dict:
    """
    Queries the official AccuWeather Developer API (Hourly Forecasts & Current Conditions).
    Returns structured JSON with temperature, weather phrase, precipitation probability, etc.
    """
    if not api_key:
        return {
            "error": "AccuWeather API key is not configured. Please set 'accuweather.api.key' in config.properties.",
            "guide": "Register a free developer account at https://developer.accuweather.com to obtain an API key (50 calls/day free).",
            "location_key": location_key
        }

    # 1-Hour Forecast Endpoint
    endpoint = f"https://dataservice.accuweather.com/forecasts/v1/hourly/1hour/{location_key}"
    params = {
        "apikey": api_key,
        "language": "vi-vn",
        "metric": "true",
        "details": "true"
    }

    resp = requests.get(endpoint, params=params, timeout=15)
    if resp.status_code == 401:
        return {
            "error": "AccuWeather API Key is unauthorized or invalid (HTTP 401).",
            "guide": "Verify your key at https://developer.accuweather.com.",
            "location_key": location_key
        }
    elif resp.status_code == 503:
        return {
            "error": "AccuWeather API daily rate limit exceeded (HTTP 503). Free tier limit is 50 requests/day.",
            "location_key": location_key
        }

    resp.raise_for_status()
    forecast_list = resp.json()

    if not isinstance(forecast_list, list) or not forecast_list:
        return {"error": "Unexpected JSON format from AccuWeather API", "raw": forecast_list}

    item = forecast_list[0]
    temp_val = item.get("Temperature", {}).get("Value")
    temp_unit = item.get("Temperature", {}).get("Unit", "C")
    real_feel = item.get("RealFeelTemperature", {}).get("Value")

    return {
        "title": f"Dự báo thời tiết AccuWeather (Location: {location_key})",
        "source": "AccuWeather Developer API",
        "location_key": location_key,
        "forecast_time": item.get("DateTime"),
        "temperature": f"{temp_val}°{temp_unit}" if temp_val is not None else "N/A",
        "real_feel": f"{real_feel}°{temp_unit}" if real_feel is not None else "N/A",
        "condition": item.get("IconPhrase", "N/A"),
        "has_precipitation": item.get("HasPrecipitation", False),
        "precipitation_probability": f"{item.get('PrecipitationProbability', 0)}%",
        "uv_index": item.get("UVIndex", 0),
        "details": {
            "link": item.get("Link"),
            "mobile_link": item.get("MobileLink")
        }
    }


# ==============================================================================
# WEATHER HTML PARSER
# ==============================================================================

def parse_weather(soup: BeautifulSoup, url: str) -> dict:
    """Parses static weather forecast pages (e.g. thoitiet.vn)."""
    title_tag = soup.find('h1') or soup.find('title')
    title = title_tag.get_text(strip=True) if title_tag else "Dự báo thời tiết"

    location = title
    city_elem = soup.find(class_=re.compile(r'(city|location-name|current-city)', re.I))
    if city_elem:
        location = city_elem.get_text(strip=True)

    temperature = None
    temp_elem = (
        soup.find(class_='current-temperature') or
        soup.find(class_=re.compile(r'(current-temp|temperature|temp)', re.I))
    )
    if temp_elem:
        temperature = temp_elem.get_text(strip=True)
    else:
        temp_match = re.search(r'(-?\d+(?:\.\d+)?)\s*°\s*C?', soup.get_text())
        if temp_match:
            temperature = temp_match.group(0).strip()

    condition = None
    cond_elem = (
        soup.find(class_='overview-caption-item') or
        soup.find(class_=re.compile(r'(weather-desc|condition-desc|sky-desc)', re.I))
    )
    if cond_elem:
        condition = cond_elem.get_text(strip=True)

    extra_details = {}
    full_text = soup.get_text()
    hum_m = re.search(r'Độ\s*ẩm\s*[:\s]*(\d+\s*%)', full_text, re.I)
    if hum_m:
        extra_details['humidity'] = hum_m.group(1).strip()
    wind_m = re.search(r'Gió\s*[:\s]*([\d\.]+\s*(?:km\/h|km\/giờ|m\/s)?)', full_text, re.I)
    if wind_m:
        extra_details['wind'] = wind_m.group(1).strip()
    uv_m = re.search(r'UV\s*Level[^\d]*(\d+)', full_text, re.I) or re.search(r'UV[^\d]*(\d+)', full_text, re.I)
    if uv_m:
        extra_details['uv_index'] = uv_m.group(1).strip()

    for item in soup.find_all(class_=re.compile(r'(detail|param|indicator|stat|overview-item)', re.I)):
        txt = ' '.join(item.get_text(strip=True).split())
        if 'độ ẩm' in txt.lower() and 'humidity' not in extra_details:
            extra_details['humidity'] = txt
        elif 'gió' in txt.lower() and 'wind' not in extra_details:
            extra_details['wind'] = txt
        elif 'uv' in txt.lower() and 'uv_index' not in extra_details:
            extra_details['uv_index'] = txt

    return {
        "title": title,
        "source_url": url,
        "location": location,
        "temperature": temperature or "N/A",
        "condition": condition or "N/A",
        "details": extra_details
    }


def parse_generic(soup: BeautifulSoup, url: str) -> dict:
    """Fallback generic text parser."""
    title_tag = soup.find('h1') or soup.find('title')
    title = title_tag.get_text(strip=True) if title_tag else "Bản tin cập nhật"
    paragraphs = [p.get_text(strip=True) for p in soup.find_all('p') if len(p.get_text(strip=True)) > 20]
    return {
        "title": title,
        "source_url": url,
        "excerpt": paragraphs[:3] if paragraphs else []
    }


def detect_category(url: str, soup: BeautifulSoup, requested_type: str) -> str:
    req = requested_type.lower()
    if 'accuweather' in req or 'dataservice.accuweather.com' in url:
        return "ACCUWEATHER_API"
    if 'vnexpress' in req or ('vnexpress.net' in url and 'xang' in url):
        return "VNEXPRESS_GASOLINE"
    if req in ['gasoline', 'weather']:
        return req.upper()

    haystack = (url + " " + soup.get_text()[:500]).lower()
    if any(k in haystack for k in ['xang', 'xăng', 'petrol', 'oil', 'gasoline', 'dau-diesel', 'gia-xang']):
        return "GASOLINE"
    if any(k in haystack for k in ['thoi-tiet', 'thời tiết', 'weather', 'khi-tuong', 'nhiet-do', 'nhiệt độ']):
        return "WEATHER"
    return "GENERAL"


# ==============================================================================
# MAIN ENTRYPOINT
# ==============================================================================

def main():
    parser = argparse.ArgumentParser(description="Secure Web Crawler & Change Detector")
    parser.add_argument("--url", required=True, help="Target URL to crawl or monitor")
    parser.add_argument("--type", default="auto", help="Data type parser to use")
    parser.add_argument("--last-hash", default="", help="Previous content hash")
    parser.add_argument("--allow-local-mock", action="store_true", help="Allow 127.0.0.1 for local test server")
    parser.add_argument("--accu-api-key", default="", help="AccuWeather Developer API key")
    parser.add_argument("--accu-location-key", default="353412", help="AccuWeather Location Key (Hanoi: 353412)")

    args = parser.parse_args()
    now_iso = datetime.now().isoformat()

    try:
        # 1. Handle AccuWeather Developer API
        if args.type == 'accuweather_api' or 'dataservice.accuweather.com' in args.url:
            location_key = args.accu_location_key
            # Extract location key from URL if embedded: .../hourly/1hour/353412
            url_match = re.search(r'/(?:hourly|currentconditions)/[^/]+/(\d+)', args.url)
            if url_match:
                location_key = url_match.group(1)

            accu_data = fetch_accuweather_api(
                location_key=location_key,
                api_key=args.accu_api_key,
                allow_local_mock=args.allow_local_mock
            )

            # Deterministic hash of structured data
            content_hash = hashlib.sha256(json.dumps(accu_data, sort_keys=True).encode('utf-8')).hexdigest()
            changed = not args.last_hash or (args.last_hash != content_hash)

            result = {
                "success": True,
                "changed": changed,
                "url": args.url,
                "category": "ACCUWEATHER_API",
                "hash": content_hash,
                "timestamp": now_iso
            }
            if changed:
                result["data"] = accu_data

            print(json.dumps(result, ensure_ascii=False))
            sys.exit(0)

        # 2. Handle VnExpress Gasoline Topic (Two-step crawler)
        if args.type == 'vnexpress_gasoline' or ('vnexpress.net' in args.url and '/chu-de/' in args.url):
            vnexpress_data = crawl_vnexpress_gasoline(args.url, allow_local_mock=args.allow_local_mock)
            content_hash = hashlib.sha256(json.dumps(vnexpress_data, sort_keys=True).encode('utf-8')).hexdigest()
            changed = not args.last_hash or (args.last_hash != content_hash)

            result = {
                "success": True,
                "changed": changed,
                "url": args.url,
                "category": "VNEXPRESS_GASOLINE",
                "hash": content_hash,
                "timestamp": now_iso
            }
            if changed:
                result["data"] = vnexpress_data

            print(json.dumps(result, ensure_ascii=False))
            sys.exit(0)

        # 3. Standard Web Fetching with SSRF Protection
        resp = safe_fetch(args.url, allow_local_mock=args.allow_local_mock)
        soup = BeautifulSoup(resp.text, 'html.parser')

        content_hash = compute_content_hash(soup)
        category = detect_category(args.url, soup, args.type)

        if args.last_hash and args.last_hash == content_hash:
            result = {
                "success": True,
                "changed": False,
                "url": args.url,
                "category": category,
                "hash": content_hash,
                "timestamp": now_iso
            }
        else:
            if category == "GASOLINE":
                parsed_data = parse_gasoline(soup, args.url)
            elif category == "WEATHER":
                parsed_data = parse_weather(soup, args.url)
            else:
                parsed_data = parse_generic(soup, args.url)

            result = {
                "success": True,
                "changed": True,
                "url": args.url,
                "category": category,
                "hash": content_hash,
                "timestamp": now_iso,
                "data": parsed_data
            }

        print(json.dumps(result, ensure_ascii=False))
        sys.exit(0)

    except Exception as e:
        error_result = {
            "success": False,
            "changed": False,
            "url": args.url,
            "error": str(e),
            "timestamp": now_iso
        }
        print(json.dumps(error_result, ensure_ascii=False))
        sys.exit(1)


if __name__ == '__main__':
    main()
