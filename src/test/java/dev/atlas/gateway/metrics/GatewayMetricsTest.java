package dev.atlas.gateway.metrics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 验证指标端点同时提供业务、JVM、Netty 内存以及可聚合的耗时直方图。 */
class GatewayMetricsTest {
    @Test void exportsRuntimeAndBusinessMetrics() {
        try (GatewayMetrics metrics = new GatewayMetrics()) {
            metrics.requestReceived();
            metrics.handlerStarted();
            metrics.requestProcessed(2_000_000);
            metrics.eventsValidated(3);
            String scrape = metrics.scrape();
            assertTrue(scrape.contains("jvm_memory_used_bytes"));
            assertTrue(scrape.contains("jvm_threads_live_threads"));
            assertTrue(scrape.contains("process_uptime_seconds"));
            assertTrue(scrape.contains("atlas_netty_allocator_direct_memory_bytes"));
            assertTrue(scrape.contains("atlas_events_validated_total 3.0"));
            assertTrue(scrape.contains("atlas_handler_duration_seconds_bucket"));
            assertTrue(scrape.contains("atlas_handler_duration_seconds_count 1"));
        }
    }

    @Test void recordsConcurrentHandlersAndReleasesOnCompletion() {
        try (GatewayMetrics metrics = new GatewayMetrics()) {
            metrics.handlerStarted();
            assertTrue(metrics.scrape().contains("atlas_handlers_active 1.0"));
            metrics.requestProcessed(1_000_000);
            assertTrue(metrics.scrape().contains("atlas_handlers_active 0.0"));
        }
    }
}
