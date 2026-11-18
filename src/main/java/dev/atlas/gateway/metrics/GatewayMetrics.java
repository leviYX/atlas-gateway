package dev.atlas.gateway.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufAllocatorMetric;
import io.netty.buffer.ByteBufAllocatorMetricProvider;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一注册业务、JVM、默认 Netty 分配器与 Linux 进程内存指标，供 Prometheus 抓取。
 * 每个服务实例使用独立 registry；所有标签均有界，不使用设备 ID、URL 或搜索词作标签。
 */
public final class GatewayMetrics implements AutoCloseable {
    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final JvmGcMetrics gc = new JvmGcMetrics();
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final AtomicInteger activeHandlers = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Counter connections = counter("atlas.connections");
    private final Counter rejectedConnections = counter("atlas.connections.rejected");
    private final Counter requests = counter("atlas.http.requests");
    private final Counter responses = counter("atlas.http.responses");
    private final Counter errors = counter("atlas.http.errors");
    private final Counter bodyBytes = counter("atlas.http.body.bytes");
    private final Counter acceptedBatches = counter("atlas.batches.accepted");
    private final Counter validatedEvents = counter("atlas.events.validated");
    private final Timer processing = Timer.builder("atlas.handler.duration")
            .description("业务回调耗时，不含上传、聚合和网络传输")
            .publishPercentileHistogram()
            .minimumExpectedValue(Duration.ofNanos(1000))
            .maximumExpectedValue(Duration.ofSeconds(10))
            .register(registry);
    private ScheduledExecutorService sampler;

    public GatewayMetrics() {
        this("unknown", "unknown");
    }

    public GatewayMetrics(String transport, String mode) {
        Gauge.builder("atlas.connections.active", activeConnections, AtomicInteger::get).register(registry);
        Gauge.builder("atlas.handlers.active", activeHandlers, AtomicInteger::get).register(registry);
        Gauge.builder("atlas.runtime.info", () -> 1).tags("transport", transport, "mode", mode).register(registry);
        new ClassLoaderMetrics().bindTo(registry);
        new JvmMemoryMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);
        new UptimeMetrics().bindTo(registry);
        new FileDescriptorMetrics().bindTo(registry);
        gc.bindTo(registry);
        if (ByteBufAllocator.DEFAULT instanceof ByteBufAllocatorMetricProvider provider) {
            ByteBufAllocatorMetric allocator = provider.metric();
            // 观察 Channel 实际使用的默认分配器，不能假设 Netty 4.2 一定使用 pooled allocator。
            Gauge.builder("atlas.netty.allocator.direct.memory.bytes", allocator,
                    ByteBufAllocatorMetric::usedDirectMemory).register(registry);
            Gauge.builder("atlas.netty.allocator.heap.memory.bytes", allocator,
                    ByteBufAllocatorMetric::usedHeapMemory).register(registry);
        }
        if (System.getProperty("os.name").startsWith("Linux")) {
            LinuxProcessMemory memory = new LinuxProcessMemory(Path.of("/proc/self/status"));
            memory.sample();
            Gauge.builder("atlas.process.resident.memory.bytes", memory, LinuxProcessMemory::residentBytes)
                    .strongReference(true).register(registry);
            Gauge.builder("atlas.process.virtual.memory.bytes", memory, LinuxProcessMemory::virtualBytes)
                    .strongReference(true).register(registry);
            // 文件读取在独立守护线程上每秒执行，指标抓取只读取缓存，不让 EventLoop 做 proc 文件 I/O。
            sampler = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "atlas-process-sampler");
                thread.setDaemon(true);
                return thread;
            });
            sampler.scheduleAtFixedRate(memory::sample, 1, 1, TimeUnit.SECONDS);
        }
    }

    /** 绑定每条事件循环的待执行任务数；这是任务队列，不是内核 SQ/CQ 的长度。 */
    public void bindEventLoops(EventLoopGroup group, String role) {
        int index = 0;
        for (EventExecutor executor : group) {
            if (executor instanceof SingleThreadEventExecutor single) {
                Gauge.builder("atlas.eventloop.pending.tasks", single, SingleThreadEventExecutor::pendingTasks)
                        .tags("role", role, "index", Integer.toString(index))
                        .register(registry);
            }
            index++;
        }
    }

    private Counter counter(String name) {
        return registry.counter(name);
    }

    /** 活动连接数由原子变量维护，用于连接上限判断。 */
    public int connectionOpened() {
        connections.increment();
        return activeConnections.incrementAndGet();
    }

    public void connectionClosed() {
        activeConnections.decrementAndGet();
    }
    public void connectionRejected() {
        rejectedConnections.increment();
    }
    public void requestReceived() {
        requests.increment();
    }
    public void bodyReceived(int bytes) {
        bodyBytes.increment(bytes);
    }

    /** 记录进入出站链的最终响应；并不表示对端已经收到全部字节。 */
    public void responseWritten(int status) {
        if (status >= 200) {
            responses.increment();
            if (status >= 400) {
                errors.increment();
            }
        }
    }

    public void batchAccepted() {
        acceptedBatches.increment();
    }
    public void eventsValidated(int count) {
        validatedEvents.increment(count);
    }
    public void handlerStarted() {
        activeHandlers.incrementAndGet();
    }

    /** 对应 handlerStarted，在 finally 中调用，保证异常路径也减少活动数并计时。 */
    public void requestProcessed(long nanos) {
        activeHandlers.decrementAndGet();
        processing.record(nanos, TimeUnit.NANOSECONDS);
    }

    /** 输出 Prometheus 格式，桶可用于聚合分位数；不能当作客户端端到端延迟。 */
    public String scrape() {
        return registry.scrape();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            if (sampler != null) {
                sampler.shutdownNow();
            }
            gc.close();
            registry.close();
        }
    }
}
