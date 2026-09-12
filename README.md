# 日本素材采集

全网搜索日本·日常生活图片，自动推送到 Telegram（群/频道），带 Web 控制台。

- 图片源：Bing 图片搜索（免费、无需注册、无需 API key，强制 ja-JP 市场）
- 推送：Telegram Bot，组图/单张可选，按关键词分组
- 控制台：Flask 单文件 Web 面板，可 PWA 安装到桌面

## 快速开始

```bash
python3 -m venv venv
./venv/bin/pip install -r requirements.txt
cp config.example.json config.json   # 填入 bot_token / chat_id
./start.sh                            # 启动控制台 http://127.0.0.1:8643
./venv/bin/python main.py --once      # 手动跑一轮采集+推送
```

## 配置

见 `config.json`，核心项：

| 字段 | 说明 |
|---|---|
| telegram.bot_token | Telegram Bot token |
| telegram.chat_id | 目标群/频道 id（留空自动获取） |
| sources.keywords | 搜索关键词（每行一个） |
| sources.max_per_keyword | 每词最多采集张数 |
| preferences.keywords_per_round | 每轮处理关键词数量 |
| preferences.album_mode | true=同词组图相册，false=逐张 |
| preferences.run_interval_minutes | 循环间隔（分钟） |

## 控制台

- 状态、日志、已采集图片预览
- 在线编辑关键词与参数
- 一键立即运行 / 启动循环采集
- 支持作为 PWA 添加到手机桌面