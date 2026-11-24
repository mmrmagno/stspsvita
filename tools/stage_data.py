#!/usr/bin/env python3
"""Stage the folder a user copies to ux0:data on the Vita.

  out/data/stsvita/game.bin        executable built from the user's own jar
  out/data/stsvita/game.manifest   what the launcher checks before booting
  out/data/stsvita/assets.id       identity of the asset tree
  out/data/stsvita/files.sha256    per-file hashes, used by deploy_assets.py
  out/data/stsvita/loading.png
  out/data/stsvita/assets/
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / "pipeline"))

from stsvita_common import sha256_file  # noqa: E402

FORMAT = 1


def place(src: Path, dst: Path) -> None:
    dst.parent.mkdir(parents=True, exist_ok=True)
    try:
        os.link(src, dst)
    except OSError:
        shutil.copy2(src, dst)


def manifest_text(build_id: str, game: Path, assets_id: str) -> str:
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,63}", build_id):
        raise ValueError(f"bad build id: {build_id!r}")
    return (f"format={FORMAT}\nbuild_id={build_id}\ngame_size={game.stat().st_size}\n"
            f"game_sha256={sha256_file(game)}\nassets_id={assets_id}\n")


def stage(eboot: Path, assets: Path, loading: Path, out: Path,
          build_id: str, assets_id: str) -> Path:
    for path in (eboot, loading):
        if not path.is_file():
            raise FileNotFoundError(path)
    if not re.fullmatch(r"[0-9a-f]{64}", assets_id):
        raise ValueError(f"bad assets id: {assets_id!r}")
    data = out / "data/stsvita"
    if data.exists():
        shutil.rmtree(data)
    data.mkdir(parents=True)
    place(eboot, data / "game.bin")
    place(loading, data / "loading.png")
    lines = []
    for src in sorted(p for p in assets.rglob("*") if p.is_file()):
        rel = src.relative_to(assets).as_posix()
        place(src, data / "assets" / rel)
        lines.append(f"{sha256_file(src)}  assets/{rel}\n")
    if not lines:
        raise FileNotFoundError(f"no assets under {assets}")
    for name in ("game.bin", "loading.png"):
        lines.append(f"{sha256_file(data / name)}  {name}\n")
    (data / "assets.id").write_text(assets_id + "\n")
    (data / "game.manifest").write_text(
        manifest_text(build_id, data / "game.bin", assets_id))
    (data / "files.sha256").write_text("".join(lines))
    return data


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--build", type=Path, default=Path("build"))
    ap.add_argument("--work", type=Path, default=Path("work"))
    ap.add_argument("--out", type=Path, default=Path("out"))
    args = ap.parse_args()

    import json
    manifest_path = args.build / "build-manifest.json"
    if not manifest_path.is_file():
        print("stage: run the validate step first", file=sys.stderr)
        return 2
    built = json.loads(manifest_path.read_text())
    eboot = args.build / "native/eboot.bin"
    if not eboot.is_file() or sha256_file(eboot) != built["outputs"]["eboot"]["sha256"]:
        print("stage: eboot.bin does not match build-manifest.json", file=sys.stderr)
        return 1
    data = stage(eboot, args.work / "assets_vita",
                 args.work / "sce_sys/livearea/contents/loading.png", args.out,
                 built["build_id"], built["assets"]["tree_sha256"])
    print(f"staged: {data}  (copy the contents of {args.out} to ux0: on the Vita)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
