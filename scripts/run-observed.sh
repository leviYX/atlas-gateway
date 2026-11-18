#!/usr/bin/env bash
# 在前台运行一轮可复现的网关实验，默认开启有界 JFR 与轮转 GC 日志。
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ "${1:-}" == "--help" ]]; then
  cat <<'HELP'
用法：scripts/run-observed.sh [run-id] [额外 JVM 参数...]
环境变量：TRANSPORT=nio MODE=validate WORKERS=4 PORT=8080 BIND_HOST=127.0.0.1
          HEAP=1g JFR_ENABLED=true JFR_SETTINGS=default NMT_ENABLED=false
          JAR=target/atlas-gateway.jar
示例：TRANSPORT=io_uring scripts/run-observed.sh uring-001 -Dgateway.maxConnections=100000
前台执行；Ctrl+C / SIGTERM 后 JFR 写入 results/<run-id>/atlas.jfr。
HELP
  exit 0
fi
transport="${TRANSPORT:-nio}"
mode="${MODE:-validate}"
run_id="${1:-$(date -u +%Y%m%dT%H%M%SZ)-$transport}"
if [[ $# -gt 0 ]]; then shift; fi
case "$run_id" in ''|*[!a-zA-Z0-9._-]*|.|..) echo 'run-id 仅允许字母、数字、点、下划线和短横线' >&2; exit 2;; esac
case "$transport" in nio|epoll|io_uring) ;; *) echo '无效 TRANSPORT' >&2; exit 2;; esac
case "${JFR_SETTINGS:-default}" in default|profile) ;; *) echo 'JFR_SETTINGS 必须为 default 或 profile' >&2; exit 2;; esac
for flag in "${JFR_ENABLED:-true}" "${NMT_ENABLED:-false}"; do
  case "$flag" in true|false) ;; *) echo '开关必须为 true 或 false' >&2; exit 2;; esac
done
if [[ -n "${JAVA_HOME:-}" ]]; then java_cmd="$JAVA_HOME/bin/java"; else java_cmd="$(command -v java)"; fi
jar_path="${JAR:-$ROOT_DIR/target/atlas-gateway.jar}"
[[ "$jar_path" = /* ]] || jar_path="$ROOT_DIR/$jar_path"
[[ -f "$jar_path" ]] || { echo "缺少 $jar_path，请先 ./mvnw clean verify" >&2; exit 2; }
version="$("$java_cmd" -version 2>&1)"
[[ "$version" == *'version "25'* ]] || { echo "需要 JDK 25，实际：$version" >&2; exit 2; }
run_dir="$ROOT_DIR/results/$run_id"
# JFR/GC 的选项解析器有自己的分隔符规则，拒绝无法直接表示的路径。
case "$run_dir" in *","*|*"'"*|*":"*) echo '项目路径不能包含逗号、单引号或冒号' >&2; exit 2;; esac
mkdir -p "$ROOT_DIR/results"
mkdir "$run_dir"  # 已有实验目录直接失败，绝不覆盖旧结果。
args=(--enable-native-access=ALL-UNNAMED "-Xms${HEAP:-1g}" "-Xmx${HEAP:-1g}"
      "-Xlog:gc*,safepoint:file=$run_dir/gc.log:time,uptime,level,tags:filecount=5,filesize=20M"
      "-Dgateway.transport=$transport" "-Dgateway.mode=$mode"
      "-Dgateway.host=${BIND_HOST:-127.0.0.1}" "-Dgateway.port=${PORT:-8080}"
      "-Dgateway.workers=${WORKERS:-4}")
if [[ "${JFR_ENABLED:-true}" == true ]]; then
  args+=("-XX:StartFlightRecording=name=atlas,settings=${JFR_SETTINGS:-default},disk=true,maxage=30m,maxsize=256m,dumponexit=true,filename='$run_dir/atlas.jfr'")
fi
if [[ "${NMT_ENABLED:-false}" == true ]]; then args+=(-XX:NativeMemoryTracking=summary); fi
args+=("$@" -jar "$jar_path")
{
  echo "started_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "run_id=$run_id"
  echo "pid=$$"
  echo "transport_requested=$transport mode_requested=$mode"
  echo "jfr_enabled=${JFR_ENABLED:-true} jfr_settings=${JFR_SETTINGS:-default} nmt_enabled=${NMT_ENABLED:-false}"
  echo "$version"
  uname -a
  git -C "$ROOT_DIR" rev-parse HEAD 2>/dev/null || true
  git -C "$ROOT_DIR" status --short 2>/dev/null || true
  if command -v sha256sum >/dev/null; then sha256sum "$jar_path"; else shasum -a 256 "$jar_path"; fi
} > "$run_dir/metadata.txt"
printf '%q ' "$java_cmd" "${args[@]}" > "$run_dir/command.txt"
printf '\n' >> "$run_dir/command.txt"
cp "$ROOT_DIR/pom.xml" "$run_dir/source-pom.xml"
printf '%s\n' "$jar_path" > "$run_dir/artifact-path.txt"
printf '%s\n' "$$" > "$run_dir/gateway.pid"
printf '结果目录：%s\nPID：%s\n日志：tail -f "%s/server.log"\n' "$run_dir" "$$" "$run_dir"
# exec 保持 gateway.pid 对应 Java 进程；客户端/采集器在另一终端运行。
exec "$java_cmd" "${args[@]}" >> "$run_dir/server.log" 2>&1
