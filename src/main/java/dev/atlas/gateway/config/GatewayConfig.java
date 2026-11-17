package dev.atlas.gateway.config;

import java.util.Properties;
import java.util.Set;

/**
 * 网关的不可变启动配置，从 gateway.* 系统属性读取参数并检查取值范围。
 */
public record GatewayConfig(String host, int port, String transport, String mode, int workers,
                            int maxBodyBytes, int maxEvents, int idleSeconds, int maxConnections,
                            int ringSize) {
    /** 读取系统属性并应用默认值，在占用端口和创建线程之前拒绝非法配置。 */
    public static GatewayConfig from(Properties properties) {
        String transport = properties.getProperty("gateway.transport", "nio");
        String mode = properties.getProperty("gateway.mode", "validate");
        if (!Set.of("nio", "epoll", "io_uring").contains(transport)) {
            throw new IllegalArgumentException("gateway.transport must be nio, epoll or io_uring");
        }
        if (!Set.of("discard", "validate").contains(mode)) {
            throw new IllegalArgumentException("gateway.mode must be discard or validate");
        }
        String host = properties.getProperty("gateway.host", "127.0.0.1");
        if (host.isBlank()) {
            throw new IllegalArgumentException("gateway.host must not be blank");
        }
        return new GatewayConfig(host, integer(properties, "port", 8080, 0, 65535), transport, mode,
                integer(properties, "workers", Math.max(1, Runtime.getRuntime().availableProcessors()), 1, 1024),
                integer(properties, "maxBodyBytes", 1024 * 1024, 1, 64 * 1024 * 1024),
                integer(properties, "maxEvents", 1000, 1, 100_000),
                integer(properties, "idleSeconds", 60, 1, 86400),
                integer(properties, "maxConnections", 10_000, 1, Integer.MAX_VALUE),
                integer(properties, "ringSize", 4096, 1, 32768));
    }

    private static int integer(Properties properties, String name, int fallback, int min, int max) {
        String key = "gateway." + name;
        try {
            int value = Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)));
            if (value < min || value > max) {
                throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
            }
            return value;
        } catch (NumberFormatException cause) {
            throw new IllegalArgumentException(key + " must be an integer", cause);
        }
    }
}
