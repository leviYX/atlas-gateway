package dev.atlas.gateway.http;

import dev.atlas.gateway.metrics.GatewayMetrics;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 通过内嵌 Channel 验证 HTTP 路由、批次校验和 Keep-Alive 行为。
 */
class IngestHandlerTest {
    static final String VALID = """
            {"batchId":"b1","source":"vehicle","schemaVersion":1,"events":[
              {"eventId":"e1","eventType":"map_search","eventTime":1731800000000,
               "attributes":{"keyword":"停车场"}}]}
            """;

    private static FullHttpResponse request(EmbeddedChannel channel, HttpMethod method,
                                           String path, String body, String contentType) {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, path,
                Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        request.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, request.content().readableBytes());
        if (contentType != null) {
            request.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        }
        channel.writeInbound(request);
        return channel.readOutbound();
    }

    private void assertStatus(String mode, String body, String contentType, HttpResponseStatus expected) {
        EmbeddedChannel channel = new EmbeddedChannel(new IngestHandler(mode, 2, new GatewayMetrics()));
        try {
            FullHttpResponse response = request(channel, HttpMethod.POST, "/v1/events/batch", body, contentType);
            try { assertEquals(expected, response.status()); } finally { response.release(); }
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void acceptsValidBatchAndReusesConnection() {
        GatewayMetrics metrics = new GatewayMetrics();
        EmbeddedChannel channel = new EmbeddedChannel(new IngestHandler("validate", 2, metrics));
        try {
            for (int i = 0; i < 2; i++) {
                FullHttpResponse response = request(channel, HttpMethod.POST, "/v1/events/batch", VALID, "application/json");
                try {
                    assertEquals(HttpResponseStatus.NO_CONTENT, response.status());
                    assertEquals(0, response.content().readableBytes());
                    assertTrue(channel.isActive());
                } finally { response.release(); }
            }
            assertTrue(metrics.scrape().contains("atlas_events_validated_total 2"));
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void rejectsMalformedJson() { assertStatus("validate", "{", "application/json", HttpResponseStatus.BAD_REQUEST); }
    @Test void rejectsTrailingJson() { assertStatus("validate", VALID + "{}", "application/json", HttpResponseStatus.BAD_REQUEST); }
    @Test void rejectsUnknownEventType() { assertStatus("validate", VALID.replace("map_search", "unknown"), "application/json", HttpResponseStatus.BAD_REQUEST); }
    @Test void rejectsMissingFields() { assertStatus("validate", "{}", "application/json", HttpResponseStatus.BAD_REQUEST); }
    @Test void rejectsUnknownSource() { assertStatus("validate", VALID.replace("vehicle", "unknown"), "application/json", HttpResponseStatus.BAD_REQUEST); }
    @Test void rejectsUnsupportedSchema() { assertStatus("validate", VALID.replace("\"schemaVersion\":1", "\"schemaVersion\":2"), "application/json", HttpResponseStatus.BAD_REQUEST); }
    @Test void rejectsEmptyBatch() { assertStatus("validate", "{\"batchId\":\"b\",\"source\":\"app\",\"schemaVersion\":1,\"events\":[]}", "application/json", HttpResponseStatus.BAD_REQUEST); }
    @Test void rejectsTooManyEvents() {
        String event = "{\"eventId\":\"e\",\"eventType\":\"map_click\",\"eventTime\":1}";
        String body = "{\"batchId\":\"b\",\"source\":\"app\",\"schemaVersion\":1,\"events\":[" + event + "," + event + "," + event + "]}";
        assertStatus("validate", body, "application/json", HttpResponseStatus.BAD_REQUEST);
    }
    @Test void rejectsWrongContentType() { assertStatus("validate", VALID, "text/plain", HttpResponseStatus.UNSUPPORTED_MEDIA_TYPE); }
    @Test void acceptsJsonWithCharset() { assertStatus("validate", VALID, "application/json; charset=utf-8", HttpResponseStatus.NO_CONTENT); }
    @Test void discardDoesNotParseJson() { assertStatus("discard", "not json", null, HttpResponseStatus.NO_CONTENT); }
    @Test void routesHealthMetricsAndErrors() {
        EmbeddedChannel channel = new EmbeddedChannel(new IngestHandler("validate", 2, new GatewayMetrics()));
        try {
            String[] paths = {"/health", "/metrics", "/missing", "/v1/events/batch"};
            int[] statuses = {200, 200, 404, 405};
            for (int i = 0; i < paths.length; i++) {
                FullHttpResponse response = request(channel, HttpMethod.GET, paths[i], "", null);
                try { assertEquals(statuses[i], response.status().code()); } finally { response.release(); }
            }
        } finally { channel.finishAndReleaseAll(); }
    }
}
