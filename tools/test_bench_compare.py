import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import bench_compare  # noqa: E402

BEFORE = "noise\n[t] [bench] scen=idle frames=10 fps=30.0 ms_p95=33.4 draws=400.5 short=2\n"
AFTER = "[bench] scen=idle frames=10 fps=60.0 ms_p95=16.7 draws=200.0 short=0\n[bench] scen=new fps=1\n"


class BenchCompareTest(unittest.TestCase):
    def test_parse_and_table(self):
        with tempfile.TemporaryDirectory() as d:
            b, a = Path(d) / "b.log", Path(d) / "a.log"
            b.write_text(BEFORE)
            a.write_text(AFTER)
            before, after = bench_compare.parse(b), bench_compare.parse(a)
        self.assertEqual(before["idle"]["draws"], 400.5)
        rows = "\n".join(bench_compare.table(before, after))
        self.assertIn("30 -> 60 (+100%) ok", rows)
        self.assertIn("33.4 -> 16.7 (-50%) ok", rows)
        self.assertIn("new | missing in before", rows)
        self.assertNotIn("WORSE", rows)


if __name__ == "__main__":
    unittest.main()
