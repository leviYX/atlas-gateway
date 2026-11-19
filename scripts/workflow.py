#!/usr/bin/env python3
"""封装网关构建、后台启停、压测和监控，让每轮实验自动关联到结果目录。"""
import argparse
import contextlib
import datetime
import json
import os
from pathlib import Path
import platform
import signal
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
RESULTS = ROOT / "results"
STATE = RESULTS / "active.json"


def positive(name, default, allow_zero=False):
    value = int(os.environ.get(name, default))
    if value < (0 if allow_zero else 1):
        raise ValueError(name + " 必须为正整数" + ("或 0" if allow_zero else ""))
    return value


def identity(pid):
    """使用启动时刻防止旧 PID 误伤新进程；不将仅有 kill(0) 成功视为身份相同。"""
    try:
        if platform.system() == "Linux":
            fields = (Path('/proc') / str(pid) / 'stat').read_text().rsplit(')', 1)[1].split()
            if fields[0] == 'Z':
                return None
            return fields[19]
        result = subprocess.run(['ps', '-p', str(pid), '-o', 'lstart='],
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        return result.stdout.strip() or None
    except (OSError, IndexError):
        return None


def running(state):
    return identity(state['pid']) == state['identity'] and state['identity'] is not None


def load_state(require_running=True):
    if not STATE.exists():
        raise ValueError('没有脚本管理的运行记录，请先执行 bash scripts/start.sh')
    state = json.loads(STATE.read_text())
    if require_running and not running(state):
        raise ValueError('记录中的网关已停止，请执行 start.sh 启动新一轮')
    return state


@contextlib.contextmanager
def lock(name):
    RESULTS.mkdir(exist_ok=True)
    path = RESULTS / ('.' + name + '.lock')
    try:
        path.mkdir()
    except FileExistsError:
        raise ValueError('已有同类操作正在执行：' + str(path) + '；若先前异常中断，请确认后移除空锁目录')
    try:
        yield
    finally:
        path.rmdir()


def health(base):
    try:
        with urllib.request.urlopen(base + '/health', timeout=1) as response:
            return response.status == 200 and response.read() == b'ok\n'
    except OSError:
        return False


def local_base(port):
    """将通配监听地址映射到回环地址，其余地址保持一致以便探活。"""
    host = os.environ.get('BIND_HOST', '0.0.0.0')
    host = {'0.0.0.0': '127.0.0.1', '::': '::1'}.get(host, host)
    if ':' in host and not host.startswith('['):
        host = '[' + host + ']'
    return 'http://' + host + ':' + str(port)


def start(transport=None):
    with lock('lifecycle'):
        if STATE.exists() and running(load_state(False)):
            raise ValueError('网关已经在运行；请先 stop.sh，避免覆盖本轮实验')
        if not (ROOT / 'target/atlas-gateway.jar').exists():
            raise ValueError('尚未构建，请先执行 bash scripts/build.sh')
        port = positive('PORT', '8080')
        base = local_base(port)
        if health(base):
            raise ValueError('目标端口已有网关响应，请先停止原来手工启动的服务')
        env = dict(os.environ)
        env['TRANSPORT'] = transport or env.get('TRANSPORT', 'io_uring')
        if env['TRANSPORT'] not in ('nio', 'epoll', 'io_uring'):
            raise ValueError('transport 必须为 nio、epoll 或 io_uring')
        run_id = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ') + '-' + env['TRANSPORT']
        run = RESULTS / run_id
        with (RESULTS / (run_id + '-launcher.log')).open('w') as log:
            process = subprocess.Popen(['bash', str(ROOT / 'scripts/run-observed.sh'), run_id], cwd=str(ROOT),
                                       env=env, stdin=subprocess.DEVNULL, stdout=log,
                                       stderr=subprocess.STDOUT, start_new_session=True)
        token = identity(process.pid)
        state = {'pid': process.pid, 'identity': token, 'run_id': run_id,
                 'transport': env['TRANSPORT'], 'mode': env.get('MODE', 'validate'), 'base_url': base}
        # 就绪前也保存身份，用户中断等待后仍可通过 stop.sh 管理后台进程。
        temporary = STATE.with_suffix('.tmp')
        temporary.write_text(json.dumps(state, indent=2))
        temporary.replace(STATE)
        for attempt in range(60):
            if process.poll() is not None:
                details = (RESULTS / (run_id + '-launcher.log')).read_text()
                if (run / 'server.log').exists():
                    details += (run / 'server.log').read_text()[-6000:]
                raise ValueError('启动失败：\n' + details)
            if health(base):
                # launcher 中 exec 替换进程但保留 PID 和启动身份。
                if (run / 'gateway.pid').exists() and int((run / 'gateway.pid').read_text()) == process.pid:
                    temporary = STATE.with_suffix('.tmp')
                    temporary.write_text(json.dumps(state, indent=2))
                    temporary.replace(STATE)
                    print('启动成功：' + state['transport'] + ' / ' + state['mode'])
                    print('PID：' + str(process.pid) + '\n结果目录：' + str(run))
                    print('日志：bash scripts/logs.sh\n状态：bash scripts/status.sh\n压测：bash scripts/bench.sh')
                    return
            time.sleep(.5)
        process.terminate()
        try:
            process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            # 不使用 SIGKILL，避免 JFR 丢失。留下状态便于用户诊断和重试停止。
            STATE.write_text(json.dumps(state))
        raise ValueError('30 秒内未就绪，已发送 SIGTERM；查看 ' + str(run / 'server.log'))


def stop():
    with lock('lifecycle'):
        state = load_state(False)
        if not running(state):
            print('网关已停止；结果保留在 ' + str(RESULTS / state['run_id']))
            return
        os.kill(state['pid'], signal.SIGTERM)
        for attempt in range(120):
            if not running(state):
                print('已停止，诊断文件：' + str(RESULTS / state['run_id']))
                return
            time.sleep(.5)
        raise ValueError('60 秒内尚未退出，未强制 kill；请检查日志后重试')


def collect(seconds=None):
    state = load_state()
    duration = seconds if seconds is not None else positive('BENCH_DURATION_SECONDS', '60')
    command = [sys.executable, str(ROOT / 'scripts/collect-linux.py'),
               str(RESULTS / state['run_id']), '--duration', str(duration),
               '--metrics-url', state['base_url'] + '/metrics']
    return subprocess.call(command, cwd=str(ROOT))


def bench():
    with lock('benchmark'):
        state = load_state()
        base = os.environ.get('BENCH_URL', state['base_url']).rstrip('/')
        if base != state['base_url']:
            raise ValueError('自动关联采集只支持当前本机网关；远程压测请直接使用 wrk 并保存到独立目录')
        if not health(base):
            raise ValueError('网关健康检查失败')
        threads = positive('BENCH_THREADS', '2')
        connections = positive('BENCH_CONNECTIONS', '64')
        warmup = positive('BENCH_WARMUP_SECONDS', '20', True)
        duration = positive('BENCH_DURATION_SECONDS', '60')
        if connections < threads:
            raise ValueError('BENCH_CONNECTIONS 必须大于等于 BENCH_THREADS')
        import shutil
        if not shutil.which('wrk'):
            raise ValueError('缺少 wrk，请先执行 bash scripts/setup.sh')
        output = RESULTS / state['run_id'] / ('bench-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ'))
        output.mkdir()
        metadata = {'started_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'url': base,
                    'threads': threads, 'connections': connections, 'warmup_seconds': warmup,
                    'duration_seconds': duration, 'load_model': 'closed-loop',
                    'same_host_load_generator': True, 'run_id': state['run_id']}
        payload = (ROOT / 'examples/batch.json').read_bytes()
        import hashlib
        metadata['payload_sha256'] = hashlib.sha256(payload).hexdigest()
        (output / 'batch.json').write_bytes(payload)
        (output / 'load.json').write_text(json.dumps(metadata, indent=2))
        sampler = None
        if platform.system() == 'Linux':
            stream = (output / 'collector-launch.log').open('w')
            sampler = subprocess.Popen([sys.executable, str(ROOT / 'scripts/collect-linux.py'),
                str(RESULTS / state['run_id']), '--duration', str(warmup + duration + 10),
                '--metrics-url', base + '/metrics'], stdout=stream, stderr=subprocess.STDOUT)
            stream.close()
        else:
            print('非 Linux：跳过 proc 采集，JFR、GC 和 JVM 指标仍可用')
        command = ['wrk', '-t' + str(threads), '-c' + str(connections), '--latency',
                   '-s', 'benchmarks/report.lua']
        try:
            for name, seconds in [('warmup', warmup), ('measure', duration)]:
                if not seconds:
                    continue
                print(name + '：' + str(seconds) + ' 秒；输出 ' + str(output / (name + '.log')), flush=True)
                metadata[name + '_started_utc'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
                (output / 'load.json').write_text(json.dumps(metadata, indent=2))
                with (output / (name + '.log')).open('w') as log:
                    subprocess.run(command + ['-d' + str(seconds) + 's', base + '/v1/events/batch'],
                                   cwd=str(ROOT), stdout=log, stderr=subprocess.STDOUT, check=True)
                if not running(state):
                    raise ValueError('压测中网关退出，请查看 server.log')
            print((output / 'measure.log').read_text())
            print('结果目录：' + str(output))
            print('请检查 wrk 的 HTTP/Socket errors；wrk 退出码为 0 不代表所有请求成功。')
        finally:
            metadata['ended_utc'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
            (output / 'load.json').write_text(json.dumps(metadata, indent=2))
            if sampler:
                sampler.terminate()
                try:
                    sampler.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    print('采集器仍在完成快照，请查看 collector-launch.log', file=sys.stderr)
                if sampler.poll() not in (0, None):
                    print('采集器未正常完成，请查看 collector-launch.log', file=sys.stderr)


def monitor(action):
    env = dict(os.environ)
    if not env.get('GRAFANA_ADMIN_PASSWORD'):
        if action == 'up':
            import getpass
            env['GRAFANA_ADMIN_PASSWORD'] = getpass.getpass('设置 Grafana admin 密码（不回显，每次启动请使用相同密码）：')
            if not env['GRAFANA_ADMIN_PASSWORD']:
                raise ValueError('密码不能为空')
        else:
            # down 只需通过 compose 配置插值，不修改持久化卷中的密码。
            env['GRAFANA_ADMIN_PASSWORD'] = 'unused-for-down'
    subprocess.run(['docker', 'compose', '-f', str(ROOT / 'observability/compose.yaml'),
                    action] + (['-d'] if action == 'up' else []), env=env, check=True)
    if action == 'up':
        print('Grafana：http://localhost:3000 （admin）\nPrometheus：http://localhost:9090/targets')
        print('若 Docker 在另一台机器，请修改 prometheus.yml 为网关内网地址；先确认 Targets UP。')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['start', 'stop', 'status', 'logs', 'bench', 'collect', 'monitor-up', 'monitor-down'])
    parser.add_argument('value', nargs='?')
    args = parser.parse_args()
    try:
        if args.action == 'start': start(args.value)
        elif args.action == 'stop': stop()
        elif args.action == 'bench': bench()
        elif args.action == 'collect': return collect(int(args.value) if args.value else None)
        elif args.action.startswith('monitor-'): monitor(args.action.split('-')[1])
        elif args.action == 'logs':
            state = load_state(False)
            return subprocess.call(['tail', '-n', '50', '-f', str(RESULTS / state['run_id'] / 'server.log')])
        else:
            state = load_state(False)
            print(('运行中' if running(state) else '已停止') + '：' + json.dumps(state, ensure_ascii=False))
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print('错误：' + str(error), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print('\n操作已中断；后台网关不会停止，停止请执行 stop.sh', file=sys.stderr)
        return 130
    return 0


if __name__ == '__main__':
    sys.exit(main())
