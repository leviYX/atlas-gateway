#!/usr/bin/env python3
"""采集一轮 Linux 网关的 RSS、CPU、上下文切换、proc 快照和可选 JFR/NMT 诊断。"""
import argparse
import csv
import datetime
import json
import os
from pathlib import Path
import platform
import shutil
import signal
import subprocess
import time
import urllib.request


def parse_stat(line):
    """comm 字段可含空格或括号，必须从最后的右括号之后解析字段号。"""
    fields = line[line.rfind(")") + 2:].split()
    return {"cpu_ticks": int(fields[11]) + int(fields[12]), "start_ticks": int(fields[19])}


def read_status(path):
    result = {}
    for line in path.read_text().splitlines():
        key, _, value = line.partition(":")
        if key in ("VmRSS", "VmSize", "VmHWM", "RssAnon", "RssFile", "RssShmem"):
            result[key] = int(value.split()[0]) * 1024
        elif key in ("Threads", "voluntary_ctxt_switches", "nonvoluntary_ctxt_switches"):
            result[key] = int(value.strip())
    return result


def matches_gateway(command, artifact):
    """匹配实际 -jar 参数，兼容自定义文件名，避免同名字符串误匹配其他 Java 进程。"""
    try:
        return command[command.index("-jar") + 1] == artifact
    except (ValueError, IndexError):
        return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run_dir", type=Path)
    parser.add_argument("--duration", type=int, default=300, help="采样秒数，默认 300")
    parser.add_argument("--interval", type=int, default=1, help="采样间隔秒数")
    parser.add_argument("--metrics-url", default="http://127.0.0.1:8080/metrics")
    parser.add_argument("--jcmd", action="store_true", help="额外执行 JFR 快照和 NMT baseline/diff，会扰动测试")
    args = parser.parse_args()
    if platform.system() != "Linux":
        parser.error("此采集器需要 Linux /proc；Mac 可运行网关和 JFR，Linux 采集在虚拟机内执行")
    if args.duration <= 0 or args.interval <= 0:
        parser.error("duration 和 interval 必须为正数")
    run = args.run_dir.resolve()
    pid = int((run / "gateway.pid").read_text().strip())
    proc = Path("/proc") / str(pid)
    initial = parse_stat((proc / "stat").read_text())
    artifact = (run / "artifact-path.txt").read_text().strip()
    command_line = (proc / "cmdline").read_bytes().decode(errors="replace").split("\0")
    if not matches_gateway(command_line, artifact):
        parser.error("PID 不属于 atlas-gateway，请检查 gateway.pid，避免采集无关进程")
    output = run / ("linux-" + datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ"))
    output.mkdir()
    (output / "metrics").mkdir()
    errors = (output / "collector.log").open("w", buffering=1)
    stopped = False

    def stop(signum, frame):
        nonlocal stopped
        stopped = True

    signal.signal(signal.SIGINT, stop)
    signal.signal(signal.SIGTERM, stop)

    def record_error(message):
        errors.write(str(message) + "\n")

    def command(name, command_args):
        executable = shutil.which(command_args[0])
        if not executable:
            record_error("工具不存在：" + command_args[0])
            return
        try:
            result = subprocess.run([executable] + command_args[1:], stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, timeout=15, check=False)
            (output / name).write_bytes(result.stdout)
            if result.returncode:
                record_error("命令失败：" + " ".join(command_args))
        except (OSError, subprocess.TimeoutExpired) as error:
            record_error(error)

    def same_process():
        try:
            return parse_stat((proc / "stat").read_text())["start_ticks"] == initial["start_ticks"]
        except (OSError, ValueError, IndexError):
            return False

    def snapshot(stage):
        for name in ("status", "smaps_rollup", "limits", "io"):
            try:
                (output / (stage + "-" + name + ".txt")).write_text((proc / name).read_text())
            except OSError as error:
                record_error(error)
        # 下列网络统计属于整台主机，不能误认为单个 Java 进程的网络流量。
        for name in ("net/dev", "net/snmp", "net/netstat", "meminfo", "stat"):
            try:
                (output / (stage + "-host-" + name.replace("/", "-") + ".txt")).write_text((Path("/proc") / name).read_text())
            except OSError as error:
                record_error(error)
        command(stage + "-ss.txt", ["ss", "-s"])

    (output / "collector.json").write_text(json.dumps({
        "pid": pid, "process_start_ticks": initial["start_ticks"], "duration": args.duration,
        "interval": args.interval, "metrics_url": args.metrics_url,
        "jcmd_enabled": args.jcmd, "kernel": platform.release(),
        "cpu_count": os.cpu_count(), "clock_ticks_per_second": os.sysconf("SC_CLK_TCK")
    }, indent=2))
    snapshot("start")
    # JAVA_HOME 优先，避免系统 jcmd 与被测 JDK 不匹配。
    if os.environ.get("JAVA_HOME"):
        os.environ["PATH"] = str(Path(os.environ["JAVA_HOME"]) / "bin") + os.pathsep + os.environ["PATH"]
    if args.jcmd and same_process():
        command("jvm-flags.txt", ["jcmd", str(pid), "VM.flags"])
        command("nmt-baseline.txt", ["jcmd", str(pid), "VM.native_memory", "baseline"])
    child = None
    pidstat_log = (output / "pidstat.log").open("w")
    if shutil.which("pidstat"):
        child = subprocess.Popen(["pidstat", "-u", "-r", "-d", "-w", "-t", "-p", str(pid), str(args.interval)],
                                 stdout=pidstat_log, stderr=subprocess.STDOUT)
    else:
        record_error("未安装 pidstat：sudo apt install sysstat；仍会采集 proc CSV")
    started = time.monotonic()
    previous_time = started
    previous_ticks = parse_stat((proc / "stat").read_text())["cpu_ticks"]
    ticks_per_second = os.sysconf("SC_CLK_TCK")
    next_scrape = started
    try:
        with (output / "process.csv").open("w", newline="") as stream:
            writer = csv.writer(stream)
            writer.writerow(["utc", "elapsed_seconds", "cpu_percent_one_core", "rss_bytes", "virtual_bytes",
                             "rss_peak_bytes", "rss_anon_bytes", "rss_file_bytes", "threads", "open_fds",
                             "main_thread_voluntary_switches", "main_thread_involuntary_switches"])
            while not stopped and same_process() and time.monotonic() - started < args.duration:
                now = time.monotonic()
                try:
                    status = read_status(proc / "status")
                    stat = parse_stat((proc / "stat").read_text())
                    cpu = ((stat["cpu_ticks"] - previous_ticks) / ticks_per_second / (now - previous_time) * 100
                           if now - previous_time >= 0.05 else None)
                    fds = len(list((proc / "fd").iterdir()))
                    writer.writerow([datetime.datetime.now(datetime.timezone.utc).isoformat(), round(now-started, 3),
                                     round(cpu, 3) if cpu is not None else "", status.get("VmRSS"), status.get("VmSize"), status.get("VmHWM"),
                                     status.get("RssAnon"), status.get("RssFile"), status.get("Threads"), fds,
                                     status.get("voluntary_ctxt_switches"), status.get("nonvoluntary_ctxt_switches")])
                    stream.flush()
                    previous_time, previous_ticks = now, stat["cpu_ticks"]
                except (OSError, ValueError, IndexError) as error:
                    record_error(error)
                    break
                if now >= next_scrape:
                    try:
                        with urllib.request.urlopen(args.metrics_url, timeout=2) as response:
                            (output / "metrics" / ("%08d.prom" % int(now-started))).write_bytes(response.read())
                    except OSError as error:
                        record_error(error)
                    next_scrape = now + 5
                time.sleep(args.interval)
    finally:
        if child:
            child.terminate()
            try:
                child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait()
        pidstat_log.close()
        if same_process():
            snapshot("end")
            if args.jcmd:
                command("nmt-diff.txt", ["jcmd", str(pid), "VM.native_memory", "summary.diff", "scale=MB"])
                command("jfr-dump.txt", ["jcmd", str(pid), "JFR.dump", "name=atlas", "filename=" + str(output / "snapshot.jfr")])
        else:
            record_error("目标已退出或 PID 被复用，跳过末尾诊断；正常退出的 JFR 在 run_dir/atlas.jfr")
        errors.close()
        print("采集结果：" + str(output))


if __name__ == "__main__":
    main()
