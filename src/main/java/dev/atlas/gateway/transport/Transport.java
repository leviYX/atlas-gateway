package dev.atlas.gateway.transport;

import dev.atlas.gateway.config.GatewayConfig;

import io.netty.channel.IoHandlerFactory;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.uring.IoUring;
import io.netty.channel.uring.IoUringIoHandler;
import io.netty.channel.uring.IoUringIoHandlerConfig;
import io.netty.channel.uring.IoUringServerSocketChannel;

/**
 * 将网络实现对应的事件循环工厂与服务端 Channel 类型配套选择。
 * 显式指定的原生实现不可用时直接失败，避免压测过程中静默切换实现。
 */
public record Transport(IoHandlerFactory factory, Class<? extends ServerChannel> channelType) {
    /** 事件循环与 Channel 必须来自同一 transport，不能单独替换其中一项。 */
    public static Transport select(GatewayConfig config) {
        return switch (config.transport()) {
            case "nio" -> new Transport(NioIoHandler.newFactory(), NioServerSocketChannel.class);
            case "epoll" -> {
                if (!Epoll.isAvailable()) {
                    throw new IllegalStateException("epoll unavailable: check Linux native dependency/profile",
                            Epoll.unavailabilityCause());
                }
                yield new Transport(EpollIoHandler.newFactory(), EpollServerSocketChannel.class);
            }
            case "io_uring" -> {
                if (!IoUring.isAvailable()) {
                    throw new IllegalStateException("io_uring unavailable: check Linux kernel/native dependency/profile",
                            IoUring.unavailabilityCause());
                }
                // ringSize 控制提交队列容量，实际批量提交时机由 Netty 的事件循环决定。
                IoUringIoHandlerConfig ringConfig = new IoUringIoHandlerConfig().setRingSize(config.ringSize());
                yield new Transport(IoUringIoHandler.newFactory(ringConfig), IoUringServerSocketChannel.class);
            }
            default -> throw new IllegalArgumentException("Unknown transport: " + config.transport());
        };
    }
}
