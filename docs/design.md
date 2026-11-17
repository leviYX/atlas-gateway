# 第一版设计与执行计划

## 已确认范围
地图行为上报接入服务，JDK 25、独立 Maven 工程、Netty 4.2。
HTTP/1.1 Keep-Alive；POST /v1/events/batch。discard 模式只接收请求体；
validate 模式校验 JSON 批次。两种模式均不保存、不投递 Kafka，返回 204。
GET /health 和 GET /metrics 提供探活与 Prometheus 文本指标。
NIO 用于 macOS；epoll/io_uring 在 Linux 显式选择，失败时不静默回退。

## 组件
GatewayConfig：系统属性与边界检查。
Transport：事件循环与 Channel 配套选择。
GatewayMetrics：线程安全计数、处理时间累计值、连接指标。
EventBatchValidator：无存储的批次结构校验。
IngestHandler：路由、响应与丢弃；ConnectionMetricsHandler：连接和 HTTP 流量计量。
GatewayServer/Application：启动、关闭和进程入口。

## 执行步骤
1. Maven wrapper、JDK 25 构建、Linux 原生依赖 profiles。
2. 先写 EmbeddedChannel 接口测试，验证缺少实现时失败。
3. 实现配置、transport、校验、HTTP pipeline、指标。
4. 添加真实 NIO socket 测试，构建可执行 jar；实际 HTTP 冒烟。
5. README、示例请求、wrk 脚本，说明 HTTP 基线与生产能力边界。

## 验收
合法批次 204；非法 JSON/字段 400；媒体类型错误 415；超大请求 413；
不支持方法 405；未知路径 404；事件超限拒绝；Keep-Alive 可复用。
discard 不做 JSON 校验，事件数不计入已校验事件。
所有出站结果计数；没有逐消息日志；请求体由 Netty 自动释放。
单请求限制不能替代连接/全局在途限制；本版不是生产高可用部署。

## 分包约定
启动入口留在根包；config 管理配置；server 管理生命周期；transport 选择网络实现；
http 处理 HTTP pipeline 事件；validation 校验上报内容；metrics 封装指标状态。
测试按被测类包路径组织，跨包通过明确的方法协作，不公开可变计数器字段。
所有类均提供中文职责说明，关键实现说明资源所有权、并发和 pipeline 顺序。
