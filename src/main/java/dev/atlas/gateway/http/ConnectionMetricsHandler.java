package dev.atlas.gateway.http;

import dev.atlas.gateway.metrics.GatewayMetrics;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.timeout.IdleStateEvent;

/**
 * 统计连接和 HTTP 流量，执行连接数限制，并在连接空闲时关闭连接。
 * 位于 HTTP 解码器之后、请求聚合器之前，因此也能统计聚合阶段拒绝的请求和响应。
 */
public final class ConnectionMetricsHandler extends ChannelDuplexHandler {
    private final GatewayMetrics metrics;
    private final int maxConnections;

    public ConnectionMetricsHandler(GatewayMetrics metrics, int maxConnections) {
        this.metrics = metrics;
        this.maxConnections = maxConnections;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        if (metrics.connectionOpened() > maxConnections) {
            metrics.connectionRejected();
            // 超限连接也计入活动数，关闭后统一由 channelInactive 扣减，避免重复释放配额。
            ctx.close();
            return;
        }
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        metrics.connectionClosed();
        super.channelInactive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest) {
            metrics.requestReceived();
        }
        if (msg instanceof HttpContent content) {
            metrics.bodyReceived(content.content().readableBytes());
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof HttpResponse response && response.status().code() >= 200) {
            metrics.responseWritten(response.status().code());
        }
        super.write(ctx, msg, promise);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
        if (event instanceof IdleStateEvent) {
            ctx.close();
        } else {
            super.userEventTriggered(ctx, event);
        }
    }
}
