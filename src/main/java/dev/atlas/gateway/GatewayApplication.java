package dev.atlas.gateway;

import dev.atlas.gateway.config.GatewayConfig;
import dev.atlas.gateway.server.GatewayServer;

/**
 * 网关进程入口：加载配置、启动服务，并通过 JVM 关闭钩子释放网络资源。
 */
public final class GatewayApplication {
    private GatewayApplication() { }

    public static void main(String[] args) throws InterruptedException {
        GatewayConfig config = GatewayConfig.from(System.getProperties());
        try (GatewayServer server = new GatewayServer(config).start()) {
            Thread hook = new Thread(server::close, "atlas-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            System.out.printf("Atlas Gateway listening on %s:%d transport=%s mode=%s workers=%d%n",
                    config.host(), server.port(), config.transport(), config.mode(), config.workers());
            try {
                server.await();
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException shuttingDown) {
                    // JVM 已进入关闭流程，由已注册的钩子负责关闭服务。
                }
            }
        }
    }
}
