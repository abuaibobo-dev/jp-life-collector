# -*- coding: utf-8 -*-
"""日本素材采集 - 命令行入口

用法:
  python main.py --once      # 只跑一轮采集+推送
  python main.py             # 循环模式，按配置间隔执行
"""
import argparse
import time

from scraper import load_config
import scraper
import uploader


def main():
    ap = argparse.ArgumentParser(description="日本素材采集：全网搜索图片 + Telegram 推送")
    ap.add_argument("--once", action="store_true", help="只执行一轮采集+推送后退出")
    ap.add_argument("--collect-only", action="store_true", help="只采集不推送")
    args = ap.parse_args()
    prefs = load_config().get("preferences", {})
    if args.once:
        cfg = load_config()
        scraper.run(cfg)
        if not args.collect_only:
            uploader.run(cfg)
        return
    minutes = max(1, int(prefs.get("run_interval_minutes", 120)))
    print(f"[循环模式] 每 {minutes} 分钟采集一次并自动推送（Ctrl+C 退出）")
    while True:
        cfg = load_config()
        scraper.run(cfg)
        if not args.collect_only:
            uploader.run(cfg)
        time.sleep(minutes * 60)


if __name__ == "__main__":
    main()