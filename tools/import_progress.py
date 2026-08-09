#!/usr/bin/env python3
"""Copy Slay the Spire progress from a desktop install onto the Vita.

Progress is two directories next to the desktop game's jar:

  saves/ <CHAR>.autosave (+.backUp), prefixed "<slot>_" for slots 1-2
  preferences/ STSPlayer, STSUnlocks, STSSeenCards,... (+.backUp), same
                 "<slot>_" prefix. JSON written by SaveHelper/Prefs.

Both are plain files the port reads through VitaFileHandle, so the desktop
bytes are the Vita bytes, with no conversion. (Gdx.app.getPreferences maps to
VitaPreferences, which is only reached by CardCrawlGame's dead saveMigration
path and does not own progress.)

GOG and beta installs keep preferences in "betaPreferences/" instead
(SaveHelper.getSaveDir: isBeta || isGog). The Vita build is Steam-flavoured
and always reads "preferences/", so a GOG source is folded into that name.

Nothing under assets/ is ever read or written, and run history (runs/) is not
transferred, because the game does not need it to restore a save or an unlock.

  # look, change nothing
  tools/import_progress.py --auto --ftp-host 192.168.1.42

  # stage locally, then write for real
  tools/import_progress.py --source ~/GOG/SlayTheSpire --dest-dir /tmp/vita --apply

  # put a backup back
  tools/import_progress.py --ftp-host 192.168.1.42 \
      --rollback-from progress-backups/20260809T101500Z --apply

Close STSVITA on the Vita before applying: the game flushes its own progress
on exit and would overwrite whatever was just imported.
"""
from __future__ import annotations

import argparse
import ftplib
import hashlib
import io
import json
import os
import re
import sys
import dataclasses
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

SAVES_DIR = "saves"
PREFS_DIR = "preferences"
BETA_PREFS_DIR = "betaPreferences"

#: Destination layout. The Vita build resolves Gdx.files.local() against
#: ux0:data/stsvita/, so these are the same two names as the desktop.
DEST_DIRS = (SAVES_DIR, PREFS_DIR)

#: VitaShell's FTP resolves "ux0:/path", not "ux0:path" (the same form
#: tools/deploy_assets.py is invoked with).
DEFAULT_FTP_BASE = "ux0:/data/stsvita"
DEFAULT_FTP_PORT = 1337

#: Save-file stems. Ironclad/Silent/Defect/Watcher, as SaveHelper names them.
CHARACTERS = frozenset({"IRONCLAD", "THE_SILENT", "DEFECT", "WATCHER"})

#: Preference file stems, from SaveHelper.deletePrefs and CardCrawlGame.
PREF_NAMES = frozenset({
    "STSAchievements",
    "STSBetaCardPreference",
    "STSDaily",
    "STSDataDefect",
    "STSDataTheSilent",
    "STSDataVagabond",
    "STSDataWatcher",
    "STSGameplaySettings",
    "STSInputSettings",
    "STSInputSettings_Controller",
    "STSPlayer",
    "STSSaveSlots",
    "STSSeenBosses",
    "STSSeenCards",
    "STSSeenRelics",
    "STSSound",
    "STSTips",
    "STSUnlockProgress",
    "STSUnlocks",
})

BACKUP_SUFFIX = ".backUp"
SAVE_SUFFIX = ".autosave"

#: STSSaveSlots is the odd one out: it carries no "<slot>_" prefix, yet it
#: indexes every slot ("PROFILE_NAME" for slot 0, "1_PLAYTIME", "2_COMPLETION"
#:...). The save-select screen renders from it, so importing a slot without
#: updating it leaves that slot looking empty. Replacing it wholesale is just
#: as wrong: it would overwrite the destination's own slot-0 name, playtime
#: and completion. It is merged key-by-key instead, for the imported slots.
SLOT_INDEX = "STSSaveSlots"

#: Which slot a STSSaveSlots key belongs to ("2_PLAYTIME" -> 2, "PLAYTIME" -> 0).
SLOT_KEY_RE = re.compile(r"^(?P<slot>[1-9])_")

#: Slot 0 has no filename prefix (SaveHelper.slotName). A literal "0_" prefix
#: is never written by the game, so it is treated as unrecognised.
SLOT_PREFIX_RE = re.compile(r"^(?P<slot>[1-9])_(?P<base>.+)$")

#: Saves are ~12KB and preferences a few hundred bytes. The cap only exists so
#: a hostile or corrupt source cannot make us buffer something huge.
MAX_FILE_BYTES = 8 * 1024 * 1024

EXIT_OK = 0
EXIT_USAGE = 2
EXIT_CONFLICT = 3
EXIT_TRANSFER = 4


class TransferError(RuntimeError):
    """A write or its read-back verification failed."""


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def utc_stamp() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")


# --------------------------------------------------------------------------
# name parsing / validation
# --------------------------------------------------------------------------

def split_slot(name: str) -> tuple[int, str]:
    """"2_STSPlayer" -> (2, "STSPlayer"); "STSPlayer" -> (0, "STSPlayer")."""
    m = SLOT_PREFIX_RE.match(name)
    if m:
        return int(m.group("slot")), m.group("base")
    return 0, name


def classify(kind: str, name: str) -> tuple[int, str] | None:
    """Return (slot, stem) for a recognised progress file, else None.

    `kind` is "saves" or "preferences" (betaPreferences classifies as
    preferences, since the contents are identical and only the folder name differs).
    """
    slot, base = split_slot(name)
    if base.endswith(BACKUP_SUFFIX):
        base = base[: -len(BACKUP_SUFFIX)]
    if kind == SAVES_DIR:
        if not base.endswith(SAVE_SUFFIX):
            return None
        stem = base[: -len(SAVE_SUFFIX)]
        return (slot, stem) if stem in CHARACTERS else None
    return (slot, base) if base in PREF_NAMES else None


def key_slot(key: str) -> int:
    m = SLOT_KEY_RE.match(key)
    return int(m.group("slot")) if m else 0


def merge_slot_index(dest: bytes | None, src: bytes, slots: set[int]) -> bytes | None:
    """Copy only `slots`' keys out of the source index into the destination's.

    Returns None if either side is not the JSON object the game writes, so a
    corrupt or unexpected file is left alone rather than rewritten.
    """
    try:
        merged = json.loads(dest) if dest else {}
        incoming = json.loads(src)
    except (ValueError, TypeError):
        return None
    if not isinstance(merged, dict) or not isinstance(incoming, dict):
        return None
    for slot in slots:
        for key in [k for k in merged if key_slot(k) == slot]:
            merged.pop(key)
        merged.update({k: v for k, v in incoming.items() if key_slot(k) == slot})
    return (json.dumps(merged, indent=2) + "\n").encode("utf-8")


def safe_rel(rel: str) -> str:
    """Validate a destination-relative path built from a scanned basename.

    Belt and braces: the caller only ever joins a whitelisted directory with a
    basename that matched `classify`, but this is the single choke point every
    write goes through, so it re-checks the whole invariant.
    """
    if not rel or rel != rel.strip():
        raise ValueError(f"empty or padded path: {rel!r}")
    if "\\" in rel or "\x00" in rel:
        raise ValueError(f"illegal character in path: {rel!r}")
    if rel.startswith("/") or re.match(r"^[A-Za-z]:", rel) or ":" in rel:
        raise ValueError(f"absolute path rejected: {rel!r}")
    parts = rel.split("/")
    if len(parts) != 2:
        raise ValueError(f"expected '<dir>/<file>': {rel!r}")
    top, name = parts
    if top not in DEST_DIRS:
        raise ValueError(f"path outside saves/ and preferences/: {rel!r}")
    if name in ("", ".", "..") or top in ("", ".", ".."):
        raise ValueError(f"path traversal rejected: {rel!r}")
    return rel


# --------------------------------------------------------------------------
# source discovery and scanning
# --------------------------------------------------------------------------

def steam_libraries() -> list[Path]:
    """Steam library roots, including extra libraries from libraryfolders.vdf."""
    roots: list[Path] = []
    home = Path.home()
    candidates = [
        home / ".local/share/Steam",
        home / ".steam/steam",
        home / ".steam/root",
        home / "Library/Application Support/Steam",          # macOS
        Path("C:/Program Files (x86)/Steam"),                # Windows
    ]
    seen: set[Path] = set()
    for root in candidates:
        if root.is_dir() and root not in seen:
            seen.add(root)
            roots.append(root)
        vdf = root / "steamapps/libraryfolders.vdf"
        if not vdf.is_file():
            continue
        try:
            text = vdf.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        # Only the "path" values matter; a real VDF parser is overkill here.
        for extra in re.findall(r'"path"\s+"([^"]+)"', text):
            p = Path(extra)
            if p.is_dir() and p not in seen:
                seen.add(p)
                roots.append(p)
    return roots


def discover_sources() -> list[Path]:
    """Installed desktop Slay the Spire directories, best-effort."""
    found: list[Path] = []
    seen: set[Path] = set()

    def offer(p: Path) -> None:
        try:
            resolved = p.resolve()
        except OSError:
            return
        if resolved in seen or not looks_like_sts(resolved):
            return
        seen.add(resolved)
        found.append(resolved)

    for lib in steam_libraries():
        offer(lib / "steamapps/common/SlayTheSpire")

    home = Path.home()
    for gog in (
        home / "GOG Games/Slay the Spire",
        home / "Games/Slay the Spire",
        Path("/opt/GOG Games/Slay the Spire"),
        Path("C:/GOG Games/Slay the Spire"),
        home / "Library/Application Support/GOG.com/Slay the Spire",
    ):
        offer(gog)
    return found


def looks_like_sts(path: Path) -> bool:
    if not path.is_dir():
        return False
    if (path / "desktop-1.0.jar").is_file():
        return True
    return any((path / d).is_dir() for d in (SAVES_DIR, PREFS_DIR, BETA_PREFS_DIR))


def source_prefs_dir(source: Path, override: str | None) -> str:
    """Pick preferences/ or betaPreferences/ for this source.

    A GOG or beta install writes its progress to betaPreferences/. Steam
    installs have a betaPreferences/ folder too, but it only ever holds
    steam_autocloud.vdf, which does not classify as progress, so "has
    recognised files" is the discriminator rather than "exists".
    """
    if override:
        if override not in (PREFS_DIR, BETA_PREFS_DIR):
            raise ValueError(f"--source-prefs-dir must be {PREFS_DIR} or {BETA_PREFS_DIR}")
        return override
    populated = [d for d in (PREFS_DIR, BETA_PREFS_DIR) if _has_progress(source / d, PREFS_DIR)]
    if len(populated) > 1:
        raise ValueError(
            f"both {PREFS_DIR}/ and {BETA_PREFS_DIR}/ hold progress in {source}; "
            "pick one with --source-prefs-dir")
    return populated[0] if populated else PREFS_DIR


def _has_progress(path: Path, kind: str) -> bool:
    if not path.is_dir():
        return False
    try:
        entries = list(os.scandir(path))
    except OSError:
        return False
    return any(
        e.is_file(follow_symlinks=False) and classify(kind, e.name) for e in entries)


@dataclass(frozen=True)
class Item:
    """One progress file selected for transfer."""
    src: Path
    rel: str            # destination-relative, e.g. "saves/2_THE_SILENT.autosave"
    slot: int
    stem: str
    data: bytes

    @property
    def digest(self) -> str:
        return sha256(self.data)


@dataclass
class Rejection:
    where: str
    name: str
    reason: str


@dataclass
class Scan:
    items: list[Item] = field(default_factory=list)
    rejected: list[Rejection] = field(default_factory=list)


def scan_source(source: Path, prefs_dir: str) -> Scan:
    """Read every recognised progress file under saves/ and the prefs dir."""
    scan = Scan()
    for src_name, dest_name, kind in (
        (SAVES_DIR, SAVES_DIR, SAVES_DIR),
        (prefs_dir, PREFS_DIR, PREFS_DIR),
    ):
        directory = source / src_name
        if not directory.is_dir():
            continue
        for entry in sorted(os.scandir(directory), key=lambda e: e.name):
            _consider(entry, src_name, dest_name, kind, scan)
    scan.items.sort(key=lambda i: i.rel)
    return scan


def _consider(entry: os.DirEntry, src_name: str, dest_name: str,
              kind: str, scan: Scan) -> None:
    name = entry.name
    if entry.is_symlink():
        # Never produced by the game; following one could read outside the
        # source tree entirely.
        scan.rejected.append(Rejection(src_name, name, "symlink"))
        return
    if not entry.is_file(follow_symlinks=False):
        scan.rejected.append(Rejection(src_name, name, "not a regular file"))
        return
    parsed = classify(kind, name)
    if parsed is None:
        scan.rejected.append(Rejection(src_name, name, "not a progress file"))
        return
    slot, stem = parsed
    try:
        size = entry.stat(follow_symlinks=False).st_size
    except OSError as exc:
        scan.rejected.append(Rejection(src_name, name, f"unreadable ({exc.strerror})"))
        return
    if size > MAX_FILE_BYTES:
        scan.rejected.append(Rejection(src_name, name, f"larger than {MAX_FILE_BYTES} bytes"))
        return
    try:
        rel = safe_rel(f"{dest_name}/{name}")
    except ValueError as exc:
        scan.rejected.append(Rejection(src_name, name, str(exc)))
        return
    try:
        data = Path(entry.path).read_bytes()
    except OSError as exc:
        scan.rejected.append(Rejection(src_name, name, f"unreadable ({exc.strerror})"))
        return
    scan.items.append(Item(Path(entry.path), rel, slot, stem, data))


# --------------------------------------------------------------------------
# destinations
# --------------------------------------------------------------------------

class Destination:
    """Somewhere progress files can be listed, read and written."""

    def describe(self) -> str:
        raise NotImplementedError

    def list_dir(self, rel_dir: str) -> list[str]:
        raise NotImplementedError

    def read(self, rel: str) -> bytes | None:
        raise NotImplementedError

    def write(self, rel: str, data: bytes) -> None:
        raise NotImplementedError

    def close(self) -> None:
        pass

    def verify(self, rel: str, expected: str) -> None:
        """Read `rel` back and confirm it hashes to `expected`."""
        got = self.read(rel)
        if got is None:
            raise TransferError(f"{rel}: missing after write")
        if sha256(got) != expected:
            raise TransferError(
                f"{rel}: checksum mismatch after write "
                f"(wrote {expected[:12]}…, read back {sha256(got)[:12]}…)")

    def put(self, rel: str, data: bytes) -> None:
        """Write and verify in one step. Every mutation goes through here."""
        safe_rel(rel)
        self.write(rel, data)
        self.verify(rel, sha256(data))

    def existing_progress(self) -> dict[str, bytes]:
        """Every recognised progress file currently at the destination."""
        out: dict[str, bytes] = {}
        for dest_dir in DEST_DIRS:
            for name in sorted(self.list_dir(dest_dir)):
                if classify(dest_dir, name) is None:
                    continue
                rel = f"{dest_dir}/{name}"
                data = self.read(rel)
                if data is not None:
                    out[rel] = data
        return out


def parse_list_line(line: str) -> str | None:
    """File name from one UNIX-style LIST row, or None for dirs/junk.

    VitaShell answers in the classic nine-field form, e.g.
    "-rw-r--r-- 1 vita vita 16720 Aug 1 19:46 THE_SILENT.autosave".
    """
    if not line or line[0] == "d":
        return None
    parts = line.split(None, 8)
    if len(parts) < 9:
        return None
    name = parts[8].strip()
    return name if name not in ("", ".", "..") else None


class LocalDestination(Destination):
    """A directory on this machine: a Vita SD staging folder, or a test tree."""

    def __init__(self, root: Path):
        self.root = root

    def describe(self) -> str:
        return f"local directory {self.root}"

    def _path(self, rel: str) -> Path:
        return self.root / safe_rel(rel)

    def list_dir(self, rel_dir: str) -> list[str]:
        directory = self.root / rel_dir
        if not directory.is_dir():
            return []
        return [e.name for e in os.scandir(directory)
                if e.is_file(follow_symlinks=False) and not e.is_symlink()]

    def read(self, rel: str) -> bytes | None:
        path = self._path(rel)
        try:
            return path.read_bytes()
        except (FileNotFoundError, IsADirectoryError):
            return None

    def write(self, rel: str, data: bytes) -> None:
        path = self._path(rel)
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_name(path.name + ".import-tmp")
        tmp.write_bytes(data)
        os.replace(tmp, path)


class FtpDestination(Destination):
    """VitaShell's FTP server (SELECT in VitaShell; default port 1337).

    Mirrors tools/deploy_assets.py: stdlib ftplib, binary mode, mkdir -p by
    path component, "drive:" paths passed through unchanged.
    """

    def __init__(self, host: str, port: int = DEFAULT_FTP_PORT,
                 base: str = DEFAULT_FTP_BASE, timeout: int = 30,
                 connection=None):
        self.host, self.port, self.base = host, port, base.rstrip("/")
        if connection is not None:
            self.ftp = connection
        else:
            self.ftp = ftplib.FTP()
            self.ftp.connect(host, port, timeout=timeout)
            self.ftp.login()
        try:
            self.ftp.voidcmd("TYPE I")
        except ftplib.all_errors:
            pass
        self._made: set[str] = set()

    def describe(self) -> str:
        return f"Vita at {self.host}:{self.port} ({self.base})"

    def _remote(self, rel: str) -> str:
        return f"{self.base}/{safe_rel(rel)}"

    def _ensure_dir(self, remote_dir: str) -> None:
        if remote_dir in self._made:
            return
        cur = ""
        for part in remote_dir.split("/"):
            if not part:
                continue
            cur = f"{cur}/{part}" if cur else part
            try:
                self.ftp.mkd(cur)
            except ftplib.all_errors:
                pass  # already exists, or the server refuses redundant mkd
        self._made.add(remote_dir)

    def list_dir(self, rel_dir: str) -> list[str]:
        """File names in a remote directory.

        VitaShell's FTP answers NLST with "502 not implemented" and ignores a
        path argument to LIST, so a bare NLST result cannot be trusted: an
        empty answer would read as "the Vita has no progress" and quietly skip
        both the backup and every conflict check. Fall back to CWD + LIST,
        which the server does support.
        """
        remote = f"{self.base}/{rel_dir}"
        try:
            names = self.ftp.nlst(remote)
            if names:
                return [b for b in (n.rstrip("/").rsplit("/", 1)[-1] for n in names)
                        if b not in ("", ".", "..")]
        except ftplib.all_errors:
            pass
        return self._list_via_cwd(remote)

    def _list_via_cwd(self, remote: str) -> list[str]:
        try:
            previous = self.ftp.pwd()
        except ftplib.all_errors:
            previous = None
        try:
            self.ftp.cwd(remote)
        except ftplib.all_errors:
            return []          # directory absent: nothing there yet
        lines: list[str] = []
        try:
            self.ftp.retrlines("LIST", lines.append)
        except ftplib.all_errors:
            return []
        finally:
            if previous:
                try:
                    self.ftp.cwd(previous)
                except ftplib.all_errors:
                    pass
        out = []
        for line in lines:
            name = parse_list_line(line)
            if name:
                out.append(name)
        return out

    def read(self, rel: str) -> bytes | None:
        buf = io.BytesIO()
        try:
            self.ftp.retrbinary(f"RETR {self._remote(rel)}", buf.write)
        except ftplib.all_errors:
            return None
        return buf.getvalue()

    def write(self, rel: str, data: bytes) -> None:
        remote = self._remote(rel)
        self._ensure_dir(remote.rsplit("/", 1)[0])
        try:
            self.ftp.storbinary(f"STOR {remote}", io.BytesIO(data))
        except ftplib.all_errors as exc:
            raise TransferError(f"{rel}: upload failed ({exc})") from exc

    def close(self) -> None:
        try:
            self.ftp.quit()
        except Exception:
            pass


# --------------------------------------------------------------------------
# planning
# --------------------------------------------------------------------------

NEW, SAME, CONFLICT, MERGE = "new", "same", "conflict", "merge"


@dataclass
class Action:
    item: Item
    kind: str
    dest_digest: str | None = None


@dataclass
class Plan:
    actions: list[Action]
    blocked_slots: set[int]
    merges: list[Action] = field(default_factory=list)
    index_skipped: list[str] = field(default_factory=list)

    @property
    def writes(self) -> list[Action]:
        # A blocked slot is skipped whole, including files that are merely
        # absent on the destination. Writing just those would drop this
        # source's saves alongside the destination's own preferences for the
        # same slot, which is the partial merge the block exists to prevent.
        return [a for a in self.actions
                if a.kind in (NEW, CONFLICT) and a.item.slot not in self.blocked_slots
                ] + self.merges

    @property
    def imported_slots(self) -> set[int]:
        """Slots whose destination content comes from this source.

        Deliberately includes slots that are already byte-identical: the
        index still has to describe them. A second run over an
        already-copied slot is exactly how a stale index gets repaired.
        """
        return {a.item.slot for a in self.actions
                if a.item.slot not in self.blocked_slots}


def build_plan(scan: Scan, dest: Destination, replace: set[int]) -> Plan:
    existing = dest.existing_progress()
    actions: list[Action] = []
    index_items: list[Item] = []
    for item in scan.items:
        if item.stem == SLOT_INDEX:
            index_items.append(item)      # merged below, never slot-blocked
            continue
        current = existing.get(item.rel)
        if current is None:
            actions.append(Action(item, NEW))
        elif sha256(current) == item.digest:
            actions.append(Action(item, SAME, item.digest))
        else:
            actions.append(Action(item, CONFLICT, sha256(current)))

    conflicted = {a.item.slot for a in actions if a.kind == CONFLICT}
    # A slot is all-or-nothing. Writing only the non-conflicting half of a slot
    # would splice this source's saves onto the Vita's own preferences for the
    # same slot and silently produce a run neither install ever had.
    blocked = conflicted - replace
    plan = Plan(actions, blocked)

    slots = plan.imported_slots
    for item in index_items:
        if item.rel.endswith(BACKUP_SUFFIX):
            # A merged.backUp would be a half-written safety net: the game
            # restores from it on corruption, so a partial one could wipe the
            # destination's own slot metadata. Leave the game to write its own.
            plan.index_skipped.append(item.rel)
            continue
        if not slots:
            continue
        current = existing.get(item.rel)
        merged = merge_slot_index(current, item.data, slots)
        if merged is None:
            plan.index_skipped.append(f"{item.rel} (unparseable, left alone)")
            continue
        if current is not None and merged == current:
            continue
        plan.merges.append(
            Action(dataclasses.replace(item, data=merged), MERGE,
                   sha256(current) if current else None))
    return plan


def slot_label(slot: int) -> str:
    return f"slot {slot}" + (" (default)" if slot == 0 else "")


# --------------------------------------------------------------------------
# backup / rollback
# --------------------------------------------------------------------------

def write_backup(dest: Destination, backup_dir: Path) -> tuple[int, Path | None]:
    """Copy the destination's current progress to a host-side folder."""
    existing = dest.existing_progress()
    if not existing:
        return 0, None
    backup_dir.mkdir(parents=True, exist_ok=True)
    manifest = {
        "created": utc_stamp(),
        "destination": dest.describe(),
        "files": {},
    }
    for rel, data in sorted(existing.items()):
        out = backup_dir / rel
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(data)
        manifest["files"][rel] = {"sha256": sha256(data), "bytes": len(data)}
    (backup_dir / "manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return len(existing), backup_dir


def load_backup(backup_dir: Path) -> dict[str, bytes]:
    """Read a backup folder back, checking it against its own manifest."""
    manifest_path = backup_dir / "manifest.json"
    if not manifest_path.is_file():
        raise ValueError(f"no manifest.json in {backup_dir}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    files: dict[str, bytes] = {}
    for rel, meta in sorted(manifest.get("files", {}).items()):
        safe_rel(rel)
        data = (backup_dir / rel).read_bytes()
        if sha256(data) != meta["sha256"]:
            raise ValueError(f"{rel}: backup is corrupt (checksum mismatch)")
        files[rel] = data
    return files


# --------------------------------------------------------------------------
# reporting
# --------------------------------------------------------------------------

def report_scan(source: Path, prefs_dir: str, scan: Scan, out) -> None:
    print(f"source      : {source}", file=out)
    print(f"preferences : {prefs_dir}/"
          + ("   (GOG/beta layout)" if prefs_dir == BETA_PREFS_DIR else ""), file=out)
    by_slot: dict[int, int] = {}
    for item in scan.items:
        by_slot[item.slot] = by_slot.get(item.slot, 0) + 1
    if by_slot:
        summary = ", ".join(f"{slot_label(s)}: {n} files" for s, n in sorted(by_slot.items()))
        print(f"found       : {len(scan.items)} progress files ({summary})", file=out)
    else:
        print("found       : no progress files", file=out)
    if scan.rejected:
        print(f"skipped     : {len(scan.rejected)} non-progress entries", file=out)
        for r in scan.rejected:
            print(f"                {r.where}/{r.name}: {r.reason}", file=out)


def report_plan(plan: Plan, out) -> None:
    counts = {NEW: 0, SAME: 0, CONFLICT: 0}
    for a in plan.actions:
        counts[a.kind] += 1
    print(f"\nplan        : {counts[NEW]} new, {counts[SAME]} already identical, "
          f"{counts[CONFLICT]} differ", file=out)
    for a in plan.actions:
        if a.kind == SAME:
            continue
        blocked = a.item.slot in plan.blocked_slots
        if blocked:
            why = "differs" if a.kind == CONFLICT else "absent here"
            print(f"  ! {a.item.rel}  {why}, {slot_label(a.item.slot)} not touched",
                  file=out)
        elif a.kind == NEW:
            print(f"  + {a.item.rel}  ({len(a.item.data)} bytes)", file=out)
        else:
            print(f"  ~ {a.item.rel}  replaced ({len(a.item.data)} bytes)", file=out)
    for a in plan.merges:
        slots = ",".join(str(s) for s in sorted(plan.imported_slots))
        print(f"  * {a.item.rel}  slot {slots} entries merged in "
              f"(other slots kept)", file=out)
    for rel in plan.index_skipped:
        print(f"  - {rel}  not written (slot index backup)", file=out)
    if plan.blocked_slots:
        flags = ",".join(str(s) for s in sorted(plan.blocked_slots))
        print(f"\n  {len(plan.blocked_slots)} slot(s) already hold different progress "
              f"and were left alone.", file=out)
        print(f"  Re-run with --replace-slots {flags} (or --replace-all) to overwrite them.",
              file=out)


# --------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------

def open_destination(args) -> Destination:
    if args.dest_dir:
        return LocalDestination(Path(args.dest_dir))
    return FtpDestination(args.ftp_host, args.ftp_port, args.ftp_base)


def parse_slots(value: str) -> set[int]:
    out = set()
    for part in value.split(","):
        part = part.strip()
        if not part:
            continue
        if not part.isdigit():
            raise ValueError(f"not a slot number: {part!r}")
        out.add(int(part))
    return out


def resolve_source(args, out) -> Path:
    if args.source:
        source = Path(args.source).expanduser()
        if not source.is_dir():
            raise ValueError(f"not a directory: {source}")
        if not looks_like_sts(source):
            raise ValueError(
                f"{source} has no desktop-1.0.jar, saves/ or preferences/. "
                "point --source at the Slay the Spire install directory")
        return source.resolve()
    found = discover_sources()
    if not found:
        raise ValueError(
            "no Slay the Spire install found automatically; pass --source <dir>")
    if len(found) > 1:
        listing = "\n".join(f"  {p}" for p in found)
        raise ValueError(f"several installs found; pick one with --source:\n{listing}")
    print(f"discovered  : {found[0]}", file=out)
    return found[0]


def do_rollback(args, dest: Destination, out) -> int:
    backup_dir = Path(args.rollback_from).expanduser()
    files = load_backup(backup_dir)
    print(f"rollback    : {backup_dir}", file=out)
    print(f"destination : {dest.describe()}", file=out)
    print(f"files       : {len(files)}", file=out)
    if not args.apply:
        for rel in files:
            print(f"  ~ {rel}", file=out)
        print("\ndry run, nothing written. Add --apply to restore.", file=out)
        return EXIT_OK
    restored = 0
    for rel, data in files.items():
        dest.put(rel, data)
        restored += 1
        print(f"  ~ {rel}  restored and verified", file=out)
    print(f"\nrestored {restored} file(s) to {dest.describe()}.", file=out)
    return EXIT_OK


def run(args, out=sys.stdout) -> int:
    if bool(args.dest_dir) == bool(args.ftp_host):
        print("error: pass exactly one of --dest-dir or --ftp-host", file=sys.stderr)
        return EXIT_USAGE

    try:
        replace = parse_slots(args.replace_slots) if args.replace_slots else set()
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_USAGE

    try:
        dest = open_destination(args)
    except (OSError, *ftplib.all_errors) as exc:
        print(f"error: cannot reach destination: {exc}", file=sys.stderr)
        return EXIT_USAGE

    try:
        if args.rollback_from:
            return do_rollback(args, dest, out)

        try:
            source = resolve_source(args, out)
            prefs_dir = source_prefs_dir(source, args.source_prefs_dir)
        except ValueError as exc:
            print(f"error: {exc}", file=sys.stderr)
            return EXIT_USAGE

        scan = scan_source(source, prefs_dir)
        report_scan(source, prefs_dir, scan, out)
        print(f"destination : {dest.describe()}", file=out)
        if not scan.items:
            print("\nnothing to import.", file=out)
            return EXIT_OK

        if args.replace_all:
            replace = {i.slot for i in scan.items}
        plan = build_plan(scan, dest, replace)
        report_plan(plan, out)

        writes = plan.writes
        if not args.apply:
            print(f"\ndry run, nothing written. Add --apply to import "
                  f"{len(writes)} file(s).", file=out)
            print("Close STSVITA on the Vita first; it rewrites progress on exit.",
                  file=out)
            return EXIT_CONFLICT if plan.blocked_slots else EXIT_OK

        if not writes:
            print("\nnothing to write.", file=out)
            return EXIT_CONFLICT if plan.blocked_slots else EXIT_OK

        backup_dir = Path(args.backup_dir or f"progress-backups/{utc_stamp()}").expanduser()
        saved, location = write_backup(dest, backup_dir)
        if location:
            print(f"\nbacked up   : {saved} existing file(s) -> {location}", file=out)
        else:
            print("\nbacked up   : destination had no progress to back up", file=out)

        written = 0
        try:
            for action in writes:
                dest.put(action.item.rel, action.item.data)
                written += 1
                mark = {NEW: "+", MERGE: "*"}.get(action.kind, "~")
                print(f"  {mark} {action.item.rel}  verified {action.item.digest[:12]}…",
                      file=out)
        except TransferError as exc:
            print(f"\nTRANSFER FAILED after {written} file(s): {exc}", file=sys.stderr)
            if location:
                print(f"Roll back with:\n  {sys.argv[0]} "
                      f"{_dest_flags(args)} --rollback-from {location} --apply",
                      file=sys.stderr)
            return EXIT_TRANSFER

        print(f"\nimported {written} file(s) to {dest.describe()}, each verified by "
              f"SHA-256 read-back.", file=out)
        if location:
            print(f"Previous progress: {location}", file=out)
            print(f"Undo with:\n  {sys.argv[0]} {_dest_flags(args)} "
                  f"--rollback-from {location} --apply", file=out)
        if plan.blocked_slots:
            return EXIT_CONFLICT
        return EXIT_OK
    finally:
        dest.close()


def _dest_flags(args) -> str:
    if args.dest_dir:
        return f"--dest-dir {args.dest_dir}"
    return f"--ftp-host {args.ftp_host} --ftp-port {args.ftp_port}"


def build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(
        description="Copy Slay the Spire desktop progress (saves + preferences) "
                    "onto the Vita.",
        epilog="Assets are never touched. Close STSVITA before applying.")
    src = ap.add_argument_group("source (desktop install)")
    src.add_argument("--source", help="Slay the Spire install directory")
    src.add_argument("--auto", action="store_true",
                     help="find the install automatically (Steam libraries, GOG)")
    src.add_argument("--source-prefs-dir", choices=[PREFS_DIR, BETA_PREFS_DIR],
                     help=f"force the source preferences folder "
                          f"(GOG/beta installs use {BETA_PREFS_DIR}/)")

    dst = ap.add_argument_group("destination (exactly one)")
    dst.add_argument("--dest-dir", help="local ux0:data/stsvita staging directory")
    dst.add_argument("--ftp-host", help="Vita IP running VitaShell FTP")
    dst.add_argument("--ftp-port", type=int, default=DEFAULT_FTP_PORT)
    dst.add_argument("--ftp-base", default=DEFAULT_FTP_BASE,
                     help=f"remote data directory (default: {DEFAULT_FTP_BASE})")

    beh = ap.add_argument_group("behaviour")
    beh.add_argument("--apply", action="store_true",
                     help="actually write (without this, nothing is modified)")
    beh.add_argument("--replace-slots", metavar="N,M",
                     help="overwrite these save slots even if they differ")
    beh.add_argument("--replace-all", action="store_true",
                     help="overwrite every slot present in the source")
    beh.add_argument("--backup-dir",
                     help="where to store the pre-import backup "
                          "(default: progress-backups/<timestamp>)")
    beh.add_argument("--rollback-from", metavar="DIR",
                     help="restore a previous backup instead of importing")
    return ap


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if not args.source and not args.auto and not args.rollback_from:
        print("error: pass --source <dir> or --auto", file=sys.stderr)
        return EXIT_USAGE
    try:
        return run(args)
    except KeyboardInterrupt:
        print("\ninterrupted", file=sys.stderr)
        return EXIT_TRANSFER


if __name__ == "__main__":
    sys.exit(main())
