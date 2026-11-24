import contextlib
import ftplib
import io
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import deploy_assets as da  # noqa: E402
import stage_data as sd  # noqa: E402


class FakeFTP:
    def __init__(self):
        self.files, self.order, self.fail_on = {}, [], None

    def mkd(self, path):
        raise ftplib.error_perm("550 exists")

    def voidcmd(self, cmd):
        path = cmd.removeprefix("DELE ")
        if path not in self.files:
            raise ftplib.error_perm("550")
        del self.files[path]

    def size(self, path):
        if path not in self.files:
            raise ftplib.error_perm("550")
        return len(self.files[path])

    def retrbinary(self, cmd, cb):
        path = cmd.split(" ", 1)[1]
        if path not in self.files:
            raise ftplib.error_perm("550")
        cb(self.files[path])

    def storbinary(self, cmd, fh):
        path = cmd.split(" ", 1)[1]
        if self.fail_on and path.endswith(self.fail_on):
            raise ftplib.error_temp("426 dropped")
        self.files[path] = fh.read()
        self.order.append(path)


class Staged(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        (self.root / "assets/sub").mkdir(parents=True)
        (self.root / "assets/a.dds").write_bytes(b"aaaa")
        (self.root / "assets/sub/b.dds").write_bytes(b"bbbb")
        (self.root / "eboot.bin").write_bytes(b"GAME")
        (self.root / "loading.png").write_bytes(b"PNG")
        self.ftp = FakeFTP()

    def stage(self):
        return sd.stage(self.root / "eboot.bin", self.root / "assets",
                        self.root / "loading.png", self.root / "out",
                        "rev-1", "0" * 64)

    def deploy(self, data, **kw):
        with contextlib.redirect_stdout(io.StringIO()):
            return da.deploy(self.ftp, data, da.DEFAULT_DEST, **kw)

    def test_stage_layout(self):
        data = self.stage()
        names = sorted(p.relative_to(data).as_posix() for p in data.rglob("*") if p.is_file())
        self.assertEqual(names, ["assets.id", "assets/a.dds", "assets/sub/b.dds",
                                 "files.sha256", "game.bin", "game.manifest", "loading.png"])

    def test_stage_rejects_bad_ids(self):
        args = (self.root / "eboot.bin", self.root / "assets",
                self.root / "loading.png", self.root / "out")
        with self.assertRaises(ValueError):
            sd.stage(*args, "bad id\n", "0" * 64)
        with self.assertRaises(ValueError):
            sd.stage(*args, "ok", "short")

    def test_first_then_unchanged(self):
        self.assertEqual(self.deploy(self.stage()), (4, 0))
        self.assertEqual(self.deploy(self.stage()), (0, 4))

    def test_same_size_change_is_uploaded(self):
        self.deploy(self.stage())
        (self.root / "assets/a.dds").unlink()
        (self.root / "assets/a.dds").write_bytes(b"AAAA")
        self.assertEqual(self.deploy(self.stage()), (1, 3))
        self.assertEqual(self.ftp.files["ux0:/data/stsvita/assets/a.dds"], b"AAAA")

    def test_manifest_is_last(self):
        self.deploy(self.stage())
        self.assertEqual(self.ftp.order[-1], "ux0:/data/stsvita/game.manifest")

    def test_interrupted_upload_never_looks_complete(self):
        self.deploy(self.stage())
        (self.root / "assets/sub/b.dds").unlink()
        (self.root / "assets/sub/b.dds").write_bytes(b"BBBB")
        self.ftp.fail_on = "b.dds"
        with self.assertRaises(ftplib.error_temp):
            self.deploy(self.stage())
        self.assertNotIn("ux0:/data/stsvita/game.manifest", self.ftp.files)
        self.assertNotIn("ux0:/data/stsvita/assets.id", self.ftp.files)
        self.ftp.fail_on = None
        self.assertEqual(self.deploy(self.stage()), (1, 3))
        self.assertEqual(self.ftp.files["ux0:/data/stsvita/assets/sub/b.dds"], b"BBBB")

    def test_removed_asset_is_deleted(self):
        self.deploy(self.stage())
        (self.root / "assets/a.dds").unlink()
        self.deploy(self.stage())
        self.assertNotIn("ux0:/data/stsvita/assets/a.dds", self.ftp.files)

    def test_saves_are_never_touched(self):
        save = "ux0:/data/stsvita/saves/IRONCLAD.autosave"
        self.ftp.files[save] = b"run"
        self.deploy(self.stage())
        (self.root / "assets/a.dds").unlink()
        self.deploy(self.stage(), force=True)
        self.assertEqual(self.ftp.files[save], b"run")

    def test_index_rejects_traversal(self):
        self.assertEqual(da.parse_index("a" * 64 + "  ../x\n"), {})


if __name__ == "__main__":
    unittest.main()
