package dev.atlas.gateway.server;

import dev.atlas.gateway.config.GatewayConfig;

import io.netty.channel.epoll.Epoll;
import io.netty.channel.uring.IoUring;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 通过真实 HTTP 连接验证服务生命周期、上报接口、大小限制和 transport 选择。
 */
class GatewayServerTest {
    // 独立的协议样例，避免集成测试依赖另一个测试类的包可见常量。
    private static final String VALID = """
            {"batchId":"b1","source":"vehicle","schemaVersion":1,"events":[
              {"eventId":"e1","eventType":"map_search","eventTime":1731800000000}]}
            """;
    private GatewayConfig config(String transport, String mode) {
        Properties properties = new Properties();
        properties.setProperty("gateway.port", "0");
        properties.setProperty("gateway.workers", "1");
        properties.setProperty("gateway.maxBodyBytes", "1024");
        properties.setProperty("gateway.transport", transport);
        properties.setProperty("gateway.mode", mode);
        return GatewayConfig.from(properties);
    }

    @ParameterizedTest
    @ValueSource(strings = {"nio", "epoll", "io_uring"})
    void servesRealHttpAndRejectsOversizedBodies(String transport) throws Exception {
        assumeTrue(!transport.equals("epoll") || Epoll.isAvailable(), "epoll not available on this host");
        assumeTrue(!transport.equals("io_uring") || IoUring.isAvailable(), "io_uring not available on this host");
        try (GatewayServer server = new GatewayServer(config(transport, "validate")).start();
             HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                     .connectTimeout(Duration.ofSeconds(3)).build()) {
            String base = "http://127.0.0.1:" + server.port();
            assertEquals(200, send(client, base + "/health", null).statusCode());
            assertEquals(204, send(client, base + "/v1/events/batch", VALID).statusCode());
            assertEquals(400, send(client, base + "/v1/events/batch", "{").statusCode());
            assertEquals(413, send(client, base + "/v1/events/batch", "x".repeat(2048)).statusCode());
            HttpResponse<String> metrics = send(client, base + "/metrics", null);
            assertEquals(200, metrics.statusCode());
            assertTrue(metrics.body().contains("atlas_events_validated_total 1"));
            assertTrue(metrics.body().contains("atlas_http_errors_total 2"));
            assertTrue(metrics.body().contains("atlas_eventloop_pending_tasks"));
            assertTrue(metrics.body().contains("jvm_memory_used_bytes"));
        }
    }

    @Test void discardModeAcceptsOpaqueBodyWithoutCountingEvents() throws Exception {
        try (GatewayServer server = new GatewayServer(config("nio", "discard")).start();
             HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            String base = "http://127.0.0.1:" + server.port();
            assertEquals(204, send(client, base + "/v1/events/batch", "opaque").statusCode());
            String metrics = send(client, base + "/metrics", null).body();
            assertTrue(metrics.contains("atlas_events_validated_total 0"));
            assertTrue(metrics.contains("atlas_batches_accepted_total 1"));
        }
    }

    @Test void explicitNativeTransportNeverFallsBack() {
        if (!IoUring.isAvailable()) {
            assertThrows(IllegalStateException.class, () -> new GatewayServer(config("io_uring", "discard")).start());
        }
        if (!Epoll.isAvailable()) {
            assertThrows(IllegalStateException.class, () -> new GatewayServer(config("epoll", "discard")).start());
        }
    }

    @Test void serverCannotStartTwiceOrAfterClose() throws Exception {
        try (GatewayServer server = new GatewayServer(config("nio", "discard")).start()) {
            assertThrows(IllegalStateException.class, server::start);
        }
        GatewayServer closed = new GatewayServer(config("nio", "discard"));
        closed.close();
        assertThrows(IllegalStateException.class, closed::start);
    }

    private HttpResponse<String> send(HttpClient client, String uri, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(5));
        if (body != null) {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
