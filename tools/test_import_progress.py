#!/usr/bin/env python3
"""Tests for tools/import_progress.py.

Fixtures are synthetic: the save/preference names are the game's, but every
byte of content is invented here. No Slay the Spire data is used or shipped.

  python3 -m unittest discover -s tools -p 'test_*.py'
"""
from __future__ import annotations

import argparse
import ftplib
import io
import json
import os
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import import_progress as ip  # noqa: E402


# --------------------------------------------------------------------------
# fixtures
# --------------------------------------------------------------------------

def make_source(root: Path, slots=(0,), prefs_dir=ip.PREFS_DIR,
                marker="alpha", jar=True) -> Path:
    """A minimal but realistic desktop install tree."""
    root.mkdir(parents=True, exist_ok=True)
    if jar:
        (root / "desktop-1.0.jar").write_bytes(b"not a real jar")
    saves = root / ip.SAVES_DIR
    prefs = root / prefs_dir
    saves.mkdir(exist_ok=True)
    prefs.mkdir(exist_ok=True)
    for slot in slots:
        pre = "" if slot == 0 else f"{slot}_"
        (saves / f"{pre}IRONCLAD.autosave").write_text(
            json.dumps({"marker": marker, "slot": slot, "gold": 99}))
        (saves / f"{pre}IRONCLAD.autosave.backUp").write_text(
            json.dumps({"marker": marker, "slot": slot, "gold": 98}))
        (prefs / f"{pre}STSPlayer").write_text(
            json.dumps({"marker": marker, "NAME": "TESTER"}))
        (prefs / f"{pre}STSUnlocks").write_text(json.dumps({"marker": marker}))
    # Steam drops this next to progress; it is not progress.
    (saves / "steam_autocloud.vdf").write_text("vdf")
    (prefs / "steam_autocloud.vdf").write_text("vdf")
    return root


def make_args(**kw) -> argparse.Namespace:
    base = dict(source=None, auto=False, source_prefs_dir=None,
                dest_dir=None, ftp_host=None, ftp_port=ip.DEFAULT_FTP_PORT,
                ftp_base=ip.DEFAULT_FTP_BASE, apply=False, replace_slots=None,
                replace_all=False, backup_dir=None, rollback_from=None)
    base.update(kw)
    return argparse.Namespace(**base)


class Case(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="stsvita-import-test-"))
        self.addCleanup(lambda: shutil.rmtree(self.tmp, ignore_errors=True))
        self.out = io.StringIO()

    def run_import(self, **kw) -> tuple[int, str]:
        code = ip.run(make_args(**kw), out=self.out)
        return code, self.out.getvalue()


# --------------------------------------------------------------------------
# name classification and path safety
# --------------------------------------------------------------------------

class TestClassify(unittest.TestCase):
    def test_saves(self):
        self.assertEqual(ip.classify("saves", "IRONCLAD.autosave"), (0, "IRONCLAD"))
        self.assertEqual(ip.classify("saves", "2_THE_SILENT.autosave"), (2, "THE_SILENT"))
        self.assertEqual(ip.classify("saves", "1_WATCHER.autosave.backUp"), (1, "WATCHER"))

    def test_preferences(self):
        self.assertEqual(ip.classify("preferences", "STSPlayer"), (0, "STSPlayer"))
        self.assertEqual(ip.classify("preferences", "2_STSSeenCards.backUp"),
                         (2, "STSSeenCards"))
        # underscore in the stem must not be read as a slot prefix
        self.assertEqual(ip.classify("preferences", "STSInputSettings_Controller"),
                         (0, "STSInputSettings_Controller"))

    def test_rejects_non_progress(self):
        for kind, name in (("saves", "steam_autocloud.vdf"),
                           ("saves", "MODDED_HERO.autosave"),
                           ("saves", "IRONCLAD.autosave.tmp"),
                           ("preferences", "steam_autocloud.vdf"),
                           ("preferences", "STSNotAThing"),
                           # slot 0 never carries a literal "0_" prefix
                           ("preferences", "0_STSPlayer")):
            with self.subTest(name=name):
                self.assertIsNone(ip.classify(kind, name))


class TestSafeRel(unittest.TestCase):
    def test_accepts(self):
        self.assertEqual(ip.safe_rel("saves/IRONCLAD.autosave"), "saves/IRONCLAD.autosave")
        self.assertEqual(ip.safe_rel("preferences/STSPlayer"), "preferences/STSPlayer")

    def test_rejects(self):
        for bad in ("/etc/passwd", "../escape", "saves/../../etc/passwd",
                    "saves/../preferences/STSPlayer", "C:/Windows/x",
                    "ux0:data/x", "assets/images/x.png", "saves\\x",
                    "saves/x\x00y", "saves/sub/x", "saves/", "", " saves/x"):
            with self.subTest(path=bad):
                with self.assertRaises(ValueError):
                    ip.safe_rel(bad)

    def test_every_write_is_validated(self):
        dest = ip.LocalDestination(Path(tempfile.mkdtemp(prefix="stsvita-safe-")))
        with self.assertRaises(ValueError):
            dest.put("../escape", b"x")
        with self.assertRaises(ValueError):
            dest.put("assets/images/x.png", b"x")


# --------------------------------------------------------------------------
# scanning
# --------------------------------------------------------------------------

class TestScan(Case):
    def test_selects_only_progress(self):
        src = make_source(self.tmp / "sts", slots=(0, 2))
        scan = ip.scan_source(src, ip.PREFS_DIR)
        rels = [i.rel for i in scan.items]
        self.assertIn("saves/IRONCLAD.autosave", rels)
        self.assertIn("saves/2_IRONCLAD.autosave", rels)
        self.assertIn("preferences/2_STSPlayer", rels)
        self.assertTrue(all(r.startswith(("saves/", "preferences/")) for r in rels))
        reasons = {(r.name, r.reason) for r in scan.rejected}
        self.assertIn(("steam_autocloud.vdf", "not a progress file"), reasons)

    def test_never_reads_assets(self):
        src = make_source(self.tmp / "sts")
        assets = src / "assets" / "images"
        assets.mkdir(parents=True)
        (assets / "card.png").write_bytes(b"\x89PNG fake")
        # assets/ shares no name with a progress dir, so it is not even scanned
        scan = ip.scan_source(src, ip.PREFS_DIR)
        self.assertFalse(any("assets" in i.rel or "assets" in str(i.src)
                             for i in scan.items))

    def test_rejects_symlink(self):
        src = make_source(self.tmp / "sts")
        secret = self.tmp / "secret.txt"
        secret.write_text("private")
        link = src / ip.SAVES_DIR / "WATCHER.autosave"
        try:
            os.symlink(secret, link)
        except (OSError, NotImplementedError):
            self.skipTest("symlinks unavailable")
        scan = ip.scan_source(src, ip.PREFS_DIR)
        self.assertNotIn("saves/WATCHER.autosave", [i.rel for i in scan.items])
        self.assertIn(("WATCHER.autosave", "symlink"),
                      {(r.name, r.reason) for r in scan.rejected})

    def test_rejects_directory_and_oversize(self):
        src = make_source(self.tmp / "sts")
        (src / ip.SAVES_DIR / "DEFECT.autosave").mkdir()
        big = src / ip.SAVES_DIR / "WATCHER.autosave"
        big.write_bytes(b"\0" * (ip.MAX_FILE_BYTES + 1))
        scan = ip.scan_source(src, ip.PREFS_DIR)
        reasons = {r.name: r.reason for r in scan.rejected}
        self.assertEqual(reasons["DEFECT.autosave"], "not a regular file")
        self.assertIn("larger than", reasons["WATCHER.autosave"])

    def test_gog_beta_preferences(self):
        src = make_source(self.tmp / "gog", prefs_dir=ip.BETA_PREFS_DIR)
        (src / ip.PREFS_DIR).mkdir(exist_ok=True)  # present but empty, as on GOG
        self.assertEqual(ip.source_prefs_dir(src, None), ip.BETA_PREFS_DIR)
        scan = ip.scan_source(src, ip.BETA_PREFS_DIR)
        # GOG's betaPreferences land in the Vita's preferences/
        self.assertIn("preferences/STSPlayer", [i.rel for i in scan.items])

    def test_steam_beta_folder_is_not_mistaken_for_gog(self):
        src = make_source(self.tmp / "steam")
        beta = src / ip.BETA_PREFS_DIR
        beta.mkdir()
        (beta / "steam_autocloud.vdf").write_text("vdf")
        self.assertEqual(ip.source_prefs_dir(src, None), ip.PREFS_DIR)

    def test_ambiguous_prefs_dirs_error(self):
        src = make_source(self.tmp / "both")
        beta = src / ip.BETA_PREFS_DIR
        beta.mkdir()
        (beta / "STSPlayer").write_text("{}")
        with self.assertRaises(ValueError):
            ip.source_prefs_dir(src, None)
        self.assertEqual(ip.source_prefs_dir(src, ip.BETA_PREFS_DIR), ip.BETA_PREFS_DIR)


# --------------------------------------------------------------------------
# local import: dry run, apply, conflicts, backup
# --------------------------------------------------------------------------

class TestLocalImport(Case):
    def setUp(self):
        super().setUp()
        self.src = make_source(self.tmp / "sts", slots=(0, 2), marker="alpha")
        self.dest = self.tmp / "vita"

    def test_dry_run_writes_nothing(self):
        code, text = self.run_import(source=str(self.src), dest_dir=str(self.dest))
        self.assertEqual(code, ip.EXIT_OK)
        self.assertIn("dry run", text)
        self.assertFalse(self.dest.exists())

    def test_apply_copies_and_verifies(self):
        code, text = self.run_import(source=str(self.src), dest_dir=str(self.dest),
                                     apply=True,
                                     backup_dir=str(self.tmp / "backup"))
        self.assertEqual(code, ip.EXIT_OK)
        self.assertIn("verified", text)
        for rel in ("saves/IRONCLAD.autosave", "saves/2_IRONCLAD.autosave",
                    "preferences/STSPlayer", "preferences/2_STSUnlocks"):
            self.assertEqual((self.dest / rel).read_bytes(),
                             (self.src / rel).read_bytes())
        self.assertFalse((self.dest / "saves/steam_autocloud.vdf").exists())
        self.assertFalse((self.dest / "assets").exists())

    def test_identical_second_run_is_a_no_op(self):
        self.run_import(source=str(self.src), dest_dir=str(self.dest), apply=True,
                        backup_dir=str(self.tmp / "b1"))
        self.out = io.StringIO()
        code, text = self.run_import(source=str(self.src), dest_dir=str(self.dest),
                                     apply=True, backup_dir=str(self.tmp / "b2"))
        self.assertEqual(code, ip.EXIT_OK)
        self.assertIn("nothing to write", text)

    def test_slot_conflict_blocks_whole_slot(self):
        self.run_import(source=str(self.src), dest_dir=str(self.dest), apply=True,
                        backup_dir=str(self.tmp / "b1"))
        # A different install with the same slots, and an extra slot 1.
        other = make_source(self.tmp / "other", slots=(0, 1, 2), marker="beta")
        self.out = io.StringIO()
        code, text = self.run_import(source=str(other), dest_dir=str(self.dest),
                                     apply=True, backup_dir=str(self.tmp / "b2"))
        self.assertEqual(code, ip.EXIT_CONFLICT)
        self.assertIn("--replace-slots", text)
        # slot 1 was absent, so it imported; slots 0 and 2 kept their bytes
        self.assertTrue((self.dest / "saves/1_IRONCLAD.autosave").exists())
        self.assertIn(b"alpha", (self.dest / "saves/IRONCLAD.autosave").read_bytes())
        self.assertIn(b"alpha", (self.dest / "preferences/2_STSPlayer").read_bytes())

    def test_conflicted_slot_is_not_partially_merged(self):
        self.run_import(source=str(self.src), dest_dir=str(self.dest), apply=True,
                        backup_dir=str(self.tmp / "b1"))
        # Same slot, but only the save differs, the preferences still match.
        other = make_source(self.tmp / "other", slots=(0,), marker="alpha")
        (other / ip.SAVES_DIR / "IRONCLAD.autosave").write_text(
            json.dumps({"marker": "beta", "slot": 0, "gold": 1}))
        self.out = io.StringIO()
        code, _ = self.run_import(source=str(other), dest_dir=str(self.dest),
                                  apply=True, backup_dir=str(self.tmp / "b2"))
        self.assertEqual(code, ip.EXIT_CONFLICT)
        self.assertIn(b"alpha", (self.dest / "saves/IRONCLAD.autosave").read_bytes())

    def test_blocked_slot_does_not_leak_files_absent_on_the_destination(self):
        """The real hazard: a blocked slot's *missing* files must not be written.

        The destination has its own slot 0 (a different character mid-run).
        The source's slot 0 disagrees, so slot 0 is blocked. The source also
        has slot-0 files the destination simply lacks; writing those would
        drop this source's save next to the destination's own preferences and
        produce a slot neither install ever had.
        """
        dest = ip.LocalDestination(self.dest)
        dest.put("saves/THE_SILENT.autosave", b"device run in progress")
        dest.put("preferences/STSPlayer", json.dumps({"marker": "device"}).encode())
        dest.put("preferences/STSUnlocks", json.dumps({"Watcher": "2"}).encode())

        src = make_source(self.tmp / "steam", slots=(0, 1), marker="steam")
        code, text = self.run_import(source=str(src), dest_dir=str(self.dest),
                                     apply=True, backup_dir=str(self.tmp / "b"))
        self.assertEqual(code, ip.EXIT_CONFLICT)

        # slot 0 on the device is exactly as it was
        self.assertEqual((self.dest / "saves/THE_SILENT.autosave").read_bytes(),
                         b"device run in progress")
        self.assertIn(b"device", (self.dest / "preferences/STSPlayer").read_bytes())
        self.assertIn(b"Watcher", (self.dest / "preferences/STSUnlocks").read_bytes())
        # and none of the source's slot-0 files leaked in
        self.assertFalse((self.dest / "saves/IRONCLAD.autosave").exists())
        self.assertFalse((self.dest / "saves/IRONCLAD.autosave.backUp").exists())
        # slot 1 was untouched by the block and did import
        self.assertTrue((self.dest / "saves/1_IRONCLAD.autosave").exists())
        self.assertIn(b"steam", (self.dest / "preferences/1_STSPlayer").read_bytes())

    def test_replace_slots_overwrites(self):
        self.run_import(source=str(self.src), dest_dir=str(self.dest), apply=True,
                        backup_dir=str(self.tmp / "b1"))
        other = make_source(self.tmp / "other", slots=(0, 2), marker="beta")
        self.out = io.StringIO()
        code, _ = self.run_import(source=str(other), dest_dir=str(self.dest),
                                  apply=True, replace_slots="0,2",
                                  backup_dir=str(self.tmp / "b2"))
        self.assertEqual(code, ip.EXIT_OK)
        self.assertIn(b"beta", (self.dest / "saves/IRONCLAD.autosave").read_bytes())

    def test_replace_all(self):
        self.run_import(source=str(self.src), dest_dir=str(self.dest), apply=True,
                        backup_dir=str(self.tmp / "b1"))
        other = make_source(self.tmp / "other", slots=(0, 2), marker="beta")
        self.out = io.StringIO()
        code, _ = self.run_import(source=str(other), dest_dir=str(self.dest),
                                  apply=True, replace_all=True,
                                  backup_dir=str(self.tmp / "b2"))
        self.assertEqual(code, ip.EXIT_OK)
        self.assertIn(b"beta", (self.dest / "preferences/2_STSUnlocks").read_bytes())


# --------------------------------------------------------------------------
# STSSaveSlots: the cross-slot index
# --------------------------------------------------------------------------

class TestSlotIndex(Case):
    """The save-select screen renders from STSSaveSlots, not from the saves.

    It has no "<slot>_" filename prefix but holds keys for every slot, so it
    can neither be treated as slot-0 data (importing a slot would leave that
    slot looking empty) nor replaced wholesale (that would wipe the
    destination's own slot-0 name/playtime/completion).
    """

    DEVICE = json.dumps({
        "PLAYTIME": "18565", "PROFILE_NAME": "marc", "COMPLETION": "19.9394226",
        "DEFAULT_SLOT": "0",
        "1_PROFILE_NAME": "", "1_PLAYTIME": "0", "1_COMPLETION": "0",
        "2_PROFILE_NAME": "", "2_PLAYTIME": "0", "2_COMPLETION": "0",
    }, indent=2).encode()

    DESKTOP = json.dumps({
        "PLAYTIME": "2029", "PROFILE_NAME": "Marc", "COMPLETION": "1.0283936",
        "DEFAULT_SLOT": "1",
        "1_PROFILE_NAME": "nonatest", "1_PLAYTIME": "16", "1_COMPLETION": "0.135",
        "2_PROFILE_NAME": "nona", "2_PLAYTIME": "11", "2_COMPLETION": "0.0945",
    }, indent=2).encode()

    def test_key_slot(self):
        self.assertEqual(ip.key_slot("PLAYTIME"), 0)
        self.assertEqual(ip.key_slot("1_PLAYTIME"), 1)
        self.assertEqual(ip.key_slot("2_PROFILE_NAME"), 2)
        self.assertEqual(ip.key_slot("DEFAULT_SLOT"), 0)

    def test_merge_takes_only_the_imported_slots(self):
        merged = json.loads(ip.merge_slot_index(self.DEVICE, self.DESKTOP, {1, 2}))
        # imported slots now describe the desktop's profiles
        self.assertEqual(merged["1_PROFILE_NAME"], "nonatest")
        self.assertEqual(merged["1_PLAYTIME"], "16")
        self.assertEqual(merged["2_PROFILE_NAME"], "nona")
        # slot 0 on the device is untouched
        self.assertEqual(merged["PROFILE_NAME"], "marc")
        self.assertEqual(merged["PLAYTIME"], "18565")
        self.assertEqual(merged["COMPLETION"], "19.9394226")
        self.assertEqual(merged["DEFAULT_SLOT"], "0")

    def test_merge_into_an_empty_destination(self):
        merged = json.loads(ip.merge_slot_index(None, self.DESKTOP, {1}))
        self.assertEqual(merged["1_PROFILE_NAME"], "nonatest")
        self.assertNotIn("PROFILE_NAME", merged)   # slot 0 was not imported

    def test_merge_refuses_unparseable_files(self):
        self.assertIsNone(ip.merge_slot_index(b"not json", self.DESKTOP, {1}))
        self.assertIsNone(ip.merge_slot_index(self.DEVICE, b"[]", {1}))

    def test_import_updates_the_index_without_touching_slot_zero(self):
        dest_dir = self.tmp / "vita"
        dest = ip.LocalDestination(dest_dir)
        dest.put("saves/THE_SILENT.autosave", b"device run")
        dest.put("preferences/STSPlayer", b'{"marker": "device"}')
        dest.put("preferences/STSSaveSlots", self.DEVICE)

        src = make_source(self.tmp / "steam", slots=(0, 1), marker="steam")
        (src / "preferences/STSSaveSlots").write_bytes(self.DESKTOP)

        code, text = self.run_import(source=str(src), dest_dir=str(dest_dir),
                                     apply=True, backup_dir=str(self.tmp / "b"))
        self.assertEqual(code, ip.EXIT_CONFLICT)      # slot 0 still blocked
        merged = json.loads((dest_dir / "preferences/STSSaveSlots").read_bytes())
        self.assertEqual(merged["1_PROFILE_NAME"], "nonatest")
        self.assertEqual(merged["PROFILE_NAME"], "marc")
        self.assertIn("merged in", text)

    def test_index_backup_is_never_written(self):
        """A partial.backUp could be restored over a good index on corruption."""
        dest_dir = self.tmp / "vita"
        dest = ip.LocalDestination(dest_dir)
        dest.put("preferences/STSSaveSlots", self.DEVICE)
        src = make_source(self.tmp / "steam", slots=(1,), marker="steam")
        (src / "preferences/STSSaveSlots").write_bytes(self.DESKTOP)
        (src / "preferences/STSSaveSlots.backUp").write_bytes(self.DESKTOP)
        self.run_import(source=str(src), dest_dir=str(dest_dir), apply=True,
                        backup_dir=str(self.tmp / "b"))
        self.assertFalse((dest_dir / "preferences/STSSaveSlots.backUp").exists())

    def test_second_run_repairs_a_stale_index(self):
        """Files already copied, index still says the slot is empty.

        This is the state a run leaves behind if the index was skipped: the
        saves are on the device but the save-select screen shows nothing,
        because it renders from STSSaveSlots. Re-running must fix it even
        though every file is already byte-identical.
        """
        dest_dir = self.tmp / "vita"
        src = make_source(self.tmp / "steam", slots=(1,), marker="steam")
        (src / "preferences/STSSaveSlots").write_bytes(self.DESKTOP)

        # first run: copy slot 1 but leave the index stale, as the old code did
        self.run_import(source=str(src), dest_dir=str(dest_dir), apply=True,
                        backup_dir=str(self.tmp / "b1"))
        ip.LocalDestination(dest_dir).put("preferences/STSSaveSlots", self.DEVICE)
        self.assertEqual(
            json.loads((dest_dir / "preferences/STSSaveSlots").read_bytes())["1_PROFILE_NAME"],
            "")

        self.out = io.StringIO()
        code, text = self.run_import(source=str(src), dest_dir=str(dest_dir),
                                     apply=True, backup_dir=str(self.tmp / "b2"))
        self.assertEqual(code, ip.EXIT_OK)
        merged = json.loads((dest_dir / "preferences/STSSaveSlots").read_bytes())
        self.assertEqual(merged["1_PROFILE_NAME"], "nonatest")
        self.assertEqual(merged["1_PLAYTIME"], "16")
        self.assertEqual(merged["PROFILE_NAME"], "marc")

    def test_no_index_write_when_nothing_is_imported(self):
        dest_dir = self.tmp / "vita"
        dest = ip.LocalDestination(dest_dir)
        dest.put("preferences/STSSaveSlots", self.DEVICE)
        src = make_source(self.tmp / "steam", slots=(0,), marker="steam")
        (src / "preferences/STSSaveSlots").write_bytes(self.DESKTOP)
        # slot 0 conflicts on STSPlayer, so nothing imports
        dest.put("preferences/STSPlayer", b'{"marker": "device"}')
        dest.put("preferences/STSUnlocks", b'{"marker": "device"}')
        dest.put("saves/IRONCLAD.autosave", b"device")
        dest.put("saves/IRONCLAD.autosave.backUp", b"device")
        self.run_import(source=str(src), dest_dir=str(dest_dir), apply=True,
                        backup_dir=str(self.tmp / "b"))
        self.assertEqual((dest_dir / "preferences/STSSaveSlots").read_bytes(),
                         self.DEVICE)


# --------------------------------------------------------------------------
# backup and rollback
# --------------------------------------------------------------------------

class TestBackupRollback(Case):
    def setUp(self):
        super().setUp()
        self.src = make_source(self.tmp / "sts", marker="alpha")
        self.dest = self.tmp / "vita"
        self.b1 = self.tmp / "b1"
        self.b2 = self.tmp / "b2"

    def test_backup_captures_destination_before_overwrite(self):
        self.run_import(source=str(self.src), dest_dir=str(self.dest), apply=True,
                        backup_dir=str(self.b1))
        # first import had nothing to back up
        self.assertFalse(self.b1.exists())

        other = make_source(self.tmp / "other", marker="beta")
        self.out = io.StringIO()
        self.run_import(source=str(other), dest_dir=str(self.dest), apply=True,
                        replace_all=True, backup_dir=str(self.b2))
        manifest = json.loads((self.b2 / "manifest.json").read_text())
        self.assertIn("saves/IRONCLAD.autosave", manifest["files"])
        self.assertIn(b"alpha", (self.b2 / "saves/IRONCLAD.autosave").read_bytes())
        self.assertIn(b"beta", (self.dest / "saves/IRONCLAD.autosave").read_bytes())

    def test_rollback_restores_previous_progress(self):
        self.run_import(source=str(self.src), dest_dir=str(self.dest), apply=True,
                        backup_dir=str(self.b1))
        other = make_source(self.tmp / "other", marker="beta")
        self.out = io.StringIO()
        self.run_import(source=str(other), dest_dir=str(self.dest), apply=True,
                        replace_all=True, backup_dir=str(self.b2))

        self.out = io.StringIO()
        code, text = self.run_import(dest_dir=str(self.dest),
                                     rollback_from=str(self.b2), apply=True)
        self.assertEqual(code, ip.EXIT_OK)
        self.assertIn("restored", text)
        self.assertIn(b"alpha", (self.dest / "saves/IRONCLAD.autosave").read_bytes())

    def test_rollback_dry_run_changes_nothing(self):
        self.run_import(source=str(self.src), dest_dir=str(self.dest), apply=True,
                        backup_dir=str(self.b1))
        other = make_source(self.tmp / "other", marker="beta")
        self.out = io.StringIO()
        self.run_import(source=str(other), dest_dir=str(self.dest), apply=True,
                        replace_all=True, backup_dir=str(self.b2))
        self.out = io.StringIO()
        code, text = self.run_import(dest_dir=str(self.dest),
                                     rollback_from=str(self.b2))
        self.assertEqual(code, ip.EXIT_OK)
        self.assertIn("dry run", text)
        self.assertIn(b"beta", (self.dest / "saves/IRONCLAD.autosave").read_bytes())

    def test_corrupt_backup_is_refused(self):
        self.run_import(source=str(self.src), dest_dir=str(self.dest), apply=True,
                        backup_dir=str(self.b1))
        other = make_source(self.tmp / "other", marker="beta")
        self.out = io.StringIO()
        self.run_import(source=str(other), dest_dir=str(self.dest), apply=True,
                        replace_all=True, backup_dir=str(self.b2))
        (self.b2 / "saves/IRONCLAD.autosave").write_bytes(b"tampered")
        with self.assertRaises(ValueError):
            ip.load_backup(self.b2)


# --------------------------------------------------------------------------
# fake VitaShell FTP
# --------------------------------------------------------------------------

class FakeFTP:
    """An in-memory stand-in for the VitaShell FTP server.

    Implements just the ftplib surface FtpDestination uses, with the same
    failure modes: error_perm for a missing RETR, error_perm for a redundant
    MKD, and NLST returning full paths.
    """

    def __init__(self, files: dict[str, bytes] | None = None, no_nlst: bool = False):
        self.files: dict[str, bytes] = dict(files or {})
        self.dirs: set[str] = set()
        self.stored: list[str] = []
        self.fail_stor_on: set[str] = set()
        self.corrupt_stor_on: set[str] = set()
        self.quit_called = False
        # VitaShell's FTP has no NLST; it answers 502.
        self.no_nlst = no_nlst
        self.cwd_path = "/"

    # -- ftplib surface ---------------------------------------------------
    def voidcmd(self, cmd):
        return "200 ok"

    def mkd(self, path):
        if path in self.dirs:
            raise ftplib.error_perm("550 already exists")
        self.dirs.add(path)
        return path

    def nlst(self, path):
        if self.no_nlst:
            raise ftplib.error_perm("502 Sorry, command not implemented. :(")
        prefix = path.rstrip("/") + "/"
        hits = [f for f in self.files if f.startswith(prefix)]
        if not hits and path.rstrip("/") not in self.dirs:
            raise ftplib.error_perm("550 no such directory")
        return hits

    def pwd(self):
        return self.cwd_path

    def cwd(self, path):
        target = path.rstrip("/")
        if target != "/" and not any(
                f.startswith(target + "/") for f in self.files) \
                and target not in self.dirs:
            raise ftplib.error_perm("550 Invalid directory.")
        self.cwd_path = target
        return "250 ok"

    def retrlines(self, cmd, callback):
        # The real server ignores a path argument to LIST and lists the CWD.
        prefix = self.cwd_path.rstrip("/") + "/"
        seen = set()
        for path, data in sorted(self.files.items()):
            if not path.startswith(prefix):
                continue
            rest = path[len(prefix):]
            if "/" in rest:
                d = rest.split("/", 1)[0]
                if d not in seen:
                    seen.add(d)
                    callback(f"drwxr-xr-x 1 vita vita 32768 Aug 1  19:46 {d}")
                continue
            callback(f"-rw-r--r-- 1 vita vita {len(data)} Aug 1  19:46 {rest}")
        return "226 ok"

    def retrbinary(self, cmd, callback):
        path = cmd.split(" ", 1)[1]
        if path not in self.files:
            raise ftplib.error_perm("550 no such file")
        callback(self.files[path])
        return "226 ok"

    def storbinary(self, cmd, fh):
        path = cmd.split(" ", 1)[1]
        data = fh.read()
        if path in self.fail_stor_on:
            raise ftplib.error_temp("451 write failed")
        self.stored.append(path)
        # a truncated upload the server still accepts
        self.files[path] = data[:-1] if path in self.corrupt_stor_on else data
        return "226 ok"

    def quit(self):
        self.quit_called = True


class TestFtpDestination(Case):
    def make_dest(self, ftp: FakeFTP) -> ip.FtpDestination:
        return ip.FtpDestination("192.0.2.10", connection=ftp)

    def test_paths_use_the_vita_data_base(self):
        ftp = FakeFTP()
        dest = self.make_dest(ftp)
        dest.put("saves/IRONCLAD.autosave", b"payload")
        self.assertEqual(ftp.stored, [f"{ip.DEFAULT_FTP_BASE}/saves/IRONCLAD.autosave"])
        self.assertIn(f"{ip.DEFAULT_FTP_BASE}/saves", ftp.dirs)

    def test_read_back_verification_catches_truncation(self):
        ftp = FakeFTP()
        ftp.corrupt_stor_on.add(f"{ip.DEFAULT_FTP_BASE}/saves/IRONCLAD.autosave")
        dest = self.make_dest(ftp)
        with self.assertRaises(ip.TransferError) as cm:
            dest.put("saves/IRONCLAD.autosave", b"payload")
        self.assertIn("checksum mismatch", str(cm.exception))

    def test_upload_failure_is_reported(self):
        ftp = FakeFTP()
        ftp.fail_stor_on.add(f"{ip.DEFAULT_FTP_BASE}/saves/IRONCLAD.autosave")
        dest = self.make_dest(ftp)
        with self.assertRaises(ip.TransferError):
            dest.put("saves/IRONCLAD.autosave", b"payload")

    def test_missing_file_reads_as_none(self):
        self.assertIsNone(self.make_dest(FakeFTP()).read("saves/IRONCLAD.autosave"))

    def test_existing_progress_ignores_junk(self):
        ftp = FakeFTP({
            f"{ip.DEFAULT_FTP_BASE}/saves/IRONCLAD.autosave": b"a",
            f"{ip.DEFAULT_FTP_BASE}/saves/steam_autocloud.vdf": b"junk",
            f"{ip.DEFAULT_FTP_BASE}/preferences/STSPlayer": b"b",
        })
        found = self.make_dest(ftp).existing_progress()
        self.assertEqual(sorted(found), ["preferences/STSPlayer", "saves/IRONCLAD.autosave"])

    def test_base_path_uses_the_form_vitashell_resolves(self):
        # "ux0:data/..." is rejected by the device with 550; "ux0:/data/..."
        # is what VitaShell and tools/deploy_assets.py actually use.
        self.assertTrue(ip.DEFAULT_FTP_BASE.startswith("ux0:/"))

    def test_existing_progress_without_nlst(self):
        """The device answers NLST with 502; listing must still work.

        Without the CWD+LIST fallback this returns {}, which would silently
        mean "no backup needed" and "no slot conflicts" on real hardware.
        """
        ftp = FakeFTP({
            f"{ip.DEFAULT_FTP_BASE}/saves/THE_SILENT.autosave": b"a",
            f"{ip.DEFAULT_FTP_BASE}/preferences/STSUnlocks": b"b",
            f"{ip.DEFAULT_FTP_BASE}/preferences/steam_autocloud.vdf": b"junk",
        }, no_nlst=True)
        found = self.make_dest(ftp).existing_progress()
        self.assertEqual(sorted(found),
                         ["preferences/STSUnlocks", "saves/THE_SILENT.autosave"])

    def test_list_line_parsing(self):
        self.assertEqual(
            ip.parse_list_line(
                "-rw-r--r-- 1 vita vita 16720 Aug 1  19:46 THE_SILENT.autosave"),
            "THE_SILENT.autosave")
        self.assertIsNone(ip.parse_list_line(
            "drwxr-xr-x 1 vita vita 32768 Jul 4  14:44 assets"))
        self.assertIsNone(ip.parse_list_line("garbage"))

    def test_backup_is_taken_on_a_server_without_nlst(self):
        """End to end: a device that cannot NLST must still be backed up."""
        src = make_source(self.tmp / "sts", marker="beta")
        ftp = FakeFTP({
            f"{ip.DEFAULT_FTP_BASE}/saves/IRONCLAD.autosave": b"device-run",
        }, no_nlst=True)
        real = ip.open_destination
        ip.open_destination = lambda args: ip.FtpDestination(
            args.ftp_host, args.ftp_port, args.ftp_base, connection=ftp)
        try:
            code, text = self.run_import(source=str(src), ftp_host="192.0.2.10",
                                         apply=True, replace_all=True,
                                         backup_dir=str(self.tmp / "b"))
        finally:
            ip.open_destination = real
        self.assertEqual(code, ip.EXIT_OK)
        self.assertIn("backed up", text)
        self.assertEqual((self.tmp / "b/saves/IRONCLAD.autosave").read_bytes(),
                         b"device-run")


class TestFtpImport(Case):
    """End-to-end run() over FTP, with the connection injected."""

    def setUp(self):
        super().setUp()
        self.src = make_source(self.tmp / "sts", marker="alpha")
        self.ftp = FakeFTP()
        self._real = ip.open_destination
        ip.open_destination = lambda args: ip.FtpDestination(
            args.ftp_host, args.ftp_port, args.ftp_base, connection=self.ftp)
        self.addCleanup(lambda: setattr(ip, "open_destination", self._real))

    def test_dry_run_uploads_nothing(self):
        code, text = self.run_import(source=str(self.src), ftp_host="192.0.2.10")
        self.assertEqual(code, ip.EXIT_OK)
        self.assertIn("dry run", text)
        self.assertEqual(self.ftp.stored, [])

    def test_apply_uploads_and_verifies(self):
        code, text = self.run_import(source=str(self.src), ftp_host="192.0.2.10",
                                     apply=True, backup_dir=str(self.tmp / "b"))
        self.assertEqual(code, ip.EXIT_OK)
        self.assertIn("verified", text)
        self.assertIn(f"{ip.DEFAULT_FTP_BASE}/saves/IRONCLAD.autosave", self.ftp.files)
        self.assertEqual(self.ftp.files[f"{ip.DEFAULT_FTP_BASE}/preferences/STSPlayer"],
                         (self.src / "preferences/STSPlayer").read_bytes())
        self.assertNotIn(f"{ip.DEFAULT_FTP_BASE}/saves/steam_autocloud.vdf", self.ftp.files)
        self.assertTrue(self.ftp.quit_called)

    def test_interrupted_upload_stops_and_points_at_the_backup(self):
        # seed the device so there is something to back up and to roll back to
        self.run_import(source=str(self.src), ftp_host="192.0.2.10", apply=True,
                        backup_dir=str(self.tmp / "b0"))
        other = make_source(self.tmp / "other", marker="beta")
        self.ftp.fail_stor_on.add(f"{ip.DEFAULT_FTP_BASE}/preferences/STSUnlocks")

        self.out = io.StringIO()
        err = io.StringIO()
        real_stderr, sys.stderr = sys.stderr, err
        try:
            code = ip.run(make_args(source=str(other), ftp_host="192.0.2.10",
                                    apply=True, replace_all=True,
                                    backup_dir=str(self.tmp / "b1")), out=self.out)
        finally:
            sys.stderr = real_stderr
        self.assertEqual(code, ip.EXIT_TRANSFER)
        self.assertIn("TRANSFER FAILED", err.getvalue())
        self.assertIn("--rollback-from", err.getvalue())
        # the pre-failure state is recoverable
        restored = ip.load_backup(self.tmp / "b1")
        self.assertIn(b"alpha", restored["saves/IRONCLAD.autosave"])

    def test_rollback_over_ftp(self):
        self.run_import(source=str(self.src), ftp_host="192.0.2.10", apply=True,
                        backup_dir=str(self.tmp / "b0"))
        other = make_source(self.tmp / "other", marker="beta")
        self.out = io.StringIO()
        self.run_import(source=str(other), ftp_host="192.0.2.10", apply=True,
                        replace_all=True, backup_dir=str(self.tmp / "b1"))
        self.assertIn(b"beta", self.ftp.files[f"{ip.DEFAULT_FTP_BASE}/saves/IRONCLAD.autosave"])

        self.out = io.StringIO()
        code, _ = self.run_import(ftp_host="192.0.2.10",
                                  rollback_from=str(self.tmp / "b1"), apply=True)
        self.assertEqual(code, ip.EXIT_OK)
        self.assertIn(b"alpha", self.ftp.files[f"{ip.DEFAULT_FTP_BASE}/saves/IRONCLAD.autosave"])


# --------------------------------------------------------------------------
# CLI guard rails
# --------------------------------------------------------------------------

class TestCli(Case):
    def test_requires_a_source(self):
        err = io.StringIO()
        real, sys.stderr = sys.stderr, err
        try:
            code = ip.main(["--dest-dir", str(self.tmp / "d")])
        finally:
            sys.stderr = real
        self.assertEqual(code, ip.EXIT_USAGE)
        self.assertIn("--source", err.getvalue())

    def test_requires_exactly_one_destination(self):
        src = make_source(self.tmp / "sts")
        err = io.StringIO()
        real, sys.stderr = sys.stderr, err
        try:
            code = ip.run(make_args(source=str(src)), out=self.out)
            self.assertEqual(code, ip.EXIT_USAGE)
            code = ip.run(make_args(source=str(src), dest_dir="x",
                                    ftp_host="192.0.2.10"), out=self.out)
            self.assertEqual(code, ip.EXIT_USAGE)
        finally:
            sys.stderr = real
        self.assertIn("exactly one", err.getvalue())

    def test_rejects_a_directory_that_is_not_an_install(self):
        plain = self.tmp / "plain"
        plain.mkdir()
        err = io.StringIO()
        real, sys.stderr = sys.stderr, err
        try:
            code = ip.run(make_args(source=str(plain), dest_dir=str(self.tmp / "d")),
                          out=self.out)
        finally:
            sys.stderr = real
        self.assertEqual(code, ip.EXIT_USAGE)
        self.assertIn("Slay the Spire install", err.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
