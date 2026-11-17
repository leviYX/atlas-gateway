# Atlas Gateway

基于 **JDK 25 + Netty 4.2** 的地图行为数据接入网关，接收车端、App 和主站上报的搜索、点击、sug 行为。
第一阶段只做接收、可选校验、计量和丢弃，用同一套 HTTP 业务逻辑对比 **NIO / epoll / io_uring**。

> **当前所有成功上报的数据都会被丢弃。204 不代表持久化成功。** 尚未接入 Kafka、消费服务或 Doris。

## 当前能力

- HTTP/1.1、Keep-Alive、JSON 批量事件上报。
- `validate`：解析并校验 JSON 信封及事件公共字段，计数后丢弃。
- `discard`：接收并聚合完整请求体，跳过 JSON 解析和业务校验，计数后丢弃。
- 单请求大小、批次事件数、连接数上限和空闲连接超时。
- 健康检查、Prometheus 文本指标，不打印逐请求日志或事件内容。
- 显式选择 transport；epoll/io_uring 不可用时启动失败，不静默回退。
- SIGTERM / Ctrl+C 停止监听、关闭客户端连接和事件循环。

这是学习和压测骨架，尚无 TLS、鉴权、全局内存/在途字节预算、请求速率限制、持久化或高可用保障。
JSON 校验在 EventLoop 执行，后续可用压测判断是否需要隔离；不要在此线程加入阻塞操作。
关闭时不承诺完成所有在途请求，客户端必须能识别断连；生产排空流程留到持久化链路阶段。

## 环境与构建

- **JDK 25**，不是 JDK 17，也不需要 preview。
- Maven Wrapper 已包含，首次执行需要下载 Maven/依赖。
- macOS：NIO；Linux：NIO、epoll、io_uring。学习建议 Ubuntu 24.04、内核 6.8 或更新。
- Netty 固定为已发布的 `4.2.16.Final`，不依赖另一个 Netty 源码仓库或 SNAPSHOT 本地安装。

```bash
# macOS：确认终端和 IDE 都使用 JDK 25
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
export PATH="$JAVA_HOME/bin:$PATH"
java -version

./mvnw clean verify
java --enable-native-access=ALL-UNNAMED -jar target/atlas-gateway.jar
```

默认监听 `127.0.0.1:8080`，transport 为 `nio`，处理模式为 `validate`。
Windows 可使用 `mvnw.cmd`，本阶段仅提供 NIO 开发路径。
JDK 25 的 native-access 参数授权 JNI 原生访问；Maven 自身依赖仍可能打印 Unsafe 弃用提示。

### Linux 原生 transport

项目使用 `netty-all`，协议模块及平台原生库由其传递依赖提供。
Mac 和 Linux 使用同一条构建命令，不再需要手动指定 Linux profile：

```bash
./mvnw clean verify

java --enable-native-access=ALL-UNNAMED \
  -Dgateway.transport=epoll -Dgateway.workers=4 \
  -jar target/atlas-gateway.jar

# 先停止上一个服务，再启动
java --enable-native-access=ALL-UNNAMED \
  -Dgateway.transport=io_uring -Dgateway.workers=4 \
  -jar target/atlas-gateway.jar
```

在 Mac 构建的 jar 也会包含 netty-all 引入的 Linux 原生库，可复制到匹配架构的 Linux 运行。
这会增加依赖数量和产物体积，适合当前需要尝试多种 Netty 能力的学习项目。

`IoUring.isAvailable()` 失败会带出底层原因；检查内核、架构、原生库以及 io_uring 系统策略。
容器还受宿主内核和 seccomp 限制。不需要安装 liburing。
`verify` 会实际测试可用的原生 transport；当前主机不可用的原生测试会标记为跳过。

## 接口

### 批量上报

```bash
curl -i http://127.0.0.1:8080/v1/events/batch \
  -H 'Content-Type: application/json' \
  --data-binary @examples/batch.json
```

成功返回 `204 No Content`，没有响应体。批次原子校验：任一事件非法则整批拒绝，不计为成功批次。

```json
{
  "batchId": "batch-001",
  "source": "vehicle",
  "schemaVersion": 1,
  "events": [
    {
      "eventId": "event-001",
      "eventType": "map_search",
      "eventTime": 1794902400000,
      "sessionId": "session-001",
      "attributes": {"keyword": "停车场", "cityCode": "310000"}
    }
  ]
}
```

| 字段 | 校验规则 |
|---|---|
| batchId | 非空字符串 |
| source | `vehicle` / `app` / `web`，web 对应主站 |
| schemaVersion | 整数 `1` |
| events | 非空数组，默认最多 1000 条 |
| eventId | 非空字符串；当前不去重 |
| eventType | `map_search` / `map_click` / `sug_impression` / `sug_click` |
| eventTime | 正的、可表示为 long 的毫秒时间戳；当前不限制时间范围 |
| attributes | 可选 JSON 对象，当前不做按事件类型的业务字段校验 |
| sessionId | 可选扩展字段，当前不校验 |

默认最大 JSON 嵌套深度 64。暂不支持 gzip 请求体；客户端发送未压缩 JSON。

| 状态码 | 含义 |
|---|---|
| 204 | validate 校验通过 / discard 收到完整请求体，随后丢弃 |
| 400 | HTTP 解码失败或批次格式/字段/事件数量不合法 |
| 404 | 路径不存在 |
| 405 | 方法不支持，返回 Allow |
| 413 | 请求体超过上限 |
| 415 | validate 模式下 Content-Type 不是 application/json |

### 健康检查与指标

```bash
curl http://127.0.0.1:8080/health
curl http://127.0.0.1:8080/metrics
```

指标包括连接数、拒绝连接数、HTTP 请求/响应/错误数、收到的 HTTP body 字节数、成功批次数、校验通过事件数。
HTTP 计数包含 health/metrics；body 字节统计是接收到的解码后字节，不等同于网卡流量。
连接超限直接关闭连接，不返回 HTTP 状态码。
`atlas_handler_duration_seconds_sum/count` 只记录完整请求进入业务 Handler 后的同步处理时间，
不包括请求上传、聚合、排队和响应网络传输；不是端到端延迟，也不能推出 P99。
`discard` 模式不解析 JSON，因此不会增加 `atlas_events_validated_total`。
指标为并发近似快照，连接数是单进程上限，计数从每次进程启动开始。

## 配置

全部通过 `-Dgateway.<name>=<value>` 设置，放在 `-jar` 之前。

| 属性 | 默认 | 说明 |
|---|---|---|
| host | 127.0.0.1 | 远程压测时设为 0.0.0.0 或指定网卡地址 |
| port | 8080 | HTTP 端口；0 分配临时端口 |
| transport | nio | nio / epoll / io_uring |
| mode | validate | validate / discard |
| workers | JVM 可用 CPU 数 | Worker EventLoop 数；另有 1 个 boss |
| maxBodyBytes | 1048576 | 单请求最大 body 字节，最大可设 64 MiB |
| maxEvents | 1000 | validate 模式单批事件数上限 |
| idleSeconds | 60 | 连接没有读写活动时关闭；不是完整请求总耗时限制 |
| maxConnections | 10000 | 单进程同时连接上限，压测十万连接时需显式调整 |
| ringSize | 4096 | io_uring SQ 配置容量；不是固定批量大小 |

限制每个请求不能替代全局内存预算。大量连接同时上传接近上限的请求体时，聚合缓冲可能消耗大量内存。
当前阶段先建立受控基线，后续再加入全局在途字节预算、慢速上传防护和过载响应。

## 压测起点

安装 wrk 后从项目根目录运行：

```bash
# 先用相同配置启动一种 transport；discard/validate 分别测试
java --enable-native-access=ALL-UNNAMED -Xms1g -Xmx1g \
  -Dgateway.transport=nio -Dgateway.mode=discard -Dgateway.workers=4 \
  -jar target/atlas-gateway.jar

# 预热
wrk -t4 -c128 -d20s -s benchmarks/report.lua http://127.0.0.1:8080/v1/events/batch
# 测量
wrk -t4 -c128 -d60s --latency -s benchmarks/report.lua http://127.0.0.1:8080/v1/events/batch
```

示例批次有 2 个事件。validate 模式成功 RPS × 2 才是事件速率；discard 模式仅知道请求数。
脚本复用相同 eventId 是为了固定负载，目前没有去重。连接上限、payload、线程、JVM 参数都应保持一致。
脚本不使用逐响应 Lua 回调，避免压测端回调本身降低吞吐；请同时检查 wrk 的 HTTP/连接错误和服务端成功批次增量。

比较建议：
1. Linux 上分别跑 NIO / epoll / io_uring，每种预热后至少重复 3 次。
2. 分别测试 discard 与 validate，区分网络/HTTP 和 JSON 校验开销。
3. 改变连接数、批次大小和上报间隔；后续加入弱网补传、集中重连、TLS。
4. 同时记录服务端 CPU、RSS、GC、成功吞吐、错误率及客户端尾延迟。
5. 初始 128 连接只是冒烟，不代表十万设备负载。十万连接需独立压测端、足够 fd 和连接源地址/端口资源，
   并显式提高 maxConnections；不要直接在本机虚拟机上推断生产容量。

wrk 是闭环负载，服务变慢时发送速率也会下降；其延迟结果不足以证明固定到达率下的 SLO。
严谨的固定速率与尾延迟评估另用 wrk2 或支持到达率模型的压测器，确认压测端不是瓶颈。
当前包含入门脚本，尚未生成任何 epoll/io_uring 性能结论。

## 代码导航

```text
src/main/java/dev/atlas/gateway/
  GatewayApplication.java                  进程入口、关闭钩子
  config/GatewayConfig.java                启动配置和边界校验
  server/GatewayServer.java                HTTP pipeline 和生命周期
  transport/Transport.java                 事件循环与 Channel 的配套选择
  http/ConnectionMetricsHandler.java       连接限制、流量指标、空闲关闭
  http/IngestHandler.java                  HTTP 路由、丢弃、响应
  validation/EventBatchValidator.java      批次结构校验
  metrics/GatewayMetrics.java              线程安全指标和 Prometheus 输出
examples/batch.json              示例请求
benchmarks/report.lua            wrk 上报脚本
src/test/                       配置、接口、资源释放和真实 socket 测试
```

架构边界、演进目录、线程模型和学习路线见 [架构设计](docs/architecture.md)。

学习入口：`Transport.select()` → `GatewayServer.start()` → `IngestHandler.channelRead0()`。
当前业务层不直接操作 SQE/CQE，ring 提交与完成分发由 Netty 管理。
理解 Channel 的接入路径后，可结合 Netty 源码跟踪 scheduleRead、SQE 入队和 CQE 回调。

后续迭代：全局过载保护 → TLS/鉴权 → 更真实的车端负载发生器 → 异步 Kafka 投递 → 其他协议入口。
Kafka 加入前，先明确成功响应的持久化语义，再调整客户端重试与去重策略。
