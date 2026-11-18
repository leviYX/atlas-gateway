package dev.atlas.gateway.metrics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 使用可控的 proc status 样例验证 Linux RSS 单位转换与缺失值处理。 */
class LinuxProcessMemoryTest {
    @TempDir Path directory;

    @Test void readsResidentAndVirtualBytes() throws Exception {
        Path status = directory.resolve("status");
        Files.writeString(status, "Name:\tjava\nVmSize:\t8192 kB\nVmRSS:\t2048 kB\n");
        LinuxProcessMemory memory = new LinuxProcessMemory(status);
        memory.sample();
        assertEquals(2_097_152, memory.residentBytes());
        assertEquals(8_388_608, memory.virtualBytes());
        Files.writeString(status, "Name:\tjava\n");
        memory.sample();
        assertTrue(Double.isNaN(memory.residentBytes()));
    }

    @Test void unavailableProcDoesNotPreventGatewayStartup() {
        LinuxProcessMemory memory = new LinuxProcessMemory(directory.resolve("missing"));
        assertDoesNotThrow(memory::sample);
        assertTrue(Double.isNaN(memory.residentBytes()));
    }
}
