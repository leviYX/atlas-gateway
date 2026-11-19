#!/usr/bin/env bash
# 使用当前 JDK25 和 Maven wrapper 执行测试并生成可运行 jar。
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
exec bash "$ATLAS_ROOT/mvnw" clean verify "$@"
