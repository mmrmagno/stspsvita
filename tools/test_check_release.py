import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import check_release as cr  # noqa: E402


class CheckRelease(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)

    def vpk(self, extra=(), eboot=b"x", art=None):
        path = self.root / "t.vpk"
        with zipfile.ZipFile(path, "w") as z:
            for name in (*cr.VPK_FILES, *extra):
                data = eboot if name == "eboot.bin" else b"x"
                if (cr.ART / name).is_file():
                    data = art or (cr.ART / name).read_bytes()
                z.writestr(name, data)
        return cr.check_vpk(path)

    def test_clean_vpk(self):
        self.assertEqual(self.vpk(), [])

    def test_extra_file(self):
        self.assertTrue(self.vpk(extra=["assets/a.dds"]))
        self.assertTrue(self.vpk(extra=["game.bin"]))

    def test_art_other_than_the_repo_art(self):
        self.assertEqual(len(self.vpk(art=b"title.jpg")), 4)

    def test_game_sized_eboot(self):
        self.assertTrue(self.vpk(eboot=b"x" * (8 << 20)))

    def test_game_code_in_elf(self):
        elf = self.root / "e"
        elf.write_bytes(b"\0com/MegaCrit/CardCrawl\0")
        self.assertEqual(len(cr.check_elf(elf)), 2)
        elf.write_bytes(b"not affiliated with Mega Crit")
        self.assertEqual(cr.check_elf(elf), [])

    def test_tag(self):
        cmake = 'set(STSV_VERSION "01.02")'
        self.assertEqual(cr.check_tag("v1.0.2", cmake), [])
        for tag in ("v1.0.0", "1.0.2", "v1.02", "v1.0.20"):
            self.assertEqual(len(cr.check_tag(tag, cmake)), 1)
        self.assertEqual(len(cr.check_tag("v1.0.2", "")), 1)

    def test_tree(self):
        (self.root / "a.jar").write_bytes(b"")
        (self.root / "b.md").write_text("ftp://192.168." + "0.5")
        (self.root / "c.md").write_text("ftp://192.168.1.42 /home/you/x")
        self.assertEqual(len(cr.check_tree(self.root, ["a.jar", "b.md", "c.md"])), 2)


if __name__ == "__main__":
    unittest.main()
