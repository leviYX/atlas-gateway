package dev.atlas.gateway.config;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证启动配置默认值与非法参数的拒绝行为。
 */
class GatewayConfigTest {
    @Test void defaultsToPortableLocalServer() {
        GatewayConfig config = GatewayConfig.from(new Properties());
        assertEquals("nio", config.transport());
        assertEquals("127.0.0.1", config.host());
        assertEquals("validate", config.mode());
        assertEquals(8080, config.port());
    }
    @Test void rejectsInvalidOptions() {
        for (String[] option : new String[][] {
                {"gateway.transport", "wrong"}, {"gateway.mode", "wrong"},
                {"gateway.port", "65536"}, {"gateway.workers", "0"},
                {"gateway.maxBodyBytes", "-1"}, {"gateway.maxEvents", "0"},
                {"gateway.idleSeconds", "0"}}) {
            Properties properties = new Properties();
            properties.setProperty(option[0], option[1]);
            assertThrows(IllegalArgumentException.class, () -> GatewayConfig.from(properties), option[0]);
        }
    }
}
