import hashlib
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SRC = REPO / "launcher/src"

DRIVER = r"""
#include <stdio.h>
#include <string.h>
#include "manifest.h"
#include "sha256.h"
static char buf[1 << 20];
int main(int argc, char **argv) {
    size_t n = fread(buf, 1, sizeof(buf), stdin);
    if (strcmp(argv[1], "sha") == 0) {
        stsv_sha256 s; char hex[65];
        size_t step = argc > 2 ? (size_t) atoi(argv[2]) : n + 1;
        stsv_sha256_init(&s);
        for (size_t i = 0; i < n; i += step)
            stsv_sha256_update(&s, buf + i, n - i < step ? n - i : step);
        stsv_sha256_hex(&s, hex);
        puts(hex);
    } else {
        stsv_manifest m;
        int r = stsv_manifest_parse(buf, n, &m);
        printf("%d %ld %s\n", r, m.game_size, m.build_id);
    }
    return 0;
}
"""

GOOD = {
    "format": "1", "build_id": "abc-1", "game_size": "8020113",
    "game_sha256": "a" * 64, "assets_id": "0" * 64,
}


def text(**changes):
    fields = {**GOOD, **changes}
    return "".join(f"{k}={v}\n" for k, v in fields.items() if v is not None)


@unittest.skipUnless(shutil.which("cc"), "needs a host C compiler")
class LauncherCore(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        driver = Path(cls.tmp.name) / "driver.c"
        driver.write_text("#include <stdlib.h>\n" + DRIVER)
        cls.exe = Path(cls.tmp.name) / "driver"
        subprocess.run(["cc", "-std=gnu11", "-Wall", "-Wextra", "-Werror",
                        "-fsanitize=address,undefined", f"-I{SRC}", str(driver),
                        str(SRC / "manifest.c"), str(SRC / "sha256.c"),
                        "-o", str(cls.exe)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def run_driver(self, data, *args):
        res = subprocess.run([str(self.exe), *args], input=data,
                             capture_output=True, check=True)
        return res.stdout.decode().strip()

    def parse(self, body):
        return self.run_driver(body.encode(), "manifest").split(" ", 2)

    def test_sha256_matches_hashlib(self):
        for size in (0, 1, 55, 56, 63, 64, 65, 1000, 70000):
            data = bytes(i * 7 % 251 for i in range(size))
            for step in ("1", "63", "4096"):
                self.assertEqual(self.run_driver(data, "sha", step),
                                 hashlib.sha256(data).hexdigest())

    def test_valid(self):
        self.assertEqual(self.parse(text()), ["0", "8020113", "abc-1"])

    def test_crlf_and_unknown_keys(self):
        body = text(extra="x").replace("\n", "\r\n")
        self.assertEqual(self.parse(body)[0], "0")

    def test_no_trailing_newline(self):
        self.assertEqual(self.parse(text().rstrip("\n"))[0], "0")

    def test_newer_format_is_distinct(self):
        self.assertEqual(self.parse(text(format="2"))[0], "2")

    def test_rejects(self):
        bad = [
            "", "garbage", "=\n=\n",
            text(format=None), text(format="0"), text(format="x"),
            text(game_size=None), text(game_size="0"), text(game_size="-5"),
            text(game_size="9" * 12), text(build_id=None), text(build_id="b" * 64),
            text(game_sha256="a" * 63), text(game_sha256="A" * 64),
            text(assets_id="z" * 64), text(assets_id=None),
        ]
        for body in bad:
            with self.subTest(body=body[:60]):
                self.assertEqual(self.parse(body)[0], "1")


if __name__ == "__main__":
    unittest.main()
