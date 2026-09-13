# -*- coding: utf-8 -*-
"""日本素材采集控制台（Flask 单文件面板）"""
import json
import os
import sqlite3
import sys
import threading
import time
from collections import deque
from pathlib import Path

from flask import Flask, jsonify, request

import scraper
import uploader

ROOT = Path(__file__).resolve().parent
CONFIG_PATH = ROOT / "config.json"
LOG_PATH = ROOT / "runtime.log"

app = Flask(__name__)
lock = threading.Lock()
loop_flag = threading.Event()
running = False
last_run = None
logs = deque(maxlen=800)


class Tee:
    def __init__(self, sink, fileobj):
        self.sink = sink
        self.fileobj = fileobj

    def write(self, s):
        if isinstance(s, bytes):
            s = s.decode("utf-8", errors="replace")
        if s:
            self.sink.write(s)
            if not s.endswith("\n"):
                self.sink.flush()
            logs.append(s.rstrip("\n"))
            with open(LOG_PATH, "a", encoding="utf-8") as f:
                f.write(s)
        return len(s)

    def flush(self):
        self.sink.flush()


def load_config():
    with open(CONFIG_PATH, "r", encoding="utf-8") as f:
        return json.load(f)


def save_config(cfg):
    with open(CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)


def do_run():
    global running, last_run
    if not lock.acquire(blocking=False):
        return False
    running = True
    last_run = time.strftime("%Y-%m-%d %H:%M:%S")
    try:
        cfg = load_config()
        print(f"\n===== 开始一轮采集+发送 {last_run} =====")
        scraper.run(cfg)
        uploader.run(cfg)
        print(f"===== 本轮完成 {time.strftime('%H:%M:%S')} =====\n")
    except Exception as exc:
        print(f"[异常] {exc}")
    finally:
        running = False
        lock.release()
    return True


def loop_worker():
    while loop_flag.is_set():
        do_run()
        interval = max(1, int(load_config().get("preferences", {}).get("run_interval_minutes", 120)))
        deadline = time.time() + interval * 60
        while loop_flag.is_set() and time.time() < deadline:
            time.sleep(2)


def sent_count():
    try:
        con = sqlite3.connect(ROOT / "sent.db")
        n = con.execute("SELECT COUNT(*) FROM sent").fetchone()[0]
        con.close()
        return n
    except Exception:
        return 0


def dir_stats():
    dl = ROOT / (load_config().get("preferences", {}).get("download_dir", "downloads"))
    se = ROOT / "sent"
    pending = sum(1 for p in dl.iterdir() if p.suffix.lower() in uploader.MEDIA_EXTS) if dl.exists() else 0
    sent_bytes = sum(p.stat().st_size for p in se.iterdir() if p.is_file()) if se.exists() else 0
    return pending, sent_bytes


def mask_secret(value):
    value = value or ""
    if len(value) <= 8:
        return "***"
    return value[:4] + "***" + value[-4:]


@app.get("/")
def index():
    return PAGE


@app.get("/api/status")
def status():
    cfg = load_config()
    tg = cfg.get("telegram") or {}
    prefs = cfg.get("preferences", {})
    pending, sent_bytes = dir_stats()
    return jsonify({
        "loop_running": loop_flag.is_set(),
        "running": running,
        "last_run": last_run,
        "interval_minutes": prefs.get("run_interval_minutes", 120),
        "sent_count": sent_count(),
        "pending": pending,
        "sent_size_mb": round(sent_bytes / 1024 / 1024, 1),
        "keywords_count": len(cfg.get("sources", {}).get("keywords", [])),
        "bot": mask_secret(tg.get("bot_token")),
        "chat_id": tg.get("chat_id") or "未设置",
        "proxy": mask_secret(prefs.get("proxy")),
        "max_per_keyword": cfg.get("sources", {}).get("max_per_keyword"),
        "keep_uploaded": cfg.get("keep_uploaded", False),
    })


@app.post("/api/run")
def api_run():
    ok = do_run()
    return jsonify({"started": ok})


@app.post("/api/loop/start")
def loop_start():
    loop_flag.set()
    threading.Thread(target=loop_worker, daemon=True).start()
    return jsonify({"started": True})


@app.post("/api/loop/stop")
def loop_stop():
    loop_flag.clear()
    return jsonify({"stopped": True})


@app.post("/api/config")
def api_config():
    cfg = load_config()
    data = request.get_json(force=True, silent=True) or {}
    tg = cfg.setdefault("telegram", {})
    new_tg = data.get("telegram") or {}
    if new_tg.get("bot_token"):
        tg["bot_token"] = new_tg["bot_token"]
    if "chat_id" in new_tg:
        tg["chat_id"] = str(new_tg["chat_id"])
    if "keywords" in data:
        cfg.setdefault("sources", {})["keywords"] = data["keywords"]
    prefs = cfg.setdefault("preferences", {})
    for key in ("run_interval_minutes", "max_per_keyword", "keywords_per_round", "max_image_mb", "max_images_send", "proxy", "search_market", "album_mode"):
        if key in data:
            if key in ("run_interval_minutes", "keywords_per_round", "max_image_mb", "max_images_send"):
                prefs[key] = int(data[key])
            elif key == "max_per_keyword":
                cfg.setdefault("sources", {})["max_per_keyword"] = int(data[key])
            elif key == "album_mode":
                prefs[key] = bool(data[key])
            else:
                prefs[key] = data[key]
    if "keep_uploaded" in data:
        cfg["keep_uploaded"] = bool(data["keep_uploaded"])
    save_config(cfg)
    print("[配置] 已保存")
    return jsonify({"ok": True})


@app.get("/api/config/full")
def api_config_full():
    cfg = load_config()
    return jsonify({
        "keywords": cfg.get("sources", {}).get("keywords", []),
        "max_per_keyword": cfg.get("sources", {}).get("max_per_keyword"),
        "bot_token": cfg.get("telegram", {}).get("bot_token", ""),
        "chat_id": cfg.get("telegram", {}).get("chat_id", ""),
        "proxy": cfg.get("preferences", {}).get("proxy", ""),
        "run_interval_minutes": cfg.get("preferences", {}).get("run_interval_minutes", 120),
        "keywords_per_round": cfg.get("preferences", {}).get("keywords_per_round", 3),
        "max_image_mb": cfg.get("preferences", {}).get("max_image_mb", 8),
        "max_images_send": cfg.get("preferences", {}).get("max_images_send", 20),
        "album_mode": cfg.get("preferences", {}).get("album_mode", True),
        "search_market": cfg.get("preferences", {}).get("search_market", "ja-JP"),
        "keep_uploaded": cfg.get("keep_uploaded", False),
    })


@app.get("/api/logs")
def api_logs():
    return jsonify({"logs": list(logs)})


@app.get("/api/images")
def api_images():
    dl = ROOT / (load_config().get("preferences", {}).get("download_dir", "downloads"))
    out = []
    if dl.exists():
        for p in sorted(dl.iterdir(), key=lambda x: x.stat().st_mtime, reverse=True)[:50]:
            if p.suffix.lower() in uploader.MEDIA_EXTS:
                out.append({
                    "name": p.name,
                    "size_mb": round(p.stat().st_size / 1024 / 1024, 2),
                    "url": f"/media/{p.name}",
                })
    return jsonify({"images": out})


@app.get("/media/<path:name>")
def media(name):
    dl = ROOT / (load_config().get("preferences", {}).get("download_dir", "downloads"))
    p = dl / name
    if not p.exists() or p.suffix.lower() not in uploader.MEDIA_EXTS:
        return "not found", 404
    return p.read_bytes(), 200, {"Content-Type": "image/jpeg", "Cache-Control": "no-store"}


PAGE = """<!doctype html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>日本素材采集</title>
<link rel="manifest" href="/static/manifest.json">
<link rel="icon" href="/static/icon-192.png">
<meta name="theme-color" content="#1a1d24">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="mobile-web-app-capable" content="yes">
<style>
 body { font-family: system-ui, sans-serif; margin: 0; background: #0f1115; color: #e6e6e6; }
 .wrap { max-width: 800px; margin: 0 auto; padding: 16px; }
 h1 { font-size: 20px; }
 .card { background: #1a1d24; border-radius: 10px; padding: 14px; margin: 10px 0; }
 .grid { display: grid; grid-template-columns: repeat(auto-fit,minmax(120px,1fr)); gap: 8px; }
 .k { color: #8b93a7; font-size: 12px; }
 .v { font-size: 18px; font-weight: 600; margin-top: 2px; }
 button { background: #3355ff; color: #fff; border: 0; border-radius: 8px; padding: 10px 16px; font-size: 15px; margin: 4px; cursor: pointer; }
 button.sec { background: #d64545; }
 button.gray { background: #333a46; }
 textarea, input { width: 96%; background: #0f1115; color: #e6e6e6; border: 1px solid #333a46; border-radius: 6px; padding: 8px; font-size: 13px; margin-top: 6px; font-family: ui-monospace, monospace; }
 label { display: inline-block; margin-top: 10px; font-size: 13px; color: #9aa3b5; }
 #log { background: #0b0d11; border: 1px solid #333a46; border-radius: 8px; padding: 10px; height: 280px; overflow-y: auto; font-size: 12px; white-space: pre-wrap; font-family: ui-monospace, monospace; }
 .thumbs { display: grid; grid-template-columns: repeat(auto-fill,minmax(120px,1fr)); gap: 6px; }
 .thumbs img { width: 100%; height: 90px; object-fit: cover; border-radius: 6px; }
 .row { display: flex; flex-wrap: wrap; align-items: center; }
</style>
</head>
<body>
<div class="wrap">
 <h1>🗾 日本素材采集控制台</h1>
 <div class="card">
   <div class="grid">
     <div><div class="k">循环状态</div><div class="v" id="loop">-</div></div>
     <div><div class="k">运行中</div><div class="v" id="running">-</div></div>
     <div><div class="k">上次运行</div><div class="v" id="lastrun">-</div></div>
     <div><div class="k">已发送</div><div class="v" id="sent">-</div></div>
     <div><div class="k">待发图片</div><div class="v" id="pending">-</div></div>
     <div><div class="k">关键词</div><div class="v" id="kwcount">-</div></div>
   </div>
 </div>
 <div class="card row">
   <button onclick="runOnce()">⏱ 立即跑一轮</button>
   <button onclick="loopStart()">▶ 启动循环</button>
   <button class="sec" onclick="loopStop()">⏹ 停止</button>
 </div>
 <div class="card">
   <label>搜索关键词（每行一个，日本素材主题）</label>
   <textarea id="keywords" rows="8"></textarea>
   <label>Telegram bot_token <input type="text" id="bottoken" placeholder="123456:ABC-xxx"></label>
   <label>Telegram chat_id <input type="text" id="chatid" placeholder="-100xxx 或留空自动获取"></label>
   <div class="row">
     <label style="margin-right:10px">间隔(分钟) <input type="number" id="interval" min="1" style="width:90px"></label>
     <label style="margin-right:10px">每词最多 <input type="number" id="maxper" min="1" style="width:70px"></label>
     <label style="margin-right:10px">每轮词数 <input type="number" id="kwround" min="0" style="width:70px"></label>
     <label>图片上限(MB) <input type="number" id="maxmb" min="1" style="width:70px"></label>
   </div>
   <label>发送后保留文件 <input type="checkbox" id="keep"></label>
   <label>组图发送（同关键词合并相册） <input type="checkbox" id="album"></label>
   <div class="row"><button onclick="saveCfg()">💾 保存配置</button></div>
 </div>
 <div class="card"><div class="k">已采集图片</div><div class="thumbs" id="thumbs"></div></div>
 <div class="card"><div class="k">运行日志</div><div id="log"></div></div>
</div>
<script>
 function el(id){ return document.getElementById(id); }
async function j(url, body){ return fetch(url, {method: body?'POST':'GET', headers:{'Content-Type':'application/json'}, body: body?JSON.stringify(body):null}).then(r=>r.json()); }
  async function jpost(url){ return fetch(url, {method:'POST', headers:{'Content-Type':'application/json'}}).then(r=>r.json()); }
  async function refresh(){
   const s = await j('/api/status');
   el('loop').textContent = s.loop_running ? '🟢 运行中' : '⚪ 停止';
   el('running').textContent = s.running ? '工作中' : '空闲';
   el('lastrun').textContent = s.last_run || '-';
   el('sent').textContent = s.sent_count;
   el('pending').textContent = s.pending;
   el('kwcount').textContent = s.keywords_count;
   const lg = await j('/api/logs');
   const box = el('log');
   const atBottom = box.scrollHeight - box.scrollTop < 60;
   box.textContent = lg.logs.join('\\n');
   if (atBottom) box.scrollTop = box.scrollHeight;
   const img = await j('/api/images');
   el('thumbs').innerHTML = img.images.map(i=>'<img loading="lazy" src="'+i.url+'" title="'+i.name+'">').join('');
 }
function runOnce(){ jpost('/api/run').then(r=>{ if(r.started) alert('已启动一轮采集'); }); }
  async function loopStart(){ await jpost('/api/loop/start'); }
  async function loopStop(){ await jpost('/api/loop/stop'); }
 async function loadCfgFull(){
   const r = await fetch('/api/config/full'); const cfg = await r.json();
   el('keywords').value = (cfg.keywords||[]).join('\\n');
   el('bottoken').value = cfg.bot_token||'';
   el('chatid').value = cfg.chat_id||'';
   el('interval').value = cfg.run_interval_minutes;
   el('maxper').value = cfg.max_per_keyword;
   el('kwround').value = cfg.keywords_per_round;
   el('maxmb').value = cfg.max_image_mb;
   el('keep').checked = !!cfg.keep_uploaded;
   el('album').checked = !!cfg.album_mode;
 }
 async function saveCfg(){
   await j('/api/config', {
     keywords: el('keywords').value.split('\\n').map(s=>s.trim()).filter(Boolean),
     telegram: { bot_token: el('bottoken').value, chat_id: el('chatid').value },
     run_interval_minutes: parseInt(el('interval').value||120,10),
     max_per_keyword: parseInt(el('maxper').value||10,10),
     keywords_per_round: parseInt(el('kwround').value||3,10),
     max_image_mb: parseInt(el('maxmb').value||8,10),
     album_mode: el('album').checked,
     keep_uploaded: el('keep').checked
   });
 }
 setInterval(refresh, 3000);
 loadCfgFull();
 if ('serviceWorker' in navigator) {
   window.addEventListener('load', () => {
     navigator.serviceWorker.register('/static/sw.js').catch(()=>{});
   });
 }
</script>
</body>
</html>
"""


if __name__ == "__main__":
    original_out = sys.stdout
    original_err = sys.stderr
    fileobj = open(LOG_PATH, "a", encoding="utf-8")
    sys.stdout = Tee(original_out, fileobj)
    sys.stderr = Tee(original_err, fileobj)
    print("[控制台] 日本素材采集控制台启动")
    host = os.environ.get("PANEL_HOST", "0.0.0.0")
    port = int(os.environ.get("PANEL_PORT", "8643"))
    app.run(host=host, port=port, threaded=True)