# Atlas Gateway 双机部署与压测操作手册

本文面向当前 OrbStack Ubuntu 学习环境，使用 JDK 25 + Netty 4.2。目标是完成安装、同步、启动、指标采集、浏览器观察，以及 epoll / io_uring 同参数对比。本文独立于 README。

网关接收地图搜索、点击、sug 等批量事件；当前成功请求也会丢弃数据，没有接入 Kafka / Doris，HTTP 204 不表示数据持久化。

## 1. 机器、地址与目录

| 角色 | 当前配置 | 负责什么 |
|---|---|---|
| Mac 宿主机 | 源码 `/Users/levi/develop/project/my/netty/netty/atlas-gateway` | 修改代码、浏览 Grafana、SSH 连接 |
| A：网关机 | IPv4 `192.168.139.168`，用户 `levi` | JDK 25、网关、JFR、GC、Linux 本机采集 |
| B：压测与监控机 | 终端主机名 `client`，用户 `levi`；IPv4 在 B 用 `hostname -I` 获取 | wrk、Docker、Prometheus、Grafana |
| A / B 项目目录 | `/home/levi/projects/atlas-gateway` | 两台机器都使用此目录 |
| Linux 中的 Mac 源码挂载 | `/mnt/mac/Users/levi/develop/project/my/netty/netty/atlas-gateway` | 从 Mac 同步项目 |

后文命令分别标注在哪台机器执行。不要把 B 的 IP 当成网关地址；当前压测目标始终是 `http://192.168.139.168:8080`。虚拟机重建或网络变更后重新检查 IP。

数据路径：B 的 wrk → A:8080；B 的 Prometheus → A:8080/metrics；B 的 Grafana → Prometheus 容器。Mac 浏览器访问 B 的 Grafana。

A 不需要 Docker；B 不运行 Java 网关，仅使用 wrk 时不需要 JDK 或 Maven。
两台虚拟机共享 Mac 的物理资源，分开运行只能减少同一 guest 内的竞争，不能消除宿主机 CPU、内存和网络竞争。

## 2. 首次准备与源码同步（A、B 都执行）

```bash
sudo apt-get update
sudo apt-get install -y rsync curl ca-certificates python3
mkdir -p /home/levi/projects/atlas-gateway

rsync -av \
  --exclude='.git' \
  --exclude='target' \
  --exclude='results' \
  --exclude='scripts/gateway.local.conf' \
  /mnt/mac/Users/levi/develop/project/my/netty/netty/atlas-gateway/ \
  /home/levi/projects/atlas-gateway/

cd /home/levi/projects/atlas-gateway
ls -l scripts/start.sh benchmarks/report.lua examples/batch.json
```

此命令也用于以后更新。它不删除目标已有文件，保留 Linux 配置、构建产物及实验结果；代码更新后 A 必须重新构建。
不要用 `cp -R .../. ./` 覆盖 `.git`：只读 Git 对象可能出现 Permission denied。
若 `/mnt/mac/...` 不存在，先检查 OrbStack 的 Mac 文件共享是否可用，不能靠 sudo 解决不存在的挂载。

命令中的 `\` 仅用于连接下一行，最后一行不加 `\`。本文的占位文字（例如 `B的IPv4`）必须替换，不能原样执行。

## 3. 安装并配置 JDK 25（仅 A）

已经安装且 `java`、`javac` 均为 25，可以跳过安装。

```bash
uname -m
java -version
javac -version
```

推荐下载 Linux JDK 25 的 tar.gz 归档版，例如从 [Eclipse Temurin](https://adoptium.net/temurin/releases/?version=25) 选择版本 25、Linux、JDK、HotSpot，并按架构选 aarch64（`uname -m` 为 aarch64）或 x64（x86_64）。不要下载 macOS 包、JRE 包或其他 Java 主版本。
可在 Mac 下载后通过 `/mnt/mac/Users/levi/Downloads/` 读取。将下载的实际路径设置为 `JDK_ARCHIVE`：

```bash
# 将这一行路径改为实际下载文件，不要照抄文件名
JDK_ARCHIVE=/mnt/mac/Users/levi/Downloads/你的JDK25压缩包.tar.gz

mkdir -p "$HOME/.local/jdks/jdk-25"
tar -xzf "$JDK_ARCHIVE" --strip-components=1 -C "$HOME/.local/jdks/jdk-25"

cat >> "$HOME/.bashrc" <<'JDK_ENV'
export JAVA_HOME="$HOME/.local/jdks/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"
JDK_ENV

source "$HOME/.bashrc"
java -version
javac -version
```

上面的解压步骤只用于首次安装到空目录。已有其他位置的 JDK 25 时，将 JAVA_HOME 指向现有目录即可，不必重复安装。
项目脚本要求 Java 主版本 25；Maven wrapper 会自行下载 Maven 和依赖，无需单独安装 Maven。

## 4. 安装本机诊断工具并构建（A）

```bash
cd /home/levi/projects/atlas-gateway
bash scripts/setup.sh
bash scripts/build.sh
ls -lh target/atlas-gateway.jar
uname -r
```

`setup.sh` 安装 python3、curl、wrk、sysstat、iproute2，并打印 Java / javac 版本；不安装 JDK、Docker，也不改内核。
A 虽不负责发压，使用该脚本会同时安装 wrk，可以保留。
`build.sh` 执行 Maven wrapper 的 `clean verify`，包含测试。首次构建需要网络下载依赖。
io_uring 取决于 Linux 内核、架构、原生库及系统策略，内核版本新不代表一定可用；以网关成功启动且 runtime 指标显示 io_uring 为准。无需另装 liburing。

## 5. 配置并启动网关（A）

常用默认值在 `scripts/gateway.conf`。个人参数放在不提交 Git 的 `scripts/gateway.local.conf`，同步时不会覆盖。

```bash
cd /home/levi/projects/atlas-gateway
cat > scripts/gateway.local.conf <<'CONF'
BIND_HOST=0.0.0.0
PORT=8080
WORKERS=2
HEAP=512m
MODE=validate
JFR_ENABLED=true
JFR_SETTINGS=default
NMT_ENABLED=false
CONF

bash scripts/start.sh epoll
bash scripts/status.sh
ss -lntp 'sport = :8080'
curl --max-time 3 http://127.0.0.1:8080/health
```

应看到运行中、监听 `0.0.0.0:8080`、返回 `ok`。后台服务不随查看日志窗口关闭而停止。

```bash
bash scripts/logs.sh
```

Ctrl+C 仅退出日志查看。停止服务使用 `bash scripts/stop.sh`；SIGTERM 会触发关闭及 JFR 落盘，不要直接 kill -9。
切换 transport 必须先停：

```bash
bash scripts/stop.sh
bash scripts/start.sh io_uring
```

不可用的原生 transport 会启动失败，不静默回退 NIO。
网关配置修改后需要停止并重新启动；代码变化则先停止、重新 build、再启动。

## 6. 安装压测工具并检查网络（B）

```bash
sudo apt-get update
sudo apt-get install -y wrk curl python3
cd /home/levi/projects/atlas-gateway
export GATEWAY_URL=http://192.168.139.168:8080
curl --max-time 3 "$GATEWAY_URL/health"
curl --max-time 3 "$GATEWAY_URL/metrics" | head -c 2000
```

第一条 HTTP 请求应返回 `ok`。第二条用来确认 Prometheus 文本指标可访问；截断输出时 curl 可能报告写入管道失败，这不代表网关失败。
验证上报接口：

```bash
curl -i "$GATEWAY_URL/v1/events/batch" \
  -H 'Content-Type: application/json' \
  --data-binary @examples/batch.json
```

应返回 `204 No Content`。若失败，先解决请求问题再压测。
若连不上，A 上检查 status、ss 和本机 curl；确认 A 的 IP 仍是 `192.168.139.168`。如果 A 的 UFW 已启用，仅为 B 放行 8080：

```bash
# 在 A 执行，将 B的IPv4 替换为实际地址
sudo ufw status
sudo ufw allow from B的IPv4 to any port 8080 proto tcp
```

不要在网络不通时反复增加 wrk 并发数。

## 7. 安装 Docker 并启动服务（B）

以下适用于当前 Ubuntu 软件仓库提供 docker-compose-v2 的环境；若找不到包，请使用 Docker 官方 Ubuntu 安装文档安装 Engine 和 Compose plugin，不要混装两套 Engine。

```bash
sudo apt-get update
sudo apt-get install -y docker.io docker-compose-v2
sudo systemctl enable --now docker
sudo usermod -aG docker levi
```

退出 SSH 并重新连接 B，让组权限生效；也可执行 `newgrp docker` 后在新 Shell 继续。

```bash
id -nG
docker version
docker compose version
docker ps
```

`id -nG` 应包含 docker，`docker ps` 不应报 socket permission denied。Docker 组具有高权限，仅加入需要管理容器的用户。
报无法连接 daemon 时检查 `sudo systemctl status docker`；daemon socket Permission denied 主要检查组权限，不要 chmod 666 docker.sock。

## 8. 配置 Prometheus 和 Grafana（B）

编辑：

```bash
cd /home/levi/projects/atlas-gateway
nano observability/prometheus/prometheus.yml
```

完整配置可使用：

```yaml
global:
  scrape_interval: 5s
  evaluation_interval: 5s
scrape_configs:
  - job_name: atlas-gateway
    scrape_timeout: 3s
    metrics_path: /metrics
    static_configs:
      - targets: ['192.168.139.168:8080']
```

这里必须是 A 的地址，不要保留默认的 host.docker.internal，否则会抓取 B。

```bash
bash scripts/monitor-up.sh
docker compose -f observability/compose.yaml ps
```

按提示设置 Grafana admin 密码；不回显是正常现象。脚本会拉取镜像，第一次可能较慢。
若手工 compose 命令因环境插值报缺密码，可为只读诊断命令加前缀：

```bash
GRAFANA_ADMIN_PASSWORD=unused docker compose -f observability/compose.yaml ps
GRAFANA_ADMIN_PASSWORD=unused docker compose -f observability/compose.yaml logs --tail=100 prometheus
```

项目自动 provision 数据源和 `Atlas` 文件夹中的 `Atlas Gateway 压测总览` 仪表盘，无需手工导入原始总览 JSON。
修改 prometheus.yml 后重启 Prometheus：

```bash
GRAFANA_ADMIN_PASSWORD=unused docker compose -f observability/compose.yaml restart prometheus
```

### 8.1 Mac 如何打开界面：二选一

**方式一：SSH 转发，保留默认回环绑定。** 在 Mac 终端执行，将 B的IPv4 替换：

```bash
ssh -N \
  -L 3000:127.0.0.1:3000 \
  -L 9090:127.0.0.1:9090 \
  levi@B的IPv4
```

终端没有提示符、保持等待是正常的。Mac 打开 `http://localhost:3000` 和 `http://localhost:9090/targets`。结束 SSH 会关闭转发。

**方式二：虚拟机内网直接访问。** 在 B 编辑 `observability/compose.yaml`：

```yaml
# prometheus 服务的 ports
ports:
  - "9090:9090"

# grafana 服务的 ports
ports:
  - "3000:3000"
```

这是两个服务各自的片段，不能作为一个 YAML 文件直接粘贴。将原有 `127.0.0.1:...` 映射替换后执行 `bash scripts/monitor-up.sh`。
Mac 打开 `http://B的IPv4:3000` 和 `http://B的IPv4:9090/targets`。此时端口会向能访问 B 的设备开放；限于可信的本地学习网络，不要直接映射公网。
若浏览器访问失败，检查容器是否 running、端口是否仍仅绑定回环、B 的网络和防火墙。

### 8.2 登录和确认指标

Grafana 用户名是 **admin**，不是 levi。密码是首次初始化成功时输入的密码；之后重新启动时输入其他密码不会覆盖持久卷内的旧密码。
忘记密码，在 B：

```bash
cd /home/levi/projects/atlas-gateway
GRAFANA_ADMIN_PASSWORD=unused docker compose -f observability/compose.yaml exec grafana \
  grafana cli admin reset-admin-password '替换为新密码'
```

先在 Prometheus `/targets` 确认 `atlas-gateway` 为 UP。再登录 Grafana → Dashboards → Atlas → Atlas Gateway 压测总览，选择实例 `192.168.139.168:8080` 或 All，刷新 5s，时间范围 Last 30 minutes。
监控启动后等待至少 1 分钟，rate 指标才有较完整窗口。No data 不等于数值为 0。
Prometheus 从启动抓取后开始记录，不会自动导入此前 process.csv、wrk 日志或 JFR。停止监控默认保留卷内历史，当前 Prometheus 保留时间为 7 天。

## 9. 创建不同颜色的 transport 对比图

原始总览目前多数指标按实例展示，没有自动拆分 transport。仅在 UI 改颜色，无法把同地址重启前后的一条系列拆成两种 transport。
已有 `atlas_runtime_info{transport="epoll",mode="validate"}` 可以关联其他指标。本节通过 PromQL 关联来生成两条系列，无需修改 Java 代码。

在 Grafana 新建独立 Dashboard，命名 **Atlas epoll vs io_uring 对比**，添加可视化，选择项目的 Prometheus 数据源，查询切换到 Code 模式。
不要直接修改文件 provision 的总览；新建独立 Dashboard 后 Save，编辑会保存在 Grafana 持久化卷中。完成后建议 Export JSON 备份。

### 9.1 CPU 面板

粘贴以下查询，图例 Legend 设置为 `{{transport}}`，标题“进程 CPU / transport”，单位 Percent (0.0–1.0)：

```promql
process_cpu_usage{job="atlas-gateway",instance="192.168.139.168:8080"}
* on(job, instance) group_left(transport)
atlas_runtime_info{job="atlas-gateway",instance="192.168.139.168:8080",mode="validate"}
```

### 9.2 RSS 面板

图例同样设置为 `{{transport}}`，标题“RSS / transport”，单位 Bytes：

```promql
atlas_process_resident_memory_bytes{job="atlas-gateway",instance="192.168.139.168:8080"}
* on(job, instance) group_left(transport)
atlas_runtime_info{job="atlas-gateway",instance="192.168.139.168:8080",mode="validate"}
```

### 9.3 上报批次吞吐面板

图例 `{{transport}}`，标题“成功上报批次/s / transport”，单位 ops/s：

```promql
sum by (transport) (
  rate(atlas_batches_accepted_total{job="atlas-gateway",instance="192.168.139.168:8080"}[1m])
  * on(job, instance) group_left(transport)
  atlas_runtime_info{job="atlas-gateway",instance="192.168.139.168:8080",mode="validate"}
)
```

若使用 discard 模式，三个查询都将 mode 改为 discard。不要用相同图例合并不同业务模式的实验。
关联查询依赖当前抓取时刻的 runtime 信息；切换进程前后，1 分钟 rate 窗口可能覆盖边界，应在新进程就绪后至少等待 1 分钟再进入正式测量，并排除切换附近的数据。它不能代替严格的每轮实验标签。

### 9.4 固定颜色

每个对比面板中依次设置：

1. Edit → Overrides → Add field override。
2. 选择 Fields with name，填写或选择 `epoll`。
3. Add override property → Standard options / Color scheme（不同版本可能显示 Color）→ Single color，设为蓝色 `#5794F2`。
4. 再为 `io_uring` 添加覆盖，设为橙色 `#FF9830`。
5. 可选为 `nio` 设置绿色 `#73BF69`。
6. Apply，保存 Dashboard。

如果某个 transport 尚未跑过，菜单可能暂时没有该系列。可以先跑两轮再选择；或使用 Fields with name matching regex 分别填写 `^epoll$`、`^io_uring$`。
图例必须先设置成 `{{transport}}`，颜色才能按上述名字匹配。所有对比面板保持同一配色。
在图形选项中将 Connect null values 设为 Never，避免连接跨越重启空档的点。

两轮顺序运行会显示在各自真实时间段，不会自动从第 0 秒重叠。先用同一时间范围观察两段平台；如要严格对齐曲线，需要进一步增加实验时间对齐能力。
wrk 客户端延迟不会自动进入 Grafana；总览中的服务端回调耗时不等于客户端端到端延迟，客户端 P95/P99 以 wrk 日志为准。

## 10. 一次完整的 epoll / io_uring 对比

### 10.1 固定实验条件

两轮固定同一 jar、JDK、内核、2 个 worker、512m 堆、validate 模式、同一 JSON、JFR 开关、64 或 256 个连接、相同 wrk 线程和时长。A、B、Mac 都避免同时构建或做其他重负载任务。
首次使用 64 连接验证；下面以 256 连接演示。内网带宽或 B 的 CPU 饱和时，结果可能反映压测端上限。
建议每种 transport 至少重复 3 轮，交替顺序，不以一次结果下结论。

### 10.2 epoll：在 A 启动并采集

```bash
cd /home/levi/projects/atlas-gateway
bash scripts/stop.sh
bash scripts/start.sh epoll
curl -fsS http://127.0.0.1:8080/metrics | rg atlas_runtime_info
```

如果未安装 rg，最后一行可改为：

```bash
curl -fsS http://127.0.0.1:8080/metrics | grep atlas_runtime_info
```

首次无运行记录时 stop.sh 可能提示没有记录，可直接继续启动。确认 transport 为 epoll 后，等待 Prometheus 采集至少 1 分钟。
在 A 的另一终端开始本机采集（采集期间操作 B）：

```bash
cd /home/levi/projects/atlas-gateway
bash scripts/collect.sh 180
```

### 10.3 epoll：在 B 压测并保存结果

以下整段在 B 的 Bash 中执行：

```bash
cd /home/levi/projects/atlas-gateway
export GATEWAY_URL=http://192.168.139.168:8080
TRANSPORT_LABEL=epoll
OUT="results/remote-$(date -u +%Y%m%dT%H%M%SZ)-$TRANSPORT_LABEL"
mkdir -p "$OUT"
set -o pipefail

# 记录实验条件和请求体
cp examples/batch.json "$OUT/batch.json"
{
  echo "transport_label=$TRANSPORT_LABEL"
  echo "url=$GATEWAY_URL"
  echo 'wrk_threads=2 connections=256 warmup=20s measure=60s'
  date -u
  uname -a
  sha256sum examples/batch.json
  curl -fsS "$GATEWAY_URL/metrics" | grep atlas_runtime_info
} > "$OUT/metadata.txt"

# 预热
wrk -t2 -c256 -d20s --latency \
  -s benchmarks/report.lua \
  "$GATEWAY_URL/v1/events/batch" \
  2>&1 | tee "$OUT/warmup.log"

# 正式测量并记录时间；如果预热失败，先处理错误，不要继续
 date -u +%Y-%m-%dT%H:%M:%SZ > "$OUT/measure-start.txt"
wrk -t2 -c256 -d60s --latency \
  -s benchmarks/report.lua \
  "$GATEWAY_URL/v1/events/batch" \
  2>&1 | tee "$OUT/measure.log"
 date -u +%Y-%m-%dT%H:%M:%SZ > "$OUT/measure-end.txt"
echo "报告目录：$OUT"
```

`TRANSPORT_LABEL` 只命名报告，不会切换 A 的 transport；实际运行状态以 A 的启动及 runtime 指标为准。
`pipefail` 便于发现 wrk 失败，但交互 Shell 不会因此自动停止后续命令，发现错误先停止这一轮。
Lua 使用相对路径读取 `examples/batch.json`，必须在项目根目录运行。请求 ID 在每次上报中重复，当前没有去重，不用于验证去重性能。

### 10.4 观察、停止并切换

压测时打开 Grafana 的总览和对比仪表盘，时间 Last 30 minutes、刷新 5s。按 measure-start / end 的 UTC 时间定位正式区间；Grafana 默认可能显示浏览器本地时间，可把 Dashboard 时间设置成 UTC。
观察结束后让 A 的采集完成，再在 A：

```bash
bash scripts/stop.sh
bash scripts/start.sh io_uring
```

确认 runtime 显示 io_uring，等待至少 1 分钟，再在 A 启动 `bash scripts/collect.sh 180`。
回到 B，重复 10.3 整段，只将 `TRANSPORT_LABEL=epoll` 改为 `TRANSPORT_LABEL=io_uring`。
第二轮结束后 A 再执行 `bash scripts/stop.sh`，让第二份 JFR 落盘。
Prometheus/Grafana 保持运行；在一个时间范围内看到蓝色 epoll 和橙色 io_uring 的两段曲线。

### 10.5 应该观察什么

| 内容 | 位置 | 解读 |
|---|---|---|
| Requests/sec | B 的 measure.log | 请求吞吐，不能直接当事件吞吐，批次中有多个事件 |
| Latency Distribution | B 的 measure.log | 客户端延迟分位数；wrk 默认输出并不保证包含 P95，勿把 P90 当 P95 |
| Socket errors、timeouts | B 的 measure.log | 连接、读写、超时问题；非零时先排查 |
| Non-2xx or 3xx | B 的 measure.log | 业务或协议拒绝；退出码 0 不代表所有请求成功 |
| 成功批次/s、事件/s | Grafana 总览 | 业务成功量；HTTP 请求数还包含探活和 metrics 抓取 |
| CPU | Grafana、A 的 pidstat.log | Micrometer 归一化 CPU 和 pidstat 单核 100% 口径不同 |
| RSS、堆、分配器 direct | Grafana | RSS 不等于堆；几项不能简单相加 |
| GC、对象分配 | Grafana、GC 日志、JFR | 是否为 JSON 解析等分配开销主导 |
| EventLoop tasks | Grafana | 待执行任务，不是 SQE / CQE 队列深度 |
| 文件描述符、连接数 | Grafana / A 系统命令 | 是否达到资源限制 |

同连接数下较高吞吐不代表同负载下更低资源消耗；wrk 是闭环模型，服务慢时发压速率会下降，低估外部持续到达请求下的排队问题。
如果差异很小，可能瓶颈在业务解析、虚拟化、网络或 B，而不在 I/O 系统调用。

## 11. 大量连接和车辆上报模拟

Netty 能管理大量连接，但当前默认 `gateway.maxConnections` 是 **10000**，并非无限；压测到此上限会出现拒绝。启动包装脚本目前没有为此参数提供专用配置项，不要凭空添加一个 MAX_CONNECTIONS 环境变量期待生效。
连接数、每连接上报频率、请求体大小、批次大小必须分别定义。
wrk `-c256` 表示总共 256 个连接不断发送请求，不代表 256 辆车每隔数秒上报，更不等于十万车辆低频长连接。

先逐级测 64、256、1000、5000 连接，每级固定其他条件并保存报告，检查 A/B 两端 `ulimit -n`。连接超过当前 shell 限制可在允许的硬限制内临时调整：

```bash
ulimit -Sn
ulimit -Hn
# 仅在硬限制允许时，A 启动服务前和 B 启动 wrk 前分别执行
ulimit -n 65535
```

已启动进程不会跟随其他终端的 ulimit 变化。不要在两台小虚拟机上一上来测试十万连接。
单个 B 源 IP 连接同一 A:8080 还受临时端口范围和 TIME_WAIT 影响；真正十万连接可能需要多个发压地址/机器，以及支持连接保持和低频上报的专门生成器。此能力目前未包含在现有 wrk 脚本中。

## 12. 每个脚本的用途与适用机器

所有入口建议用 `bash scripts/xxx.sh`，不依赖可执行位。入口根据自身位置定位项目，但直接使用 wrk + Lua 时仍必须在根目录。

| 文件 | 用途 / 命令 | 适用位置 |
|---|---|---|
| setup.sh | 安装辅助包，检查 Java/Javac 版本；不装 JDK | A；B 建议手工只装 wrk/curl/python3 |
| build.sh | `bash scripts/build.sh`，clean verify 生成 jar | A 或 Mac 构建机 |
| start.sh | 后台启动；`start.sh epoll` / `start.sh io_uring` / `start.sh nio`；不带参数默认 io_uring | A |
| stop.sh | SIGTERM 停止受脚本管理的网关，保留结果并落盘 JFR | A |
| status.sh | 运行状态、PID、transport、结果标识 | A |
| logs.sh | tail -f 服务日志；Ctrl+C 不停网关 | A |
| bench.sh | 本机预热 + 测量，Linux 自动启动采集；默认 20s+60s、2线程、64连接 | 单机实验；当前不支持 B 远程发压 |
| collect.sh | `bash scripts/collect.sh 180`；采集当前本机网关，不负责发压 | A |
| monitor-up.sh | 提示密码并 compose up -d | B，需要可用的 Docker |
| monitor-down.sh | compose down，默认保留数据卷 | B |
| run-observed.sh | 高级前台启动，接受 run-id 和额外 JVM 参数，不使用后台状态管理 | A；常规流程优先 start.sh |
| collect-linux.py | collect.sh 底层，/proc、pidstat、指标文本，可选 jcmd | A，仅 Linux |
| workflow.py | 启停、状态保护、压测及 Compose 的 Python 调度实现 | 由入口调用 |
| common.sh | 统一目录和加载配置，不单独执行 | 内部文件 |
| gateway.conf | 共享默认配置 | 配置文件 |
| gateway.local.conf | 个人覆盖，不同步、不提交 Git | 按机器自行维护 |

common.sh 先加载 gateway.conf，再加载 gateway.local.conf；后者的显式赋值可覆盖命令环境变量。参数不生效时先检查个人覆盖文件。
前台 run-observed.sh 不加载 common.sh，也不写 active.json；手动前台启动的服务不会自动纳入 stop.sh 管理，不要与后台方式混用。
若 shell 会话异常退出导致 results/.lifecycle.lock 或 .benchmark.lock 残留，先检查确无相关操作后再移除空锁目录，不能在操作仍执行时删锁。

## 13. 结果目录、采集是否结束及 JFR

A 每次 start 生成 `results/<UTC时间>-<transport>/`，常见文件：

```text
results/<本轮>/
  server.log                 服务日志
  gc.log*                    GC / safepoint 轮转日志
  atlas.jfr                  正常停止后写出的 JFR
  metadata.txt               Java、内核、启动条件等
  command.txt                实际启动命令
  gateway.pid                Java PID
  artifact-path.txt          被测 jar 路径
  source-pom.xml             本轮 pom 备份
  linux-<时间>/
    collector.json           采集参数
    collector.log            采集错误信息
    process.csv              Linux 进程指标
    pidstat.log              CPU、内存、I/O、线程等
    metrics/                 多个时刻的 Prometheus 文本快照
```

collect.sh 指定时长结束后会返回命令提示符；返回提示符也可能是失败，必须看退出码和日志。后续启动采集可这样同时保留退出码：

```bash
bash scripts/collect.sh 180
COLLECT_EXIT=$?
echo "collector exit=$COLLECT_EXIT"
```

A 上可查看最近的采集文件：

```bash
find results -name collector.log -type f -print
find results -name process.csv -type f -print
```

检查对应本轮 collector.log 是否有错误、process.csv 是否有持续采样行。采集器结束不会停止网关；停止网关也不要提前截断仍需要的采集区间。
默认采集不会执行高扰动的 jcmd 诊断。需要 NMT / 额外 JFR 快照时，先阅读 `docs/observability.md` 再手工启用。
JFR 默认开启、最近 30 分钟、最多 256m；正常 stop 后将 A 的 atlas.jfr 复制到 Mac，用 JDK Mission Control 打开。JFR、CSV 不会自动上传 Grafana。

B 的远程实验保存到 `results/remote-<时间>-<transport>/`，包含预热/测量日志、请求体和时间；两机结果需要按时间和 transport 一起归档。
Grafana 历史在 Docker 卷内，不在项目 results 目录；可以导出仪表盘 JSON、截图，记录测量时间和查询窗口。

## 14. 常见错误速查

| 报错 / 现象 | 处理 |
|---|---|
| rsync: command not found | `sudo apt-get install -y rsync` |
| cp .git/objects Permission denied | 改用本文排除 .git 的 rsync |
| report.lua 找不到 | `cd /home/levi/projects/atlas-gateway`，确认 benchmarks/report.lua 和 examples/batch.json 都已同步 |
| Java 版本不对 | 检查 JAVA_HOME、PATH、java/javac -version；必须 25 |
| curl 8080 连接失败 | A 检查 status、ss、本地 health、BIND_HOST、防火墙、IP |
| Docker socket permission denied | 加 docker 组后重新登录，检查 id -nG 和 docker ps |
| Docker 镜像拉取失败 | 检查 B 的网络、DNS、镜像仓库可达性；不是 Java 网关问题 |
| Grafana 登录失败 | 用户 admin，使用首次初始化密码或执行重置 |
| Targets DOWN | 检查 target 是 A 的地址；B 上 curl 通不代表容器一定能访问，查看 Targets 的具体抓取错误 |
| 图表 No data | 先确认 Targets UP、时间范围、实例选择、指标名称及是否已有足够抓取样本 |
| 颜色无法区分 | 新建对比查询并 Legend={{transport}}；单独改原始总览颜色不会生成 transport 系列 |
| 修改 prometheus.yml 没生效 | 重启 prometheus，检查挂载文件及容器日志 |
| 停止后 JFR 没生成 | 检查是否开启 JFR、是否正常 SIGTERM、server.log 是否有启动或落盘错误 |
| Native transport 启动失败 | 看本轮 server.log 的底层错误；不要将失败后的数据标记为该 transport 结果 |

## 15. 日常最短操作清单

1. A 启动本轮 transport，确认 runtime 指标与监听正确。
2. B 确认 Grafana/Prometheus running、Targets UP；等待至少一分钟。
3. A 在独立终端启动 collect.sh，时长覆盖预热和正式压测。
4. B 执行 20 秒预热和 60 秒测量，按本轮 transport 保存报告。
5. Mac 查看总览与蓝/橙对比曲线，记录正式测量时间段。
6. 检查客户端错误、A 采集日志、CPU/内存是否存在瓶颈。
7. 等采集完成，A stop 导出 JFR，再切换下一轮 transport。
8. 测试结束可在 B 执行 `bash scripts/monitor-down.sh`，保留卷；不要使用 down -v，除非明确要删除监控历史及 Grafana 配置。
