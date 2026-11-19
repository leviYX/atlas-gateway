"""验证工作流的进程保护和压测记录，避免脚本误停进程或丢失失败记录。"""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("workflow", Path(__file__).resolve().parents[1] / "workflow.py")
workflow = importlib.util.module_from_spec(spec)
spec.loader.exec_module(workflow)


class WorkflowTest(unittest.TestCase):
    """覆盖 PID 复用防护、参数校验及压测失败后的结果保存。"""

    def test_reused_pid_is_never_signalled(self):
        state = {"pid": 42, "identity": "old", "run_id": "previous"}
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(workflow, 'RESULTS', Path(directory)), \
                 patch.object(workflow, 'load_state', return_value=state), \
                 patch.object(workflow, 'identity', return_value="new"), \
                 patch.object(workflow.os, 'kill') as kill:
                workflow.stop()
                kill.assert_not_called()

    def test_health_address_matches_bind_host(self):
        for host, expected in [('0.0.0.0', '127.0.0.1'), ('192.168.1.2', '192.168.1.2'), ('::', '[::1]')]:
            with patch.dict(os.environ, {'BIND_HOST': host}):
                self.assertEqual(workflow.local_base(8080), 'http://' + expected + ':8080')

    def test_positive_rejects_invalid_load(self):
        with patch.dict(os.environ, {'BENCH_THREADS': '0'}):
            with self.assertRaises(ValueError):
                workflow.positive('BENCH_THREADS', '2')

    def test_benchmark_failure_retains_record(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'examples').mkdir()
            (root / 'examples/batch.json').write_text('{}')
            (root / 'run').mkdir()
            state = {'run_id': 'run', 'base_url': 'http://127.0.0.1:8080'}
            with patch.object(workflow, 'ROOT', root), \
                 patch.object(workflow, 'RESULTS', root), \
                 patch.object(workflow, 'load_state', return_value=state), \
                 patch.object(workflow, 'health', return_value=True), \
                 patch.object(workflow.platform, 'system', return_value='Darwin'), \
                 patch('shutil.which', return_value='/usr/bin/wrk'), \
                 patch.object(workflow.subprocess, 'run', side_effect=subprocess.CalledProcessError(1, 'wrk')), \
                 patch.dict(os.environ, {'BENCH_WARMUP_SECONDS': '0', 'BENCH_DURATION_SECONDS': '1', 'BENCH_URL': state['base_url']}):
                with self.assertRaises(subprocess.CalledProcessError):
                    workflow.bench()
            records = list((root / 'run').glob('bench-*/load.json'))
            self.assertEqual(len(records), 1)
            self.assertIn('ended_utc', json.loads(records[0].read_text()))
            self.assertFalse((root / '.benchmark.lock').exists())


if __name__ == '__main__':
    unittest.main()
