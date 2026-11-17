package dev.atlas.gateway.metrics;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * 收集多个 EventLoop 共享的进程级指标，并输出 Prometheus 文本快照。
 * 连接限额使用原子计数，累计指标使用 LongAdder 降低多线程更新竞争；快照不是原子事务。
 */
public final class GatewayMetrics {
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final LongAdder connections = new LongAdder();
    private final LongAdder rejectedConnections = new LongAdder();
    private final LongAdder requests = new LongAdder();
    private final LongAdder responses = new LongAdder();
    private final LongAdder errors = new LongAdder();
    private final LongAdder bodyBytes = new LongAdder();
    private final LongAdder acceptedBatches = new LongAdder();
    private final LongAdder validatedEvents = new LongAdder();
    private final LongAdder processingNanos = new LongAdder();
    private final LongAdder processedRequests = new LongAdder();

    /** 计入新连接并返回包含当前连接的总数，用于原子执行连接限额判断。 */
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
        bodyBytes.add(bytes);
    }

    /** 仅统计最终响应；100 Continue 等临时响应不代表一次请求已经完成。 */
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
        validatedEvents.add(count);
    }

    /** 只累计业务回调时间，不包含上传、请求聚合或响应在网络上的传输时间。 */
    public void requestProcessed(long nanos) {
        processedRequests.increment();
        processingNanos.add(nanos);
    }

    /** 返回并发近似快照；累计时间和次数可计算平均值，不能据此推导 P99。 */
    public String scrape() {
        return metric("connections_active", "gauge", activeConnections.get())
                + metric("connections_total", "counter", connections.sum())
                + metric("connections_rejected_total", "counter", rejectedConnections.sum())
                + metric("http_requests_total", "counter", requests.sum())
                + metric("http_responses_total", "counter", responses.sum())
                + metric("http_errors_total", "counter", errors.sum())
                + metric("http_body_bytes_total", "counter", bodyBytes.sum())
                + metric("batches_accepted_total", "counter", acceptedBatches.sum())
                + metric("events_validated_total", "counter", validatedEvents.sum())
                + metric("handler_duration_seconds_sum", "counter", processingNanos.sum() / 1_000_000_000.0)
                + metric("handler_duration_seconds_count", "counter", processedRequests.sum());
    }

    private static String metric(String name, String type, Number value) {
        return "# TYPE atlas_" + name + " " + type + "\natlas_" + name + " " + value + "\n";
    }
}
