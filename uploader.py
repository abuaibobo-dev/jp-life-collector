# -*- coding: utf-8 -*-
"""Telegram 图片上传：把 downloads/ 中未发送的图片批量发送到群/频道。"""
import json
import sqlite3
import time
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parent
MEDIA_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp"}
API = "https://api.telegram.org/bot{token}/{method}"


def load_config(path=ROOT / "config.json"):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def db():
    con = sqlite3.connect(ROOT / "sent.db")
    con.execute("CREATE TABLE IF NOT EXISTS sent(filename TEXT PRIMARY KEY, sent_at TEXT)")
    return con


def resolve_chat_id(bot_token):
    r = requests.get(API.format(token=bot_token, method="getUpdates"), timeout=20)
    updates = r.json().get("result", [])
    if not updates:
        return None
    return updates[-1]["message"]["chat"]["id"]


def build_caption(media_file: Path) -> str | None:
    info_path = media_file.with_suffix(".json")
    try:
        with open(info_path, "r", encoding="utf-8") as f:
            info = json.load(f)
    except Exception:
        return None
    lines = []
    title = (info.get("title") or "").strip()
    if title:
        lines.append(title[:400])
    keyword = info.get("keyword")
    if keyword:
        lines.append(f"#关键词: {keyword}")
    page_url = info.get("page_url")
    if page_url:
        lines.append(page_url)
    caption = "\n".join(lines).strip()
    return caption[:1024] if caption else None


def media_keyword(media_file: Path) -> str:
    info_path = media_file.with_suffix(".json")
    try:
        with open(info_path, "r", encoding="utf-8") as f:
            info = json.load(f)
        return (info.get("keyword") or "").strip() or "未分类"
    except Exception:
        return "未分类"


def group_caption(files: list[Path]) -> str | None:
    """组图 caption：汇总本批关键词与数量，不贴单张链接。"""
    kw = media_keyword(files[0])
    return f"日本素材 | 关键词: {kw}\n共 {len(files)} 张"


def send_photo(bot_token: str, chat_id, media_file: Path, caption: str | None):
    with open(media_file, "rb") as fh:
        data = {"chat_id": chat_id}
        if caption:
            data["caption"] = caption
        return requests.post(
            API.format(token=bot_token, method="sendPhoto"),
            data=data,
            files={"photo": (media_file.name, fh)},
            timeout=(30, 600),
        )


def send_media_group(bot_token: str, chat_id, media_files: list[Path], caption: str | None):
    """一次发送组图（最多10张/相册）。成功返回 resp，否则抛异常。"""
    attach = []
    files = {}
    for i, f in enumerate(media_files):
        key = f"img{i}"
        attach.append({"type": "photo", "media": f"attach://{key}"})
        files[key] = (f.name, open(f, "rb"))
    data = {"chat_id": chat_id, "media": json.dumps(attach, ensure_ascii=False)}
    if caption:
        data["caption"] = caption
    try:
        return requests.post(
            API.format(token=bot_token, method="sendMediaGroup"),
            data=data,
            files=files,
            timeout=(60, 1200),
        )
    finally:
        for fh in files.values():
            fh[1].close()


def run(cfg: dict):
    tg = cfg.get("telegram") or {}
    bot_token = tg.get("bot_token")
    if not bot_token:
        print("[上传跳过] 未配置 telegram.bot_token")
        return
    chat_id = tg.get("chat_id")
    if not chat_id:
        print("[自动获取 chat_id] 请先向机器人发送任意消息（如 /start）...")
        chat_id = resolve_chat_id(bot_token)
        if not chat_id:
            print("[上传跳过] 无法获取 chat_id，请先私聊机器人发送 /start")
            return
        print(f"[自动 chat_id] 使用最近对话 chat_id={chat_id}")

    prefs = cfg.get("preferences", {})
    download_dir = ROOT / prefs.get("download_dir", "downloads")
    if not download_dir.exists():
        return

    con = db()
    sent_dir = ROOT / "sent"
    sent_dir.mkdir(exist_ok=True)

    queued = sorted(
        p for p in download_dir.iterdir()
        if p.suffix.lower() in MEDIA_EXTS and p.stat().st_size > 0
    )
    pending = [
        f for f in queued
        if con.execute("SELECT 1 FROM sent WHERE filename=?", (f.name,)).fetchone() is None
    ]
    if not pending:
        con.close()
        return

    max_images = int(prefs.get("max_images_send", 20) or 20)
    album_mode = bool(prefs.get("album_mode", True))
    pending = pending[:max_images]

    print(f"[上传] 发现 {len(pending)} 张待发送 (album={'开' if album_mode else '关'})")
    ok, fail = 0, 0

    def mark_sent(media, group=False):
        nonlocal ok
        now = time.strftime("%Y-%m-%d %H:%M:%S")
        con.execute("INSERT INTO sent(filename, sent_at) VALUES (?, ?)", (media.name, now))
        con.commit()
        size_mb = media.stat().st_size / 1024 / 1024
        print(f"    ✓ {media.name} ({size_mb:.1f}MB)")
        ok += 1
        if not cfg.get("keep_uploaded", False):
            meta = media.with_suffix(".json")
            media.replace(sent_dir / media.name)
            if meta.exists():
                meta.replace(sent_dir / meta.name)

    def send_single(media):
        caption = build_caption(media)
        resp = send_photo(bot_token, chat_id, media, caption)
        body = resp.json()
        if resp.status_code == 200 and body.get("ok"):
            print(f"[单张发送成功] {media.name}")
            mark_sent(media)
            return True
        print(f"[发送失败] {media.name}: {body.get('description') or resp.text[:300]}")
        return False

    if not album_mode:
        for media in pending:
            if media.stat().st_size / 1024 / 1024 > 9:
                print(f"[跳过>10MB] {media.name}")
                fail += 1
                continue
            try:
                if not send_single(media):
                    fail += 1
            except Exception as exc:
                print(f"[发送异常] {media.name}: {exc}")
                fail += 1
            time.sleep(1)
        print(f"[上传完成] 成功 {ok}，失败 {fail}，共 {len(pending)}")
        con.close()
        return

    # 组图模式：按关键词分组，每批最多10张
    groups = {}
    for media in pending:
        if media.stat().st_size / 1024 / 1024 > 9:
            print(f"[跳过>10MB] {media.name}")
            fail += 1
            continue
        groups.setdefault(media_keyword(media), []).append(media)

    for kw, files in groups.items():
        for i in range(0, len(files), 10):
            batch = files[i:i + 10]
            if len(batch) == 1:
                try:
                    if not send_single(batch[0]):
                        fail += 1
                except Exception as exc:
                    print(f"[发送异常] {batch[0].name}: {exc}")
                    fail += 1
                time.sleep(1)
                continue
            caption = group_caption(batch)
            try:
                resp = send_media_group(bot_token, chat_id, batch, caption)
                body = resp.json()
                if resp.status_code == 200 and body.get("ok"):
                    print(f"[组图发送成功] 关键词「{kw}」共 {len(batch)} 张")
                    for media in batch:
                        mark_sent(media)
                else:
                    print(f"[组图失败，回退单张] {kw}: {body.get('description') or resp.text[:200]}")
                    for media in batch:
                        try:
                            if send_single(media):
                                time.sleep(1)
                            else:
                                fail += 1
                        except Exception as exc:
                            print(f"[发送异常] {media.name}: {exc}")
                            fail += 1
            except Exception as exc:
                print(f"[组图异常] {kw}: {exc}")
                for media in batch:
                    try:
                        if send_single(media):
                            time.sleep(1)
                        else:
                            fail += 1
                    except Exception as exc2:
                        print(f"[发送异常] {media.name}: {exc2}")
                        fail += 1
            time.sleep(2)

    print(f"[上传完成] 成功 {ok}，失败 {fail}，共 {len(pending)}")
    con.close()


if __name__ == "__main__":
    run(load_config())