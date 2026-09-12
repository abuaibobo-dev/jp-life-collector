#!/bin/sh
cd "$(dirname "$0")"
if [ ! -f panel.pid ]; then
  echo "未运行"
  exit 0
fi
PID=$(cat panel.pid)
if kill -0 "$PID" 2>/dev/null; then
  kill "$PID"
  echo "已停止 (pid $PID)"
else
  echo "进程已不存在 (pid $PID)"
fi
rm -f panel.pid