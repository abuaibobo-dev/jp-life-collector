# -*- coding: utf-8 -*-
"""Bing 全网图片采集器（免费、无需注册、无需 API key）

按关键词搜索全网图片，筛选并下载到本地。
"""
import html
import json
import re
import time
import urllib.parse
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parent

UA = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.9",
    "Referer": "https://www.bing.com/",
}

IMAGES_EXT = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp"}
EXPECTED_ONLY = {".jpg", ".jpeg", ".png", ".webp"}
# 过滤掉明显不是照片的域名/路径（图标站、广告、追踪）
BAD_PATTERNS = (
    "logo", "icon", "advert", ".svg", "pixel", "spacer",
    "transparent", "banner", "sprite", "favicon",
)


def load_config(path=ROOT / "config.json"):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def search_results(keyword, first=1, count=30, timeout=25, mkt="ja-JP"):
    """搜索 Bing 图片，返回 [(image_url, page_url, title)] 去重列表。"""
    q = urllib.parse.quote(keyword)
    if mkt:
        url = (f"https://www.bing.com/images/search?q={q}&form=HDRSC2"
               f"&first={first}&setmkt={mkt}&cc=JP")
    else:
        url = f"https://www.bing.com/images/search?q={q}&form=HDRSC2&first={first}"
    resp = requests.get(url, headers=UA, timeout=timeout)
    resp.raise_for_status()
    data = []
    seen = set()
    for grp in re.finditer(r'm="({.*?})"', resp.text, re.S):
        try:
            d = json.loads(html.unescape(grp.group(1)))
        except Exception:
            continue
        murl = (d.get("murl") or "").strip()
        if not murl or murl in seen:
            continue
        seen.add(murl)
        data.append((murl, d.get("purl") or "", d.get("t") or ""))
        if len(data) >= count:
            break
    return data


def good_url(murl):
    path = urllib.parse.urlsplit(murl).path.lower()
    if "." not in path:
        return False
    ext = "." + path.rsplit(".", 1)[-1]
    if ext not in EXPECTED_ONLY:
        return False
    if any(p in murl.lower() for p in BAD_PATTERNS):
        return False
    return True


def safe_name(text, maxlen=60):
    val = re.sub(r'[\\/:*?"<>|\s]+', "_", (text or "img")).strip("_")
    val = val[:maxlen]
    return (val or "img") + "_" + str(int(time.time() * 1000))


def download_image(murl, dest: Path, timeout=30):
    """下载图片。返回 (bytes 大小, content_type) 或抛异常。"""
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer": urllib.parse.urlsplit(murl).scheme + "://" + urllib.parse.urlsplit(murl).netloc,
    }
    resp = requests.get(murl, headers=headers, timeout=timeout, allow_redirects=True)
    resp.raise_for_status()
    real = resp.content
    if len(real) < 4 * 1024:
        raise ValueError("too small")
    ct = (resp.headers.get("Content-Type") or "").lower()
    if "html" in ct:
        raise ValueError("html page instead of image")
    dest.write_bytes(real)
    return len(real), ct


def pick_extension(murl):
    path = urllib.parse.urlsplit(murl).path.lower()
    if "." not in path:
        return ".jpg"
    ext = "." + path.rsplit(".", 1)[-1]
    return ext if ext in EXPECTED_ONLY else ".jpg"


def collect_keyword(cfg, keyword, source_label="全网搜索", on_item=None):
    """针对单个关键词执行采集，返回已下载文件列表。"""
    s = cfg["sources"]
    prefs = cfg.get("preferences", {})
    download_dir = ROOT / prefs.get("download_dir", "downloads")
    download_dir.mkdir(parents=True, exist_ok=True)
    archive_file = ROOT / prefs.get("archive_file", "archive.txt")
    seen_urls = set()
    if archive_file.exists():
        seen_urls.update(archive_file.read_text(encoding="utf-8", errors="ignore").splitlines())

    per = int(s.get("max_per_keyword", 12) or 12)
    pages = max(1, int(s.get("search_pages", 3) or 3))
    mkt = prefs.get("search_market", "ja-JP")
    max_size = int(prefs.get("max_image_mb", 8) or 8)

    downloaded = []
    collected = 0
    for page in range(pages):
        first = page * 30 + 1
        results = []
        try:
            results = search_results(keyword, first=first, mkt=mkt)
        except Exception as exc:
            print(f"  [搜索失败][{source_label}] {keyword} page{page + 1}: {exc}")
            break
        for image_url, page_url, title in results:
            if not good_url(image_url):
                continue
            if image_url in seen_urls:
                continue
            ext = pick_extension(image_url)
            for attempt in range(2):
                try:
                    dest = download_dir / f"{safe_name(title, 36)}{ext}"
                    size, _ct = download_image(image_url, dest)
                    if size // 1024 // 1024 > max_size:
                        dest.unlink(missing_ok=True)
                        print(f"  [超限] {dest.name} ({size / 1024 / 1024:.1f}MB)")
                        continue
                except Exception as exc:
                    dest.unlink(missing_ok=True)
                    print(f"  [下载失败] {title[:20]} -> {exc}")
                    time.sleep(1)
                    continue
                json_file = dest.with_suffix(".json")
                json_file.write_text(
                    json.dumps({
                        "title": title, "image_url": image_url,
                        "page_url": page_url, "keyword": keyword,
                    }, ensure_ascii=False, indent=2),
                    encoding="utf-8",
                )
                with open(archive_file, "a", encoding="utf-8") as f:
                    f.write(image_url + "\n")
                seen_urls.add(image_url)
                collected += 1
                downloaded.append(dest)
                if on_item:
                    on_item(dest, image_url, page_url, title)
                print(f"  [已采集] {dest.name} ({size / 1024 / 1024:.1f}MB)")
                if collected >= per:
                    return downloaded
                break
        time.sleep(1)
    return downloaded


def run(cfg, on_item=None):
    """执行全量采集：遍历所有关键词。返回下载文件列表。"""
    s = cfg.get("sources", {})
    keywords = s.get("keywords", [])
    if not keywords:
        print("[采集跳过] 未配置 sources.keywords")
        return []
    prefs = cfg.get("preferences", {})
    keywords_per_round = int(prefs.get("keywords_per_round", 0) or 0)

    active = keywords
    if keywords_per_round > 0:
        active = keywords[:keywords_per_round]

    all_files = []
    for keyword in active:
        if not keyword.strip() or keyword.startswith("#"):
            continue
        print(f"[开始] 采集关键词: {keyword}")
        try:
            files = collect_keyword(cfg, keyword.strip(), "全网搜索", on_item=on_item)
            all_files.extend(files)
        except Exception as exc:
            print(f"[采集异常][{keyword}]: {exc}")
    print(f"[采集完成] 本轮新增 {len(all_files)} 张")
    return all_files


if __name__ == "__main__":
    run(load_config())