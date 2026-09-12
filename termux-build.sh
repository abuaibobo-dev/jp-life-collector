#!/data/data/com.termux/files/usr/bin/env bash
# jp-life-collector Termux 一键构建
# 用法（在 Termux 里）:
#   git clone https://github.com/abuaibobo-dev/jp-life-collector.git
#   cd jp-life-collector
#   bash termux-build.sh
# 产物: jp-life-collector-<version>.apk（带签名，可直接安装）
set -euo pipefail

CPU=$(uname -m)
echo "=== [1/8] 检查架构 (应为 aarch64) ==="
echo "arch: $CPU"
if [ "$CPU" != "aarch64" ]; then
  echo "本脚本只支持 aarch64 (arm64-v8a) 手机" >&2
  exit 1
fi

echo "=== [2/8] 安装 Termux 依赖 (openjdk-17 / git / wget / unzip) ==="
pkg update -y
pkg install -y openjdk-17 git wget unzip

echo "=== [3/8] 安装 Gradle ==="
GRADLE_VERSION=8.7
if ! command -v gradle >/dev/null 2>&1; then
  cd /data/data/com.termux/files/home
  wget -q "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -O gradle.zip
  unzip -q -o gradle.zip
  rm -f gradle.zip
  ln -sf "/data/data/com.termux/files/home/gradle-${GRADLE_VERSION}/bin/gradle" \
         /data/data/com.termux/files/usr/bin/gradle
fi
gradle -v | head -6

echo "=== [4/8] 安装 Android SDK (cmdline-tools + platform-35 + build-tools) ==="
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
TOOLS_DIR="$ANDROID_HOME/cmdline-tools/latest"
if [ ! -f "$TOOLS_DIR/bin/sdkmanager" ]; then
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  cd "$ANDROID_HOME/cmdline-tools"
  wget -q "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" -O clt.zip
  unzip -q -o clt.zip
  mv cmdline-tools latest
  rm -f clt.zip
  ls -la "$TOOLS_DIR/bin"
fi
(yes | "$TOOLS_DIR/bin/sdkmanager" --sdk_root="$ANDROID_HOME" --licenses) || true
"$TOOLS_DIR/bin/sdkmanager" --sdk_root="$ANDROID_HOME" "platform-tools" "platforms;android-35" "build-tools;35.0.0"

echo "=== [5/8] 安装 aarch64 aapt2 (社区静态构建) ==="
AAPT2_BIN="$HOME/android-arm-tools/aapt2"
if [ ! -x "$AAPT2_BIN" ]; then
  mkdir -p "$HOME/android-arm-tools"
  cd "$HOME/android-arm-tools"
  wget -q "https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip" -O tools.zip
  unzip -q -o tools.zip
  find . -name aapt2 -exec cp {} "$AAPT2_BIN" \;
  chmod +x "$AAPT2_BIN"
  rm -f tools.zip
fi
"$AAPT2_BIN" version || true

echo "=== [6/8] 配置项目 ==="
cd "$(dirname "$0")/android"
cat > local.properties <<EOF
sdk.dir=$ANDROID_HOME
EOF
grep -q "^android.aapt2FromMavenOverride" gradle.properties || \
  echo "android.aapt2FromMavenOverride=$AAPT2_BIN" >> gradle.properties

echo "=== [7/8] 生成签名密钥 (debug keystore) ==="
KS_DIR="$HOME/.android"
KS="$KS_DIR/debug.keystore"
if [ ! -f "$KS" ]; then
  mkdir -p "$KS_DIR"
  keytool -genkeypair -v -keystore "$KS" -storepass android -alias androiddebugkey \
    -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
fi

echo "=== [8/8] 构建 release APK ==="
export ANDROID_HOME ANDROID_SDK_ROOT
gradle --no-daemon assembleRelease

APK="app/build/outputs/apk/release/app-release-unsigned.apk"
if [ ! -f "$APK" ]; then
  DEVAPK="app/build/outputs/apk/debug/app-debug.apk"
  echo "unsigned release 未生成，改用 debug" >&2
  APK="$DEVAPK"
fi

OUT="$HOME/素材采集.apk"
"$ANDROID_HOME/build-tools/35.0.0/apksigner" sign --ks "$KS" --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android \
  --out "$OUT" "$APK"
echo ""
echo "=========================================================="
echo " 构建完成: $OUT"
echo " 在 Termux 里输入下面命令发送/安装:"
echo "   termux-open '$OUT'"
echo " 或复制到 Download:"
echo "   cp '$OUT' /sdcard/Download/"
echo "=========================================================="