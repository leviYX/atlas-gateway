#!/usr/bin/env bash
# 由各入口 source，统一目录、配置与环境，不依赖用户当前工作目录。
set -euo pipefail
ATLAS_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
set -a
source "$ATLAS_ROOT/scripts/gateway.conf"
if [[ -f "$ATLAS_ROOT/scripts/gateway.local.conf" ]]; then
  source "$ATLAS_ROOT/scripts/gateway.local.conf"
fi
set +a
cd "$ATLAS_ROOT"
