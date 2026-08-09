#!/usr/bin/env python3
"""Validate canonical STSVITA outputs and emit build/build-manifest.json."""

from __future__ import annotations

import argparse
import binascii
import collections
import datetime as dt
import hashlib
import json
import os
import subprocess
import sys
import zipfile
from pathlib import Path, PurePosixPath

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / "pipeline"))

from stsvita_common import KNOWN_JARS, find_jar, sha256_file  # noqa: E402


REQUIRED_VPK_FILES = {
    "eboot.bin",
    "loading.png",
    "sce_sys/param.sfo",
    "sce_sys/icon0.png",
    "sce_sys/livearea/contents/bg0.png",
    "sce_sys/livearea/contents/startup.png",
    "sce_sys/livearea/contents/loading.png",
    "sce_sys/livearea/contents/template.xml",
}


class ValidationError(RuntimeError):
    pass


def run_text(*args: str) -> str:
    proc = subprocess.run(args, cwd=REPO, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, check=False)
    if proc.returncode:
        return f"unavailable (exit {proc.returncode})"
    return proc.stdout.rstrip()


def first_line(text: str) -> str:
    return next((line.strip() for line in text.splitlines() if line.strip()),
                "unavailable")


def version_line(text: str, prefix: str) -> str:
    return next((line.strip() for line in text.splitlines()
                 if line.strip().startswith(prefix)), first_line(text))


def file_crc32(path: Path) -> int:
    value = 0
    with path.open("rb") as src:
        for chunk in iter(lambda: src.read(1 << 20), b""):
            value = binascii.crc32(chunk, value)
    return value & 0xFFFFFFFF


def asset_inventory(root: Path) -> tuple[dict[str, Path], dict[str, object]]:
    if not root.is_dir():
        raise ValidationError(f"asset tree missing: {root}")
    files = {
        path.relative_to(root).as_posix(): path
        for path in sorted(root.rglob("*")) if path.is_file()
    }
    if not files:
        raise ValidationError(f"asset tree is empty: {root}")

    suffixes: collections.Counter[str] = collections.Counter()
    total_bytes = 0
    tree_hash = hashlib.sha256()
    for rel, path in files.items():
        size = path.stat().st_size
        suffix = Path(rel).suffix.lower() or "<none>"
        suffixes[suffix] += 1
        total_bytes += size
        digest = sha256_file(path)
        tree_hash.update(rel.encode("utf-8") + b"\0")
        tree_hash.update(str(size).encode("ascii") + b"\0")
        tree_hash.update(digest.encode("ascii") + b"\n")

    # assets.py removes source images whenever their runtime DDS replacement
    # exists. JPG-derived replacements intentionally use <name>.jpg.dds to
    # avoid colliding with a distinct same-stem PNG atlas page.
    names = set(files)
    sibling_errors = []
    for rel in sorted(names):
        if rel.endswith(".jpg.dds") and rel[:-4] in names:
            sibling_errors.append(f"both {rel[:-4]} and {rel}")
        elif rel.endswith(".dds") and not rel.endswith(".jpg.dds"):
            png = rel[:-4] + ".png"
            if png in names:
                sibling_errors.append(f"both {png} and {rel}")
    if sibling_errors:
        sample = "; ".join(sibling_errors[:8])
        raise ValidationError(f"stale PNG/JPG + DDS siblings: {sample}")
    if suffixes[".dds"] == 0:
        raise ValidationError("processed asset tree contains no DDS files")

    return files, {
        "files": len(files),
        "bytes": total_bytes,
        "by_extension": dict(sorted(suffixes.items())),
        "tree_sha256": tree_hash.hexdigest(),
        "png_dds_sibling_policy": "passed",
    }


def read_vpk(path: Path) -> tuple[zipfile.ZipFile, dict[str, zipfile.ZipInfo]]:
    if not path.is_file():
        raise ValidationError(f"missing package: {path}")
    try:
        archive = zipfile.ZipFile(path)
        infos = archive.infolist()
        bad = archive.testzip()
    except (OSError, zipfile.BadZipFile) as exc:
        raise ValidationError(f"unreadable VPK {path}: {exc}") from exc
    if bad:
        archive.close()
        raise ValidationError(f"VPK CRC failure in {path}: {bad}")
    names = [info.filename for info in infos]
    if len(names) != len(set(names)):
        archive.close()
        raise ValidationError(f"duplicate archive entries in {path}")
    return archive, {info.filename: info for info in infos}


def validate_required(path: Path, infos: dict[str, zipfile.ZipInfo]) -> None:
    missing = sorted(REQUIRED_VPK_FILES - set(infos))
    if missing:
        raise ValidationError(f"{path} lacks required files: {missing}")
    for name in REQUIRED_VPK_FILES:
        if infos[name].file_size == 0:
            raise ValidationError(f"{path} contains empty required file: {name}")


def newest_mtime(paths: list[Path]) -> float:
    files = []
    for path in paths:
        if path.is_dir():
            files.extend(child for child in path.rglob("*") if child.is_file())
        elif path.is_file():
            files.append(path)
    return max((path.stat().st_mtime for path in files), default=0.0)


def validate_generated_inputs(work: Path, jar: Path, jar_hash: str, assets: Path,
                              langs: list[str], audio_kbps: int) -> None:
    pointer = work / "jar_path.txt"
    if not pointer.is_file():
        raise ValidationError(f"missing generated input pointer: {pointer}")
    recorded = Path(pointer.read_text().strip()).resolve()
    if recorded != jar.resolve():
        raise ValidationError(
            f"stale generated input: jar_path.txt names {recorded}, requested {jar}"
        )

    asset_metadata_path = work / "assets-vita-metadata.json"
    if not asset_metadata_path.is_file():
        raise ValidationError(f"missing asset build metadata: {asset_metadata_path}")
    try:
        asset_metadata = json.loads(asset_metadata_path.read_text())
    except (OSError, json.JSONDecodeError) as exc:
        raise ValidationError(f"invalid asset build metadata: {exc}") from exc
    expected_asset_metadata = {
        "jar_path": str(jar.resolve()),
        "jar_sha256": jar_hash,
        "languages": langs,
        "audio_kbps": audio_kbps,
        "audio_converted": (
            sum(1 for path in assets.rglob("*.ogg") if path.is_file())
            if audio_kbps else 0
        ),
        "dds": True,
        # Quarter-data City layers were reverted 2026-07-17 (visual reject):
        # the set must be empty so a stale quarter-encoded tree fails here.
        "dds_quarter_res_paths": [],
    }
    mismatches = {
        key: (asset_metadata.get(key), value)
        for key, value in expected_asset_metadata.items()
        if asset_metadata.get(key) != value
    }
    if mismatches:
        raise ValidationError(f"stale asset tree metadata: {mismatches}")
    asset_inputs = [
        REPO / "pipeline/assets.py",
        REPO / "pipeline/stsvita_common.py",
        REPO / "tools/ddsenc.c",
        REPO / "native/third_party/stb_image.h",
        REPO / "native/third_party/stb_dxt.h",
    ]
    if newest_mtime(asset_inputs) > asset_metadata_path.stat().st_mtime:
        raise ValidationError("asset pipeline sources are newer than the staged asset tree")

    generated = work / "generated_c"
    all_txt = generated / "all.txt"
    if not all_txt.is_file():
        raise ValidationError(f"missing TeaVM source list: {all_txt}")
    listed = [line.strip() for line in all_txt.read_text().splitlines() if line.strip()]
    if not listed:
        raise ValidationError(f"empty TeaVM source list: {all_txt}")
    missing = []
    for rel in listed:
        pure = PurePosixPath(rel)
        if pure.is_absolute() or ".." in pure.parts or not (generated / rel).is_file():
            missing.append(rel)
    if missing:
        raise ValidationError(f"stale TeaVM source list; missing/unsafe entries: {missing[:8]}")
    transpile_inputs = [
        REPO / "backend",
        REPO / "transpiler/src",
        REPO / "transpiler/build.gradle",
        REPO / "pipeline/typemanifest.py",
        REPO / "tools/vita_patch_runtime.py",
    ]
    if newest_mtime(transpile_inputs) > all_txt.stat().st_mtime:
        raise ValidationError("backend/transpiler/runtime-patch sources are newer than generated C")

    livearea = work / "sce_sys/icon0.png"
    if not livearea.is_file():
        raise ValidationError(f"missing generated LiveArea icon: {livearea}")
    if (REPO / "pipeline/livearea.py").stat().st_mtime > livearea.stat().st_mtime:
        raise ValidationError("LiveArea pipeline source is newer than generated LiveArea files")

    cache = REPO / "build/native/CMakeCache.txt"
    if cache.is_file():
        cache_text = cache.read_text(errors="replace")
        expected_generated = f"GENERATED_C:PATH={generated.resolve()}"
        expected_assets = f"ASSETS:PATH={assets.resolve()}"
        if expected_generated not in cache_text or expected_assets not in cache_text:
            raise ValidationError("native CMake cache points at stale generated-C/assets paths")

    tracked = run_text("git", "ls-files", "--", "work", "build")
    if tracked and not tracked.startswith("unavailable"):
        raise ValidationError(f"generated content is tracked by git: {tracked.splitlines()[:8]}")


def tool_versions(audio_kbps: int) -> dict[str, str]:
    versions = {
        "python": sys.version.splitlines()[0],
        "java": first_line(run_text("java", "-version")),
        "gradle": version_line(run_text("gradle", "--version"), "Gradle "),
        "cmake": version_line(run_text("cmake", "--version"), "cmake version "),
        "imagemagick": first_line(run_text("magick", "-version")),
        "vita_gcc": first_line(run_text("arm-vita-eabi-gcc", "--version")),
    }
    if audio_kbps:
        versions["ffmpeg"] = first_line(run_text("ffmpeg", "-version"))
    return versions


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--sts-input", type=Path, required=True,
                    help="game install directory or desktop-1.0.jar")
    ap.add_argument("--work", type=Path, default=Path("work"))
    ap.add_argument("--build", type=Path, default=Path("build"))
    ap.add_argument("--langs", default="eng")
    ap.add_argument("--audio-kbps", type=int, default=32)
    ap.add_argument("--slim-only", action="store_true")
    args = ap.parse_args()

    work = args.work.resolve()
    build = args.build.resolve()
    jar = find_jar(args.sts_input)
    jar_hash = sha256_file(jar)
    assets = work / "assets_vita"
    langs = sorted({lang.strip() for lang in args.langs.split(",") if lang.strip()})

    try:
        staged, asset_stats = asset_inventory(assets)
        validate_generated_inputs(work, jar, jar_hash, assets, langs, args.audio_kbps)

        slim_path = build / "STSVITA-slim.vpk"
        slim, slim_infos = read_vpk(slim_path)
        try:
            validate_required(slim_path, slim_infos)
            bundled_in_slim = sorted(n for n in slim_infos if n.startswith("assets/"))
            if bundled_in_slim:
                raise ValidationError(
                    f"slim package unexpectedly bundles assets: {bundled_in_slim[:8]}"
                )
            slim_eboot = slim.read("eboot.bin")
        finally:
            slim.close()

        outputs: dict[str, dict[str, object]] = {
            "slim_vpk": {
                "path": str(slim_path.relative_to(REPO)),
                "bytes": slim_path.stat().st_size,
                "sha256": sha256_file(slim_path),
                "entries": len(slim_infos),
            },
            "eboot": {
                "bytes": len(slim_eboot),
                "sha256": hashlib.sha256(slim_eboot).hexdigest(),
            },
        }

        if not args.slim_only:
            full_path = build / "STSVITA.vpk"
            full, full_infos = read_vpk(full_path)
            try:
                validate_required(full_path, full_infos)
                expected = {f"assets/{rel}" for rel in staged}
                actual = {name for name in full_infos
                          if name.startswith("assets/") and not name.endswith("/")}
                missing = sorted(expected - actual)
                extra = sorted(actual - expected)
                if missing or extra:
                    raise ValidationError(
                        "full package asset set differs from staging: "
                        f"missing={missing[:8]} extra={extra[:8]}"
                    )
                for rel, source in staged.items():
                    info = full_infos[f"assets/{rel}"]
                    size = source.stat().st_size
                    if info.file_size != size or info.CRC != file_crc32(source):
                        raise ValidationError(f"full package asset differs from staging: {rel}")
                full_eboot = full.read("eboot.bin")
            finally:
                full.close()
            if full_eboot != slim_eboot:
                raise ValidationError("full and slim packages use different eboot.bin bytes")
            outputs["full_vpk"] = {
                "path": str(full_path.relative_to(REPO)),
                "bytes": full_path.stat().st_size,
                "sha256": sha256_file(full_path),
                "entries": len(full_infos),
                "staged_assets_verified": len(staged),
            }

        native_eboot = build / "native/eboot.bin"
        if native_eboot.is_file() and sha256_file(native_eboot) != outputs["eboot"]["sha256"]:
            raise ValidationError("packaged executable differs from build/native/eboot.bin")

        revision = run_text("git", "rev-parse", "HEAD")
        status_text = run_text("git", "status", "--porcelain=v1", "--untracked-files=all")
        dirty_paths = [] if not status_text else status_text.splitlines()
        if dirty_paths and dirty_paths[0].startswith("unavailable"):
            dirty_paths = [dirty_paths[0]]
        short_revision = revision[:12] if len(revision) >= 12 else revision
        build_id_hash = outputs["slim_vpk"]["sha256"]
        build_id = f"{short_revision}{'-dirty' if dirty_paths else ''}-{build_id_hash[:12]}"
        manifest = {
            "schema_version": 1,
            "build_id": build_id,
            "generated_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
            "mode": "incremental-slim" if args.slim_only else "clean-release",
            "repository": {
                "revision": revision,
                "dirty": bool(dirty_paths),
                "status": dirty_paths,
            },
            "game_input": {
                "path": str(jar),
                "bytes": jar.stat().st_size,
                "sha256": jar_hash,
                "supported": jar_hash in KNOWN_JARS,
                "description": KNOWN_JARS.get(jar_hash),
            },
            "options": {
                "languages": langs,
                "audio_kbps": args.audio_kbps,
            },
            "tools": tool_versions(args.audio_kbps),
            "assets": asset_stats,
            "outputs": outputs,
            "validation": {
                "archives_readable": True,
                "required_files": True,
                "full_assets_match_staging": None if args.slim_only else True,
                "slim_excludes_assets": True,
                "identical_executable": None if args.slim_only else True,
                "generated_outputs": True,
                "tracked_game_content": False,
            },
        }
        build.mkdir(parents=True, exist_ok=True)
        manifest_path = build / "build-manifest.json"
        tmp = manifest_path.with_suffix(".json.tmp")
        tmp.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
        os.replace(tmp, manifest_path)
    except ValidationError as exc:
        print(f"validate: ERROR: {exc}", file=sys.stderr)
        return 1

    print(f"validate: OK ({'slim' if args.slim_only else 'full + slim'}, "
          f"{asset_stats['files']} staged assets)")
    print(f"manifest: {manifest_path} (build_id {build_id})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
