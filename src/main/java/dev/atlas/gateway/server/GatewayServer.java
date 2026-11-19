package dev.atlas.gateway.server;

import dev.atlas.gateway.config.GatewayConfig;
import dev.atlas.gateway.http.ConnectionMetricsHandler;
import dev.atlas.gateway.http.IngestHandler;
import dev.atlas.gateway.metrics.GatewayMetrics;
import dev.atlas.gateway.transport.Transport;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 管理 HTTP 网关生命周期，组装连接处理链并持有监听端口、客户端连接和事件循环。
 * 实例仅允许启动一次；关闭时先停止接入，再释放连接和线程资源。
 */
public final class GatewayServer implements AutoCloseable {
    private final GatewayConfig config;
    private final GatewayMetrics metrics;
    private final ChannelGroup clients = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private EventLoopGroup boss;
    private EventLoopGroup workers;
    private Channel server;

    public GatewayServer(GatewayConfig config) {
        this.config = config;
        this.metrics = new GatewayMetrics(config.transport(), config.mode());
    }

    public GatewayServer start() throws InterruptedException {
        // 防止再次启动覆盖旧资源引用；实例关闭后也不能重新使用。
        if (closed.get() || !started.compareAndSet(false, true)) {
            throw new IllegalStateException("GatewayServer is single-use and cannot be restarted");
        }
        try {
            Transport transport = Transport.select(config);
            boss = new MultiThreadIoEventLoopGroup(1, transport.factory());
            workers = new MultiThreadIoEventLoopGroup(config.workers(), transport.factory());
            metrics.bindEventLoops(boss, "boss");
            metrics.bindEventLoops(workers, "worker");
            server = new ServerBootstrap()
                    .group(boss, workers)
                    .channel(transport.channelType())
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            clients.add(channel);
                            // 入站按添加顺序执行、出站反向执行。指标位于聚合器之前，才能看到其 413 响应。
                            channel.pipeline()
                                    .addLast(new IdleStateHandler(0, 0, config.idleSeconds()))
                                    .addLast(new HttpServerCodec())
                                    .addLast(new ConnectionMetricsHandler(metrics, config.maxConnections()))
                                    .addLast(new HttpObjectAggregator(config.maxBodyBytes(), true))
                                    .addLast(new IngestHandler(config.mode(), config.maxEvents(), metrics));
                        }
                    })
                    .bind(config.host(), config.port())
                    .sync()
                    .channel();
            return this;
        } catch (InterruptedException | RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    public int port() {
        return ((InetSocketAddress) server.localAddress()).getPort();
    }

    public void await() throws InterruptedException {
        server.closeFuture().sync();
    }

    @Override
    public void close() {
        // 关闭钩子和主线程都可能请求关闭，通过 CAS 保证只释放一次。
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // 先停止接受新连接，再关闭现有连接，最后退出事件循环。
        if (server != null) {
            server.close().awaitUninterruptibly();
        }
        clients.close().awaitUninterruptibly();
        if (workers != null) {
            workers.shutdownGracefully(0, 5, TimeUnit.SECONDS).awaitUninterruptibly();
        }
        if (boss != null) {
            boss.shutdownGracefully(0, 5, TimeUnit.SECONDS).awaitUninterruptibly();
        }
        metrics.close();
    }
}
