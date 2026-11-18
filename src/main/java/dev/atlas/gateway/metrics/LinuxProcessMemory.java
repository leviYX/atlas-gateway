package dev.atlas.gateway.metrics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 按周期读取 Linux proc status，提供进程 RSS 与虚拟地址空间大小，非 Linux 不参与采样。 */
final class LinuxProcessMemory {
    private final Path status;
    private volatile double residentBytes = Double.NaN;
    private volatile double virtualBytes = Double.NaN;

    LinuxProcessMemory(Path status) {
        this.status = status;
    }

    void sample() {
        double resident = Double.NaN;
        double virtual = Double.NaN;
        try {
            for (String line : Files.readAllLines(status)) {
                if (line.startsWith("VmRSS:")) {
                    resident = bytes(line);
                } else if (line.startsWith("VmSize:")) {
                    virtual = bytes(line);
                }
            }
        } catch (IOException | SecurityException | NumberFormatException unavailable) {
            // 采集失败不能中断网关；缺失值使用 NaN，避免沿用旧值或伪装为 0。
        }
        residentBytes = resident;
        virtualBytes = virtual;
    }

    private static double bytes(String line) {
        // /proc 中的 kB 实际为 1024 字节，不是十进制 1000 字节。
        return Double.parseDouble(line.split(":", 2)[1].trim().split("\\s+")[0]) * 1024;
    }

    double residentBytes() { return residentBytes; }
    double virtualBytes() { return virtualBytes; }
}
