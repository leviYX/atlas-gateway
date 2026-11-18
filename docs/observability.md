# 压测可观测性与操作手册

## 目标与实现范围

以同一个 run-id 关联客户端负载、网关/JVM 指标、JFR、GC 和 Linux 进程采样。
已实现 Micrometer Prometheus 指标、JFR/GC 启动器、Linux 采集器和预配置 Grafana 总览。
客户端端到端延迟仍由负载发生器输出，本项目没有把客户端延迟伪装成服务端耗时。

Micrometer 1.15.10 与 JDK 25 使用本项目的真实 HTTP 测试验证。
项目保留当前的 Netty 4.2.21.Final，默认 Channel 分配器通过其实际 MetricProvider 观测。
Docker 面板需要在 Docker daemon 可用时启动；Linux proc 与原生 transport 的真实运行要在 Linux 验证。

## 1. 构建与启动一轮实验

```bash
# Mac；Linux 将 JAVA_HOME 改为实际 JDK 25 路径
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw clean verify

# 前台运行，日志写入结果目录；下一步使用另一终端
TRANSPORT=nio MODE=validate WORKERS=4 HEAP=1g \
  scripts/run-observed.sh nio-validate-001
```

Linux 上仅改变 TRANSPORT，启动 epoll / io_uring 实验。重复实验使用不同 run-id。
run-id 只能包含字母、数字、点、下划线、短横线，已有目录会拒绝覆盖。
启动脚本默认要求 JDK 25，JAR 不存在会直接报错。

```bash
# Linux 示例，允许 Docker Prometheus 从宿主机网卡访问
TRANSPORT=io_uring MODE=validate BIND_HOST=0.0.0.0 \
  scripts/run-observed.sh uring-validate-001 -Dgateway.maxConnections=100000
```

额外 JVM 参数以独立参数传给脚本，不使用含糊的 JVM_OPTIONS 字符串。
如重复指定同名 -D 属性，JVM 最后一个值生效，以 command.txt 和启动日志中的实际配置为准。
网关 /metrics 与业务共用端口，目前没有鉴权；BIND_HOST=0.0.0.0 时使用受控内网和访问规则。

脚本支持的环境变量：

| 变量 | 默认 | 说明 |
|---|---|---|
| TRANSPORT | nio | nio / epoll / io_uring |
| MODE | validate | validate / discard |
| WORKERS | 4 | 各 transport 对比应相同 |
| HEAP | 1g | 同时设置 Xms、Xmx |
| BIND_HOST / PORT | 127.0.0.1 / 8080 | HTTP 监听地址 |
| JFR_ENABLED | true | 可关闭 JFR 做开销对照 |
| JFR_SETTINGS | default | default 或 profile；记录在元数据中 |
| NMT_ENABLED | false | 开启 NativeMemoryTracking=summary，需启动时指定 |
| JAR | target/atlas-gateway.jar | 以项目根目录解析相对路径 |

默认 JFR 使用滚动记录，最多保留约 30 分钟、256 MiB 的录制数据，并在正常退出时写入 atlas.jfr。
GC 日志按 20 MiB、5 个文件轮转。磁盘应预留足够空间；SIGKILL 不保证导出 JFR。
run-id 不会自动成为指标标签，避免每次实验永久增加时序；用运行时间窗口与结果目录关联。

## 2. 启动 Prometheus 与 Grafana

在 Docker daemon 已启动的机器上执行：

```bash
export GRAFANA_ADMIN_PASSWORD='请替换为本地密码'
docker compose -f observability/compose.yaml up -d
```

- Prometheus： http://localhost:9090 ，先到 Targets 确认 atlas-gateway 为 UP。
- Grafana： http://localhost:3000 ，用户 admin，密码是上面的环境变量。
- 预置数据源 Atlas Prometheus，Atlas 文件夹下有“Atlas Gateway 压测总览”。
- 面板默认最近 15 分钟，5 秒刷新，按 instance 选择目标。
- Prometheus 5 秒采样，保留 7 天；数据放在 Docker volumes。

默认抓取 host.docker.internal:8080（容器看到的宿主机）。因此网关只绑定 127.0.0.1 时通常无法被容器访问，
需要上述 BIND_HOST=0.0.0.0，或者编辑 observability/prometheus/prometheus.yml 为可达的内网地址。
Linux 上额外 host-gateway 映射已配置；远程虚拟机/云服务器直接填写其内网 IP:端口。

只在本机运行时无需 Docker，直接 curl /metrics 并保存到结果目录也能记录数据。
监控容器与服务同机运行会占用 CPU/内存，严谨对比建议独立监控机器。
Grafana/Prometheus 的端口只发布到本机回环地址，远程查看可走 SSH 隧道。

```bash
# 结束监控，保留数据卷；需要同一个 GRAFANA_ADMIN_PASSWORD 环境变量
 docker compose -f observability/compose.yaml down
```

固定版本镜像便于复现；没有自动拉取 latest。首次启动需要网络下载镜像。

## 3. Linux 采集器

```bash
sudo apt update
sudo apt install -y sysstat iproute2

# 在网关所在的 Linux 主机/虚拟机内执行，同一用户，JDK25 的 JAVA_HOME 已配置
python3 scripts/collect-linux.py results/uring-validate-001 --duration 300

# 端口不同或需要 JFR 快照与 NMT 差量诊断
python3 scripts/collect-linux.py results/uring-validate-001 \
  --duration 300 --metrics-url http://127.0.0.1:8080/metrics --jcmd
```

CSV 每秒记录进程 CPU、RSS、虚拟内存、RSS 分项、线程数与 FD 数；每 5 秒保存一份 metrics 文本。
CPU 以单核 100% 为基准，可超过 100%；与 Micrometer 的整机归一化 process_cpu_usage 不同。
第一行 CPU 为空，因为没有完整采样窗口。
CSV 的上下文切换来自主线程 status，列名明确标注 main_thread；全线程分析使用 pidstat -t -w 输出。
pidstat 会额外采集各线程 CPU、缺页、I/O 与上下文切换；未安装时仍有 proc CSV，并在 collector.log 说明。

采集开始/结束保存 smaps_rollup、status、limits、io，以及主机 CPU/内存/网络累计计数和 ss -s。
网络快照属于整个主机/网络命名空间，不能当作 Java 进程专属网络流量。
脚本核对进程命令和 starttime，发现退出或 PID 被复用后停止针对该 PID 诊断。

--jcmd 默认关闭：会扰动测试，在开始/结束执行 NMT baseline/diff，并在结束时导出 snapshot.jfr。
NMT 需要 NMT_ENABLED=true 启动；JFR 需要 JFR_ENABLED=true，否则相关文件记录未启用原因。
目标在采集结束前退出时不会强行执行末尾命令，正常退出的 JFR 仍由启动器导出。
采集器 Ctrl+C 只停止采集，绝不停止网关；重复采集写入不同 linux-* 子目录。

## 4. 运行压测并关联时间窗口

```bash
# 另一终端，从项目根目录运行；客户端与服务在不同机器更适合容量测试
wrk -t4 -c128 -d20s -s benchmarks/report.lua http://127.0.0.1:8080/v1/events/batch
wrk -t4 -c128 -d60s --latency -s benchmarks/report.lua \
  http://127.0.0.1:8080/v1/events/batch > results/nio-validate-001/client.log
```

在同一 run 目录创建 summary.md，记录预热开始、测量开始/结束的 UTC 时间、连接数、发送模型、
每批事件数、payload 大小和客户端参数。分布式机器保持时钟同步，再在 Grafana 选相同时间窗口。
wrk 是闭环模型，不应把其延迟直接当作固定到达率的 SLO 证据；固定速率实验另用 wrk2 等工具。

## 5. 面板与指标含义

| 指标 | 含义 / 限制 |
|---|---|
| atlas_batches_accepted_total | 接收后丢弃的成功批次；不是持久化确认 |
| atlas_events_validated_total | validate 校验通过事件数，discard 始终不增加 |
| atlas_http_requests_total / responses_total / errors_total | 全部 HTTP 请求/出站最终响应/4xx+，包含探活与指标抓取 |
| atlas_connections_active / connections_total / connections_rejected_total | 活动连接、累计连接与因上限被关闭的连接 |
| atlas_eventloop_pending_tasks | 按 boss/worker 和 index 标记的待执行任务数；不是 SQ/CQ 长度 |
| atlas_handlers_active | 正在执行完整请求回调；不包含上传、聚合或未来输出排队 |
| atlas_handler_duration_seconds_* | 回调计时及直方图，包含 health/metrics 回调；不代表网络端到端延迟 |
| atlas_http_body_bytes_total | HTTP 解码后收到的 body 字节，不含 TLS、头部和 TCP 开销 |
| atlas_runtime_info | 值 1，带 transport/mode 标签，用于核对实际运行配置 |
| jvm_memory_* / jvm_gc_* / jvm_threads_* | 堆/非堆、GC 和线程指标；采样精度与具体 JVM 有关 |
| jvm_buffer_* | JDK BufferPool，不能保证覆盖 Netty 全部堆外内存 |
| atlas_netty_allocator_*_memory_bytes | 默认 allocator 报告的 heap/direct 内存；可能包含池保留空间 |
| process_cpu_usage / system_cpu_usage | 进程和主机 CPU，按可用 CPU 归一化，0..1 |
| atlas_process_resident_memory_bytes | Linux /proc/self/status 的 VmRSS，每秒缓存；其他平台不注册 |
| atlas_process_virtual_memory_bytes | Linux VmSize，地址空间预留不是实际物理内存使用 |
| process_files_* | JVM 可报告时的 FD 指标 |

业务计数名称保持兼容旧版本，但 Prometheus 数值会变成 1.0；时间序列中可能带 JVM 自带的固定标签。
直方图分位数示例：

```promql
histogram_quantile(0.99,
  sum by (le) (rate(atlas_handler_duration_seconds_bucket[1m])))
```

这是桶估算值，并非 JFR 中每个请求的精确耗时。短实验、低流量或抓取不足时，rate/分位数可能没有值。
尚未给每类路由分别打点，所以指标抓取耗时也可能影响该分位数。客户端延迟仍是外部结果。
默认 allocator 之外自建的 ByteBufAllocator 要单独绑定；指标不代表所有 JNI 分配。

## 6. JFR、GC 和原生内存诊断

正常停止：启动终端 Ctrl+C，或 kill -TERM $(cat results/<run-id>/gateway.pid)。
不要 kill -9，否则录制文件可能没有导出。

```bash
# 运行中手动快照，建议先用绝对目录，避免 jcmd 与目标进程工作目录不同
jcmd <PID> JFR.dump name=atlas filename=/absolute/path/snapshot.jfr
# 停止后验证录制文件
jfr summary results/nio-validate-001/atlas.jfr

# NMT 启动时已开启的情况下
jcmd <PID> VM.native_memory baseline
jcmd <PID> VM.native_memory summary.diff scale=MB
```

把 JFR 拷到 Mac 用 JDK Mission Control 查看 CPU 执行采样、对象分配、GC、锁/线程等事件。
default 侧重低开销，不保证包含所有详细分配/锁事件；需要细节时单独运行 JFR_SETTINGS=profile。
GC 日志关注停顿时间、频率和回收前后堆变化；JFR 有利于定位热点，不能单靠它解释全部 RSS。

RSS 的组成包括驻留的堆、线程栈、代码、JVM 元数据、堆外缓冲与映射页。
heap committed、allocator direct memory、NMT committed 都不等于 RSS，不要直接相加。
NMT 主要覆盖 JVM 管理的原生内存，不能保证追踪 Netty/JNI 的所有第三方分配。
RSS 在降载后不立即下降可能是池保留；多轮持续增长、堆外指标和缓冲引用情况要一起分析。

## 7. 每轮产物与对照

```text
results/<run-id>/
  metadata.txt           UTC、JDK、系统、Git 状态、jar SHA256、观测开关
  source-pom.xml         本次源码目录的依赖声明（jar SHA256 才唯一标识实际产物）
  command.txt            实际 Java 启动命令
  gateway.pid
  artifact-path.txt     实际 -jar 路径，用于采集器核对进程
  server.log
  gc.log[.*]
  atlas.jfr              正常退出导出；有界滚动记录
  client.log             手动保存的负载发生器输出
  summary.md             手动记录实验窗口、负载参数和结论
  linux-<UTC>/
    collector.json      PID starttime、采样间隔、内核等
    process.csv
    pidstat.log
    metrics/*.prom
    start-* / end-*      进程和主机快照
    snapshot.jfr        --jcmd 时的运行中快照
    nmt-*               --jcmd 时的 NMT 输出
    collector.log       缺工具、无权限、进程退出等说明
```

results 已加入 gitignore，避免提交大体积诊断文件。JFR 和客户端数据也应按项目的数据管理要求保存。

第一轮固定连接数和消息体，预热后测量；每个 transport 至少重复三轮。
对比时相同 JFR 配置、相同抓取间隔、相同 collector；profile 与 default 的数据分组比较。
额外跑一轮 JFR_ENABLED=false、不运行 collector/Prometheus 的对照，估算外部观测开销。
当前业务计数/直方图仍在该对照轮启用，这不是完全零指标开销的模式。
--jcmd 和 NMT 属于额外诊断，默认不混入吞吐基线。

## 验证命令

```bash
./mvnw clean verify
bash -n scripts/run-observed.sh
python3 -m unittest discover -s scripts/tests -v
GRAFANA_ADMIN_PASSWORD=validation-only docker compose -f observability/compose.yaml config --quiet
```

Linux 上再验证 RSS 与进程采集；Docker 启动后确认 Targets UP、Grafana 各面板有数据，
不要将“配置校验通过”当作“容器和 Linux 原生链路已验证”。
