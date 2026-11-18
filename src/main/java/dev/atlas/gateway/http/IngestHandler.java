package dev.atlas.gateway.http;

import dev.atlas.gateway.metrics.GatewayMetrics;
import dev.atlas.gateway.validation.EventBatchValidator;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 处理上报、探活和指标 HTTP 请求，按模式校验或直接丢弃上报数据。
 * 每条连接创建独立实例；完整请求在回调结束后由父类自动释放，不跨回调持有请求体。
 */
public final class IngestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final String mode;
    private final EventBatchValidator validator;
    private final GatewayMetrics metrics;

    public IngestHandler(String mode, int maxEvents, GatewayMetrics metrics) {
        this.mode = mode;
        this.validator = new EventBatchValidator(maxEvents);
        this.metrics = metrics;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        metrics.handlerStarted();
        long start = System.nanoTime();
        try {
            if (!request.decoderResult().isSuccess()) {
                respond(ctx, request, HttpResponseStatus.BAD_REQUEST, "invalid HTTP request\n", false, null);
                return;
            }
            String path = new QueryStringDecoder(request.uri()).path();
            boolean ingest = "/v1/events/batch".equals(path);
            boolean health = "/health".equals(path);
            boolean monitoring = "/metrics".equals(path);
            if (!ingest && !health && !monitoring) {
                respond(ctx, request, HttpResponseStatus.NOT_FOUND, "not found\n", true, null);
                return;
            }
            HttpMethod expected = ingest ? HttpMethod.POST : HttpMethod.GET;
            if (!expected.equals(request.method())) {
                respond(ctx, request, HttpResponseStatus.METHOD_NOT_ALLOWED, "method not allowed\n", true, expected.name());
                return;
            }
            if (health || monitoring) {
                respond(ctx, request, HttpResponseStatus.OK, health ? "ok\n" : metrics.scrape(), true, null);
                return;
            }
            // discard 跳过 JSON 解析；validate 必须整批校验成功后才累计事件数。
            if ("validate".equals(mode)) {
                String type = request.headers().get(HttpHeaderNames.CONTENT_TYPE, "");
                if (!"application/json".equalsIgnoreCase(type.split(";", 2)[0].trim())) {
                    respond(ctx, request, HttpResponseStatus.UNSUPPORTED_MEDIA_TYPE,
                            "Content-Type must be application/json\n", true, null);
                    return;
                }
                int events;
                try {
                    events = validator.validate(request.content());
                } catch (IOException | IllegalArgumentException invalid) {
                    respond(ctx, request, HttpResponseStatus.BAD_REQUEST, "invalid event batch\n", true, null);
                    return;
                }
                metrics.eventsValidated(events);
            }
            // 当前没有持久化链路：204 只表示接收流程完成，数据会随请求释放而丢弃。
            metrics.batchAccepted();
            respond(ctx, request, HttpResponseStatus.NO_CONTENT, "", true, null);
        } finally {
            metrics.requestProcessed(System.nanoTime() - start);
        }
    }

    private static void respond(ChannelHandlerContext ctx, FullHttpRequest request, HttpResponseStatus status,
                                String body, boolean reusable, String allow) {
        FullHttpResponse response = new DefaultFullHttpResponse(request.protocolVersion(), status,
                HttpMethod.HEAD.equals(request.method()) ? Unpooled.EMPTY_BUFFER
                        : Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        // 204 响应没有消息体，也不设置 Content-Length。
        if (!status.equals(HttpResponseStatus.NO_CONTENT)) {
            response.headers().set(HttpHeaderNames.CONTENT_TYPE,
                    request.uri().equals("/metrics") ? "text/plain; version=0.0.4; charset=utf-8" : "text/plain; charset=utf-8");
            HttpUtil.setContentLength(response, response.content().readableBytes());
        }
        if (allow != null) {
            response.headers().set(HttpHeaderNames.ALLOW, allow);
        }
        // 完整消费请求后才复用连接；无法安全继续解码时，发送响应后关闭。
        boolean keepAlive = reusable && HttpUtil.isKeepAlive(request);
        HttpUtil.setKeepAlive(response, keepAlive);
        if (keepAlive) {
            ctx.writeAndFlush(response);
        } else {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        HttpResponseStatus status = cause instanceof TooLongFrameException
                ? HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE : HttpResponseStatus.BAD_REQUEST;
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
        HttpUtil.setContentLength(response, 0);
        HttpUtil.setKeepAlive(response, false);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
