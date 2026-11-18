# Atlas Gateway 架构设计

## 定位与阶段

Atlas Gateway 是以地图行为上报为实际场景的 Netty 学习和性能实验项目。
客户端包括车载地图、App 与主站，事件包括搜索、点击、sug 展示与点击。
使用 JDK 25、Netty 4.2.16.Final，Maven 单模块构建，以 netty-all 支持后续协议学习。

当前已实现 HTTP 接收、可选校验、指标与丢弃，运行时切换 NIO / epoll / io_uring。
以下“规划”目录和组件是未来演进边界，不表示已经实现 Kafka、多协议或独立实验程序。

## 架构选择

继续使用单模块、按职责分包。先保持一次构建、一次启动，方便打断点观察完整收发链路。
多 Maven 模块适合组件已有独立发布或依赖隔离需求后再拆；目前无需承担模块管理成本。
通用插件框架、动态协议发现和服务注册不在当前范围内，每增加一个协议先显式组装 pipeline。

netty-all 负责提供可用模块，不自动启用协议、启动端口，也不自动选择 io_uring。
transport 仍由配置显式选择，底层运行能力由内核、架构和原生加载共同决定。

## 当前实现与职责

| 包 / 类 | 责任 | 边界 |
|---|---|---|
| GatewayApplication | 加载配置、启动、注册关闭钩子 | 不放协议和事件业务逻辑 |
| config/GatewayConfig | 不可变配置、默认值与边界检查 | 不在 Handler 中散落读取系统属性 |
| server/GatewayServer | 管理 boss、worker、监听端口和客户端资源，组装当前 HTTP pipeline | 当前实现仍为 HTTP 服务，尚不是多协议容器 |
| transport/Transport | 配套选择 IoHandlerFactory 与 ServerChannel | 不处理业务路由；不把 ring 暴露给业务 |
| http/IngestHandler | HTTP 路由、状态码、接收与丢弃 | 当前直接调用校验器，尚未抽出统一应用服务 |
| http/ConnectionMetricsHandler | HTTP 流量、连接计量、连接上限与空闲关闭 | HTTP 特有指标不直接复用为 UDP 指标 |
| validation/EventBatchValidator | JSON 批次结构校验 | 不做存储、网络访问或第三方查询 |
| metrics/GatewayMetrics | 线程安全统计与文本快照 | 封装可变计数器，不向调用方暴露字段 |

当前数据流：客户端 HTTP 请求 → 解码 → 连接/流量计量 → 请求体聚合 → 路由 → 可选校验 → 计数 → 丢弃 → HTTP 响应。
HTTP 聚合器位于指标 Handler 之后，保证其直接产生的 413 响应也能计量。

## 扩展后的逻辑边界（规划）

未来协议接入层只负责把不同协议转换成统一事件，应用层决定如何处理，输出端负责具体投递。
逻辑调用方向：协议接入 → 事件处理服务 → 输出端口；输出实现通过启动时组装注入。
事件处理服务不依赖 HTTP 请求、ChannelHandlerContext、KafkaProducer 或原始 CQE。

建议逐步演进为：

```text
dev.atlas.gateway
  GatewayApplication
  config/                         公共与各协议配置
  server/                         进程内监听器和资源生命周期
  transport/                      NIO、epoll、io_uring 选择
  http/                           已实现；以后可迁移为 protocol/http
  validation/                     已实现的 JSON 批次校验
  metrics/                        指标和观测
  protocol/                       多协议阶段新增
    http/                         HTTP pipeline、路由和响应适配
    websocket/                    WebSocket 握手、帧处理和响应适配
    mqtt/                         MQTT 编解码与接入语义
    tcp/                          自定义帧协议
    udp/                          数据报协议
  model/                          跨协议共享的事件与批次模型
  application/                    协议无关的接收、校验、处理结果
  sink/                           异步输出端口与丢弃实现
    kafka/                        后续 Kafka 输出实现

dev.atlas.lab                     规划中的独立学习示例，使用单独 main 启动
  codec/                          编解码、拆包粘包
  buffer/                         ByteBuf 生命周期、池化
  transport/                      底层 IoHandle、SQE/CQE 实验

benchmarks/                       负载脚本、场景说明、测试结果模板
examples/                         可复现的请求与协议样例
docs/                            架构、源码阅读记录和实验结论
```

只在实际开始新增协议时迁移 http 包和拆分 pipeline 初始化，不一次性创建所有空目录和空接口。
lab 示例与网关入口分开，默认不启动、不占用网关端口，也不把实验开关混入业务上报逻辑。

## 统一消息与处理结果（规划）

统一事件包含 eventId、eventType、eventTime、source、schemaVersion 及业务属性。
客户端输入的 source 等元数据与未来从鉴权上下文获得的身份要区分，不能把客户端自报标识当成可信身份。
模型应是独立且明确归属的对象，业务应用层不长期持有 Netty 引用计数对象。

处理结果至少区分格式错误、过载、输出失败和成功，由协议适配层翻译成各协议响应。
HTTP 可返回状态码，WebSocket 可返回带请求 ID 的消息；UDP 没有连接，也不能承诺与 HTTP 相同的确认语义。
MQTT 编解码不等同于完整 Broker，实现 QoS、会话和订阅前必须单独定义范围。

当前 validate 路径只返回校验结果和事件数，不为未来模型提前增加复制与分配。
多协议需要复用事件处理时再抽取应用服务；discard 基线继续跳过解析，避免改变压测含义。

## 线程与内存

boss 接受 TCP 连接，worker EventLoop 处理其 I/O 和轻量业务；多个连接共享一个 EventLoop。
同一 Channel 的回调由其 EventLoop 串行执行，指标等跨连接共享状态仍需并发安全。

当前 JSON 校验在 EventLoop 执行，基线测试明确包含这部分 CPU 开销。
后续重校验、外部调用或潜在阻塞的输出操作交给有界执行器；队列满时拒绝，不使用无限队列吸收压力。
若异步执行可能改变同一连接响应顺序，必须显式排序或禁止 HTTP pipelining，不能让响应错配。

当前 FullHttpRequest 由 SimpleChannelInboundHandler 自动释放。
若未来将 ByteBuf 交给异步任务，必须 retain 并在所有成功、失败、拒绝、取消路径释放，
或在 EventLoop 中解析成独立对象后转交。CQE 回调对象同样不能持有到有效期之外。

## 输出和确认语义（规划）

未来输出端口采用异步结果，由丢弃实现和 Kafka 实现分别提供。
当前 204 仅表示接收/校验后丢弃；接入 Kafka 后需要明确切换成“Kafka 确认后成功”，
不能在只是排入进程内队列时就宣称已持久化。

KafkaProducer 有自己的网络线程；网关选择 io_uring 不会替换 Kafka 客户端的 transport。
异步 send 也可能在等待元数据或缓冲区时阻塞，需要隔离入口并限制在途量。
关闭顺序在引入输出端后扩展为：停止接入 → 有期限排空 → 关闭输出端 → 释放连接与线程。

## 过载保护演进

已实现：单请求 body 上限、事件数上限、连接数上限、连接空闲关闭。
规划补齐：进程级在途字节预算、请求/事件速率限制、完整上传截止时间、输出队列容量、熔断和排空。
单请求上限不能控制所有连接同时上传时的总内存；空闲超时不能防止持续极慢上传。
容量测试逐级增加，不把“十万设备总数”直接等同于“十万活跃连接”或“十万 RPS”。

## 学习与验证路线

1. HTTP NIO 跑通请求、Keep-Alive、错误响应和 ByteBuf 释放。
2. Linux 使用相同 Handler 对比 epoll / io_uring，记录 CPU、吞吐、RSS、GC、错误率和客户端尾延迟。
3. 独立 lab 提交 NOP 并观察 userData、SQE/CQE；ring 提交时机依旧由 Netty 管理，精确策略研究结合源码。
4. 学习 buffer ring、multishot 和零拷贝时，每次只变更一个配置/特性并复测。
5. 加入 WebSocket 或自定义 TCP 时抽取统一模型和处理服务，验证协议适配边界。
6. 完善全局背压、TLS/鉴权，再接入异步 Kafka，验证确认和重试语义。

## 压测原则

同一负载、worker 数、JVM、内核和业务代码，只改变 transport。
discard 与 validate 分开测；直连接入与代理/TLS 部署分开测；HTTP 消息批量与 SQE 批量不是同一概念。
先建立闭环吞吐基线，再用固定到达率负载评估尾延迟，监控压测端自身的资源限制。
网关和负载发生器尽量独立部署；连接重建、弱网补传和后端变慢分别成为独立场景。

## 测试边界

配置与校验使用单元测试；HTTP pipeline 用 EmbeddedChannel 测试协议和资源释放；
完整服务使用真实 socket 验证生命周期和交互；原生 transport 在匹配 Linux 上实际运行。
未来 sink 的超时、拒绝和异步失败测试使用可控实现，不需要每次启动 Kafka。
所有新增类提供中文职责注释，复杂实现解释资源所有权、线程约束和错误处理原因。

## 已实现的可观测性

GatewayMetrics 使用独立 Micrometer Prometheus registry，绑定 JVM、默认 Netty 分配器、业务计数与耗时桶。
Linux RSS 通过守护采样线程每秒更新缓存，不在 EventLoop 上读取 proc 文件。服务关闭时释放 GC 监听器与采样线程。
可复现实验脚本、系统采集、监控面板和指标边界见 [可观测性手册](observability.md)。
