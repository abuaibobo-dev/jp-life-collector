#!/bin/sh
cd "$(dirname "$0")"
PORT="${PANEL_PORT:-8643}"
if [ -f panel.pid ] && kill -0 "$(cat panel.pid)" 2>/dev/null; then
  echo "控制台已在运行: http://127.0.0.1:${PORT} (pid $(cat panel.pid))"
  exit 0
fi
if [ ! -x venv/bin/python ]; then
  echo "首次使用，正在安装依赖..."
  python3 -m venv venv
  ./venv/bin/pip install -q -r requirements.txt
fi
nohup ./venv/bin/python webapp.py >/dev/null 2>&1 &
echo $! > panel.pid
sleep 1
echo "控制台已启动:"
echo "  手机本机访问: http://127.0.0.1:${PORT}"
echo "  局域网访问:   http://<本机IP>:${PORT}"
echo "  停止:        ./stop.sh"