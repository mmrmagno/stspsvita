import hashlib
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
import zlib
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
PIPELINE = REPO / "pipeline"
sys.path.insert(0, str(PIPELINE))

from stsvita_common import ASSET_DIRS  # noqa: E402

MAIN = "com/megacrit/cardcrawl/desktop/DesktopLauncher.class"


def png(width, height):
    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))
    rows = b"".join(b"\0" + bytes((x * 3 + y * 5 + c * 40) & 255 if c < 3 else 255
                                  for x in range(width) for c in range(4))
                    for y in range(height))
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b""))


def make_jar(path, skip_dir=None, gdx=b"1.9.5", with_main=True):
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("META-INF/MANIFEST.MF",
                   "Main-Class: com.megacrit.cardcrawl.desktop.DesktopLauncher\n")
        if with_main:
            z.writestr(MAIN, b"fixture")
        z.writestr("com/badlogic/gdx/Version.class", b"\x01\x00" + bytes([len(gdx)]) + gdx)
        for d in sorted(ASSET_DIRS - {skip_dir}):
            z.writestr(f"{d}/fixture.txt", d)
        z.writestr("images/big.png", png(256, 256))
        z.writestr("images/small.png", png(16, 16))
        z.writestr("font/jpn/fixture.otf", b"font")
        z.writestr("font/fixture.otf", b"font")
        z.writestr("build.properties", "fixture=1\n")
        z.writestr("libfixture.so", b"native")
        z.writestr("linux/libfixture.so", b"native")
        z.writestr("random.txt", b"not an asset")


def run(script, *args):
    return subprocess.run([sys.executable, str(PIPELINE / script), *map(str, args)],
                          capture_output=True, text=True)


def tree(root):
    return {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(root.rglob("*")) if p.is_file()}


class PipelineTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.dir = Path(tmp.name)
        self.jar = self.dir / "desktop-1.0.jar"

    def test_unknown_jar_is_refused_unless_allowed(self):
        make_jar(self.jar)
        refused = run("verify.py", "--sts-dir", self.dir)
        self.assertEqual(refused.returncode, 1)
        self.assertIn("unsupported jar fingerprint", refused.stdout)
        allowed = run("verify.py", "--sts-dir", self.jar, "--allow-unknown")
        self.assertEqual(allowed.returncode, 0, allowed.stdout + allowed.stderr)

    def test_structural_problems_are_refused_even_when_allowed(self):
        for kwargs, text in (({"skip_dir": "cards"}, "cards"),
                             ({"gdx": b"1.8.0"}, "libGDX"),
                             ({"with_main": False}, "main class")):
            with self.subTest(text):
                make_jar(self.jar, **kwargs)
                r = run("verify.py", "--sts-dir", self.dir, "--allow-unknown")
                self.assertEqual(r.returncode, 1)
                self.assertIn(text, r.stdout)

    def test_missing_or_broken_jar_is_a_readable_error(self):
        r = run("verify.py", "--sts-dir", self.dir)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("not found", r.stderr)
        self.jar.write_bytes(b"not a zip")
        r = run("extract.py", "--sts-dir", self.dir, "--work", self.dir / "work")
        self.assertNotEqual(r.returncode, 0)
        self.assertNotIn("Traceback", r.stderr)

    def test_extract_takes_assets_only(self):
        make_jar(self.jar)
        work = self.dir / "work"
        self.assertEqual(run("extract.py", "--sts-dir", self.dir, "--work", work).returncode, 0)
        files = set(tree(work / "assets_raw"))
        self.assertIn("images/big.png", files)
        self.assertIn("build.properties", files)
        for name in ("random.txt", "libfixture.so", "linux/libfixture.so", MAIN,
                     "META-INF/MANIFEST.MF"):
            self.assertNotIn(name, files)

    @unittest.skipUnless(shutil.which("magick") and shutil.which("gcc"),
                         "needs ImageMagick 7 and gcc")
    def test_assets_are_converted_and_reproducible(self):
        make_jar(self.jar)
        work = self.dir / "work"
        run("extract.py", "--sts-dir", self.dir, "--work", work)
        trees = []
        for _ in range(2):
            r = run("assets.py", "--work", work, "--audio-kbps", 0)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            trees.append(tree(work / "assets_vita"))
        self.assertEqual(trees[0], trees[1])
        files = set(trees[0])
        self.assertIn("images/big.dds", files)
        self.assertNotIn("images/big.png", files)
        self.assertIn("images/small.png", files)
        self.assertIn("font/fixture.otf", files)
        self.assertNotIn("font/jpn/fixture.otf", files)


if __name__ == "__main__":
    unittest.main()
