#!/usr/bin/env bash
# 便捷入口，实际流程集中在 workflow.py，配置位于 gateway.conf。
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
exec python3 "$ATLAS_ROOT/scripts/workflow.py" status "$@"
