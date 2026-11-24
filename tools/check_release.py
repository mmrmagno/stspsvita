#!/usr/bin/env python3
"""Fail if a release could contain anything that is not ours to publish."""
import argparse
import re
import subprocess
import sys
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
ART = REPO / "launcher"
VPK_FILES = {
    "eboot.bin",
    "sce_sys/param.sfo",
    "sce_sys/icon0.png",
    "sce_sys/livearea/contents/bg0.png",
    "sce_sys/livearea/contents/startup.png",
    "sce_sys/livearea/contents/template.xml",
}
MAX_EBOOT = 512 * 1024
BAD_SUFFIXES = {".jar", ".vpk", ".bin", ".self", ".dds", ".ogg", ".wav", ".atlas",
                ".ttf", ".otf", ".class", ".psp2dmp", ".log", ".autosave"}
GAME_MARKERS = (b"megacrit", b"cardcrawl", b"teavm")
PRIVATE_TEXT = re.compile(r"/home/(?!you\b)[a-z]+/|192\.168\.0\.\d+|HANDOFF")


def check_vpk(path: Path) -> list[str]:
    problems = []
    with zipfile.ZipFile(path) as z:
        names = {n for n in z.namelist() if not n.endswith("/")}
        if names != VPK_FILES:
            problems.append(f"{path.name}: unexpected {sorted(names - VPK_FILES)}, "
                            f"missing {sorted(VPK_FILES - names)}")
        if "eboot.bin" in names and z.getinfo("eboot.bin").file_size > MAX_EBOOT:
            problems.append(f"{path.name}: eboot.bin is too large for the launcher")
        for name in sorted(names & VPK_FILES):
            ours = ART / name
            if ours.is_file() and z.read(name) != ours.read_bytes():
                problems.append(f"{path.name}: {name} is not the art in the repo")
    return problems


def check_elf(path: Path) -> list[str]:
    data = path.read_bytes().lower()
    return [f"{path.name}: contains {m.decode()}" for m in GAME_MARKERS if m in data]


def check_tree(root: Path, names: list[str]) -> list[str]:
    problems = []
    for name in names:
        path = root / name
        if not path.is_file():
            continue
        if path.suffix.lower() in BAD_SUFFIXES:
            problems.append(f"{name}: file type must not be in the repo")
        if name == "tools/check_release.py":
            continue
        text = path.read_bytes().decode("utf-8", "ignore")
        found = PRIVATE_TEXT.search(text)
        if found:
            problems.append(f"{name}: private text {found.group(0)!r}")
    return problems


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--vpk", type=Path)
    ap.add_argument("--elf", type=Path, help="unpacked launcher ELF")
    args = ap.parse_args()
    out = subprocess.run(["git", "ls-files", "-co", "--exclude-standard"], cwd=REPO,
                         capture_output=True, text=True, check=True).stdout
    problems = check_tree(REPO, out.splitlines())
    if args.vpk:
        problems += check_vpk(args.vpk)
    if args.elf:
        problems += check_elf(args.elf)
    for p in problems:
        print(f"release check: {p}", file=sys.stderr)
    if not problems:
        print("release check: OK")
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
