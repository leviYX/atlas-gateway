#!/usr/bin/env bash
# Ubuntu 首次安装辅助工具；JDK25 沿用已安装的版本，不改动 Java 配置。
set -euo pipefail
if [[ "$(uname -s)" != Linux ]]; then
  echo '本脚本用于 Ubuntu；Mac 压测端可使用 brew install wrk。' >&2
  exit 1
fi
sudo apt update
sudo apt install -y python3 curl wrk sysstat iproute2
java -version
javac -version
echo '辅助工具已安装，请确认上面 Java 与 javac 都为 25，然后执行 bash scripts/build.sh。'
