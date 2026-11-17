package dev.atlas.gateway.http;

import dev.atlas.gateway.metrics.GatewayMetrics;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.timeout.IdleStateEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证连接限额、空闲关闭，以及超大请求的计数和缓冲区释放。
 */
class ConnectionMetricsHandlerTest {
    @Test void capsConnectionsAndReleasesCapacityOnClose() {
        GatewayMetrics metrics = new GatewayMetrics();
        EmbeddedChannel first = new EmbeddedChannel(new ConnectionMetricsHandler(metrics, 1));
        EmbeddedChannel second = new EmbeddedChannel(new ConnectionMetricsHandler(metrics, 1));
        try {
            assertTrue(first.isActive());
            assertFalse(second.isActive());
            assertTrue(metrics.scrape().contains("atlas_connections_active 1\n"));
            assertTrue(metrics.scrape().contains("atlas_connections_rejected_total 1\n"));
            first.close();
            assertTrue(metrics.scrape().contains("atlas_connections_active 0\n"));
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    @Test void closesIdleConnections() {
        GatewayMetrics metrics = new GatewayMetrics();
        EmbeddedChannel channel = new EmbeddedChannel(new ConnectionMetricsHandler(metrics, 1));
        try {
            channel.pipeline().fireUserEventTriggered(IdleStateEvent.ALL_IDLE_STATE_EVENT);
            assertFalse(channel.isActive());
            assertTrue(metrics.scrape().contains("atlas_connections_active 0\n"));
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void releasesBodyAndCountsRejectedRequest() {
        GatewayMetrics metrics = new GatewayMetrics();
        EmbeddedChannel channel = new EmbeddedChannel(new ConnectionMetricsHandler(metrics, 1),
                new HttpObjectAggregator(4, true), new IngestHandler("discard", 1, metrics));
        DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST,
                "/v1/events/batch");
        DefaultLastHttpContent body = new DefaultLastHttpContent(Unpooled.buffer().writeZero(8));
        request.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 8);
        try {
            channel.writeInbound(request);
            channel.writeInbound(body);
            FullHttpResponse response = channel.readOutbound();
            try { assertEquals(413, response.status().code()); } finally { response.release(); }
            assertEquals(0, body.refCnt());
            assertTrue(metrics.scrape().contains("atlas_http_requests_total 1\n"));
            assertTrue(metrics.scrape().contains("atlas_http_errors_total 1\n"));
            assertTrue(metrics.scrape().contains("atlas_batches_accepted_total 0\n"));
        } finally { channel.finishAndReleaseAll(); }
    }
}
