"""验证 proc 解析的字段位置与单位，避免把 RSS/CPU 数值读错。"""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("collector", Path(__file__).parents[1] / "collect-linux.py")
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)


class ProcParsingTest(unittest.TestCase):
    """使用包含空格、括号的进程名验证 stat 解析。"""
    def test_stat_fields_ignore_process_name_spaces(self):
        fields = ["S"] + ["0"] * 21
        fields[11], fields[12], fields[19] = "17", "9", "12345"
        result = collector.parse_stat("42 (java (worker)) " + " ".join(fields))
        self.assertEqual({"cpu_ticks": 26, "start_ticks": 12345}, result)

    def test_renamed_jar_identifies_exact_target(self):
        self.assertTrue(collector.matches_gateway(
            ["/jdk/bin/java", "-Xmx1g", "-jar", "/app/custom.jar"], "/app/custom.jar"))
        self.assertFalse(collector.matches_gateway(
            ["java", "-jar", "/app/other.jar"], "/app/custom.jar"))
        self.assertFalse(collector.matches_gateway(
            ["java", "-Dname=/app/custom.jar", "OtherMain"], "/app/custom.jar"))

    def test_status_kib_and_switches(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "status"
            path.write_text("VmRSS:\t2048 kB\nThreads:\t8\nvoluntary_ctxt_switches:\t19\n")
            result = collector.read_status(path)
            self.assertEqual(2097152, result["VmRSS"])
            self.assertEqual(8, result["Threads"])
            self.assertEqual(19, result["voluntary_ctxt_switches"])


if __name__ == "__main__":
    unittest.main()
