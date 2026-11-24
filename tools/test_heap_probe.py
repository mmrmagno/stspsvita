import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
GLUE = REPO / "native/src/glue"

DRIVER = r"""
#include <stdio.h>
#include "heap_probe.h"
void *__real_malloc(size_t n);
void *__wrap_malloc(size_t n) { return n > 1000000 ? NULL : __real_malloc(n); }
int main(int argc, char **argv) {
    (void) argv;
    size_t unit = (size_t) argc;
    printf("%d %d\n", stsv_heap_can_alloc(unit * 1000), stsv_heap_can_alloc(unit * 50000000));
    return 0;
}
"""


@unittest.skipUnless(shutil.which("cc"), "needs a host C compiler")
class HeapProbeTest(unittest.TestCase):
    def test_failed_allocation_is_reported_when_optimized(self):
        for level in ("-O0", "-O2", "-O3"):
            with self.subTest(level), tempfile.TemporaryDirectory() as tmp:
                src = Path(tmp) / "driver.c"
                src.write_text(DRIVER)
                exe = Path(tmp) / "driver"
                subprocess.run(["cc", level, f"-I{GLUE}", str(src), "-Wl,--wrap=malloc",
                                "-o", str(exe)], check=True)
                out = subprocess.run([str(exe)], capture_output=True, text=True, check=True)
                self.assertEqual(out.stdout.split(), ["1", "0"])

    def test_no_bare_malloc_probe_left(self):
        for name in ("gl_common_glue.c", "misc_glue.c"):
            self.assertNotRegex((GLUE / name).read_text(), r"probe = malloc\(|\*m = malloc\(t\)")


if __name__ == "__main__":
    unittest.main()
