#!/usr/bin/env python3
"""Turn work/assets_raw into the Vita asset payload (work/assets_vita).

What it does:
  * drops fonts for languages you did not ask for (CJK fonts are 160MB —
    the single biggest size lever in the whole port)
  * optionally re-encodes OGG audio at a lower bitrate (needs ffmpeg)
  * copies everything else through unchanged

Texture DIMENSIONS as seen by the game are never changed: Slay the Spire
mixes draws sized from atlas regions / texel srcRects with draws sized from
constants * Settings.scale, so naive rescaling breaks layout. The half-res
.dds half-res pass is safe because the.dds header keeps the
ORIGINAL dims — texture.getWidth() and every UV fraction stay identical —
and only the encoded block data is half size (the upload glue infers the
actual GPU dims from the data size). GPU memory is otherwise reclaimed by
DXT compression at unchanged dimensions.

Usage: assets.py --work <work dir> [--langs eng,jpn] [--audio-kbps 32] [--jobs N]
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from stsvita_common import CJK_FONT_DIRS, DEFAULT_LANGS, sha256_file


def reencode_ogg(src: Path, dst: Path, kbps: int) -> None:
    tmp = dst.with_name(f".{dst.name}.stsvita-tmp.ogg")
    # libvorbis refuses managed bitrates below ~45kbps at 44.1kHz stereo
    # ("encoder setup failed"). The
    # 32kbps profile therefore resamples to 24kHz, which the encoder accepts
    # and which also halves the decoded PCM size of every preloaded SFX in
    # OpenAL's native-heap buffers. 48kbps stays at the source rate.
    cmd = ["ffmpeg", "-y", "-v", "error", "-i", str(src), "-c:a", "libvorbis"]
    if kbps <= 32:
        cmd += ["-ar", "24000"]
    cmd += ["-b:a", f"{kbps}k", str(tmp)]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0 or not tmp.is_file() or tmp.stat().st_size == 0:
        tmp.unlink(missing_ok=True)
        detail = res.stderr.strip().splitlines()[-1] if res.stderr else "no output"
        raise RuntimeError(
            f"ffmpeg failed to encode {src} at {kbps}kbps: {detail}"
        )
    tmp.replace(dst)


def build_dds_encoder(work: Path) -> Path:
    exe = work / "tools" / "ddsenc"
    src = Path("tools/ddsenc.c")
    deps = [src, Path("native/third_party/stb_image.h"), Path("native/third_party/stb_dxt.h")]
    if exe.exists() and exe.stat().st_mtime >= max(p.stat().st_mtime for p in deps):
        return exe
    exe.parent.mkdir(parents=True, exist_ok=True)
    res = subprocess.run(
        ["gcc", "-std=c11", "-O2", "-o", str(exe), str(src), "-lm"],
        capture_output=True, text=True,
    )
    if res.returncode != 0:
        sys.exit("error: failed to build DDS encoder:\n" + (res.stderr or res.stdout))
    return exe


# Half-res.dds: StS art is authored for 1920x1080 and the
# Vita renders 960x544 with Settings.scale = 0.5, so every draw lands at half
# the authored pixel size — half-res data IS native resolution on screen. The
#.dds header keeps the ORIGINAL (logical) dims — the game's layout/UV/srcRect
# math reads them via texture.getWidth() — while the encoded blocks are half
# size; stsv_tex_image_compressed infers the actual upload dims from the data
# size.;
# now applied to EVERYTHING /8-dimensioned (ddsenc -2 falls back to full-res
# otherwise). This is what lets the boot preload (cards/cardui/ui/title,
# ~130MB at full res) fit the vitaGL pools instead of dropping the late
# preloads (combat buttons, campfire UI, settings/map/reward panels,
# powers.png buff icons), and it cuts the on-demand loads (1024Portraits
# card popups, largeRelics, event art) 4x.


def dds_path_for(img: Path) -> Path:
    """.dds sibling name for an encoded image.

    PNGs keep the historical <stem>.dds. JPGs get <name>.jpg.dds: the scene
    atlases (bottomScene/cityScene/beyondScene, title) ship BOTH <stem>.png
    and <stem>.jpg as SEPARATE atlas pages with different content (the pngs
    carry the alpha overlay layers, and bottomScene/scene2.png is not even
    the same size as scene2.jpg). With a shared <stem>.dds the jpg encode
    overwrote the png's, so every region on a png page sampled the jpg's
    pixels — the 'mosaic of different places' backgrounds seen on device
    2026-07-12 once half-res let those pages actually upload.
    """
    if img.suffix == ".jpg":
        return img.parent / (img.name + ".dds")
    return img.with_suffix(".dds")


# Atlases whose REGION draws render blank at half-res on the Vita GXM
#: card frames/banners (cardui), the small
# card-art atlas (cards), and all combat/particle VFX (vfx). The atlas TEXTURE
# uploads and samples fine at half-res (the cardui CARD_BACK pile icon and the
# 2048 half-res title.atlas both render) — but the specific SpriteBatch draws
# these use (rotated/scaled TextureRegion draws for frames, additive blends
# for VFX) come out blank, while standalone-Texture draws, Spine monster
# atlases, and scene/UI atlases all render correctly at half-res. Root cause
# not pinned (not reproducible on the desktop-GL host; GXM-specific), so keep
# these three full-res. Memory is fine: earlier rounds ran ALL atlases full-res
# with these rendering, and everything else is half-res now, so this is
# strictly less resident texture memory than that known-good state.
# Textures that upload fine at half-res but render BLANK on the Vita GXM
# (device s3d/s3e) — the same half-res draw quirk as the card frames, NOT a
# memory drop. Full-res is the proven fix (cards). Kept surgical: only the
# small interactive UI + card/vfx atlases; big flat art (scene/portrait/
# monster/rug backgrounds) renders correctly at half and holds the saving.
HALF_RES_DENY = (
    "cardui/", "cards/", "vfx/",
    "images/ui/topPanel/", "images/ui/campfire/",
    "images/ui/reward/", "images/ui/option/",
)
# The map parchment pages are large standalone textures and rendered correctly
# in the global-half device run. Keeping the whole directory full-res made the
# four pages consume about 7MB when DungeonMapScreen opened, exhausting the
# bounded spill and blanking the map. The compact legend draw was reported
# missing at half-res, so retain full data for that one texture.
HALF_RES_DENY_PATHS = ("images/ui/map/legend2.png",)
# Button faces need full-size data on GXM, but their translucent shadow and
# outline layers can use native-screen-resolution data. This compensates for
# the critical UI boot spill without making the controls disappear again.
HALF_RES_FORCE_PATHS = (
    "images/ui/topPanel/cancelButtonShadow.png",
    "images/ui/topPanel/cancelButtonOutline.png",
    "images/ui/topPanel/confirmButtonShadow.png",
    "images/ui/topPanel/confirmButtonOutline.png",
    "images/ui/topPanel/proceedButtonShadow.png",
    "images/ui/topPanel/proceedButtonOutline.png",
)
# Quarter-data was tried for the five 2048x2048 City alpha overlays
# to fit the exhausted mid-run pool: all five were admitted, but
# hardware review rejected the result — half-data IS native
# screen resolution at Settings.scale=0.5, so quarter-data is a visible
# downgrade versus Exordium. The layers are back at the normal half-data
# scale; the admission headroom now comes from the 112MB-Java/240MB-newlib
# rebalance (larger vitaGL RAM pool) instead of a per-scene quality cut.
# The -4 encoder path and the loader's quarter-payload recognition remain
# available for any future non-visual-critical texture.
QUARTER_RES_FORCE_PATHS = ()
# Chest/shop interactables live as loose files under images/npcs/ alongside
# big backgrounds (rug, neow, heart) that DO render at half — so match by name.
HALF_RES_DENY_NAMES = ("Chest", "merchantObjects")


def wants_half_res(rel: str) -> bool:
    if rel in HALF_RES_FORCE_PATHS:
        return True
    if rel.startswith(HALF_RES_DENY) or rel in HALF_RES_DENY_PATHS:
        return False
    name = rel.rsplit("/", 1)[-1]
    if any(tok in name for tok in HALF_RES_DENY_NAMES):
        return False
    return True


def dds_scale(rel: str) -> int:
    if rel in QUARTER_RES_FORCE_PATHS:
        return 4
    return 2 if wants_half_res(rel) else 1


def encode_dds(job: tuple[Path, Path, Path, bool, int]) -> str:
    exe, src, dst, force, scale = job
    cmd = [str(exe), str(src), str(dst)]
    if force:
        cmd.append("-f")
    if scale > 1:
        cmd.append(f"-{scale}")
    res = subprocess.run(cmd, capture_output=True, text=True)
    while res.returncode == 3 and scale > 1:
        # Dimensions cannot support the requested scale: retry quarter-data
        # as half-data, then half-data as full-data.
        scale //= 2
        cmd.pop()
        if scale > 1:
            cmd.append(f"-{scale}")
        res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode == 0:
        return {4: "encoded-quarter", 2: "encoded-half"}.get(scale, "encoded")
    if res.returncode == 3:
        if dst.exists():
            dst.unlink()
        return "skipped"
    msg = (res.stderr or res.stdout).strip().splitlines()
    tail = msg[-1] if msg else "unknown error"
    print(f"WARNING: DDS conversion failed for {src}: {tail}")
    if dst.exists():
        dst.unlink()
    return "failed"


def next_pot(n: int) -> int:
    p = 1
    while p < n:
        p <<= 1
    return p


def pad_npot_atlas_pages(dst_root: Path, jobs: int) -> list[Path]:
    """Pad every non-power-of-two.atlas page to POT dimensions.

    The Vita GPU (GXM) mangles NPOT texture sampling — the Ironclad's
    611x113 page rendered him tiny/garbled until padded to 1024x128. Regions use absolute pixel coords
    and libGDX normalizes UVs by the real texture size, so extending the
    canvas bottom/right (transparent) is invisible except for fixing the
    sampling. The atlas 'size:' page lines are updated to match.
    Returns the padded page paths (they also get force-DDS'd: POT pages
    are DXT-compressible, a big VRAM win over NPOT RGBA).
    """
    tasks: list[tuple[Path, int, int]] = []
    for atlas in sorted(dst_root.rglob("*.atlas")):
        lines = atlas.read_text().splitlines()
        changed = False
        for i, line in enumerate(lines):
            # Page size lines are unindented; region size lines are indented.
            if not line.startswith("size:") or i == 0:
                continue
            page_name = lines[i - 1].strip()
            if not page_name or ":" in page_name:
                continue
            w, h = (int(v) for v in line[len("size:"):].strip().split(","))
            pw, ph = next_pot(w), next_pot(h)
            if (pw, ph) == (w, h):
                continue
            png = atlas.parent / page_name
            if not png.is_file():
                continue
            tasks.append((png, pw, ph))
            lines[i] = f"size: {pw},{ph}"
            changed = True
        if changed:
            atlas.write_text("\n".join(lines) + "\n")

    def pad(task: tuple[Path, int, int]) -> None:
        png, pw, ph = task
        subprocess.run(
            ["magick", str(png), "-background", "none", "-gravity", "NorthWest",
             "-extent", f"{pw}x{ph}", str(png)],
            check=True, capture_output=True)

    with ThreadPoolExecutor(max_workers=jobs) as ex:
        list(ex.map(pad, tasks))
    return [t[0] for t in tasks]


def stub_dds_bytes() -> bytes:
    """A 4x4 fully-transparent DXT1.dds (128B header + one 8B block).

    Matches the stub that was hand-uploaded to the device on 2026-07-11:
    oldCards atlas pages (~20MB, legacy card art the game never draws) are
    replaced with this to reclaim VRAM headroom on the Vita.
    """
    header = bytearray(128)
    header[0:4] = b"DDS "
    header[4:8] = (124).to_bytes(4, "little")           # dwSize
    header[12:16] = (4).to_bytes(4, "little")           # dwHeight
    header[16:20] = (4).to_bytes(4, "little")           # dwWidth
    header[76:80] = (32).to_bytes(4, "little")          # ddspf.dwSize
    header[84:88] = b"DXT1"                             # ddspf.dwFourCC
    return bytes(header) + bytes(8)                     # one zero DXT1 block


# Individual textures the game loads but never usefully draws on the Vita,
# stubbed like oldCards. unlockBlur.png = ImageMaster.UNLOCK_TEXT_BG, only
# drawn by NeowUnlockScreen (the one-time unlock cinematic); it is preloaded
# at boot and holds 253KB of pool for the whole session.
STUB_FILES = ("images/ui/unlockBlur.dds",)


def stub_dead_textures(dst_root: Path) -> int:
    stub = stub_dds_bytes()
    count = 0
    old_cards = dst_root / "oldCards"
    if old_cards.is_dir():
        for dds in sorted(old_cards.glob("*.dds")):
            dds.write_bytes(stub)
            count += 1
    for rel in STUB_FILES:
        dds = dst_root / rel
        if dds.is_file():
            dds.write_bytes(stub)
            count += 1
    return count


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--work", type=Path, default=Path("work"))
    ap.add_argument("--langs", default=DEFAULT_LANGS,
                    help="comma-separated game languages whose fonts to keep "
                         "(non-CJK fonts always ship; this matters for "
                         "zhs/zht/jpn/kor)")
    ap.add_argument("--audio-kbps", type=int, default=32,
                    help="re-encode OGGs at this bitrate "
                         "(default: 32, resampled to 24kHz; 48 = higher fidelity; 0 = copy through)")
    ap.add_argument("--no-dds", action="store_true",
                    help="do not generate DXT-compressed .dds siblings")
    ap.add_argument("--jobs", type=int, default=8)
    args = ap.parse_args()

    src_root = args.work / "assets_raw"
    dst_root = args.work / "assets_vita"
    if not src_root.is_dir():
        sys.exit("error: run extract.py first (work/assets_raw missing)")
    if dst_root.exists():
        shutil.rmtree(dst_root)

    langs = {l.strip() for l in args.langs.split(",") if l.strip()}
    dropped_font_dirs = CJK_FONT_DIRS - langs

    if args.audio_kbps and not shutil.which("ffmpeg"):
        sys.exit("error: --audio-kbps needs ffmpeg on PATH")

    copied = dropped = 0
    audio_jobs = []
    for src in sorted(src_root.rglob("*")):
        if src.is_dir():
            continue
        rel = src.relative_to(src_root)
        parts = rel.parts

        if parts[0] == "font" and len(parts) > 1 and parts[1] in dropped_font_dirs:
            dropped += 1
            continue

        dst = dst_root / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        if args.audio_kbps and src.suffix == ".ogg":
            audio_jobs.append((src, dst))
        else:
            shutil.copy2(src, dst)
        copied += 1

    if audio_jobs:
        with ThreadPoolExecutor(max_workers=args.jobs) as ex:
            list(ex.map(lambda p: reencode_ogg(*p, args.audio_kbps), audio_jobs))

    if not shutil.which("magick"):
        sys.exit("error: NPOT atlas-page padding needs ImageMagick on PATH")
    padded = pad_npot_atlas_pages(dst_root, args.jobs)

    dds_encoded = dds_half = dds_quarter = dds_skipped = dds_failed = 0
    if not args.no_dds:
        if not shutil.which("gcc"):
            sys.exit("error: DDS generation needs gcc on PATH")
        ddsenc = build_dds_encoder(args.work)
        padded_set = set(padded)
        # JPGs too: scene/menu/event art ships as.jpg (blueBg.jpg is the main
        # menu sky). Undecoded they cost seconds of stb decode on the 444MHz
        # CPU (the 40s "New Main Menu Screen" stall) plus a 9MB RGBA upload.
        images = sorted(dst_root.rglob("*.png")) + sorted(dst_root.rglob("*.jpg"))
        # Padded atlas pages are force-encoded: ddsenc's default min-size rule
        # would skip flat pages (e.g. 1024x128), but POT skeleton pages are
        # exactly the NPOT-RGBA hogs the padding exists to compress.
        jobs = [(ddsenc, p, dds_path_for(p), p in padded_set,
                 dds_scale(str(p.relative_to(dst_root)).replace("\\", "/")))
                for p in images]
        with ThreadPoolExecutor(max_workers=args.jobs) as ex:
            for result in ex.map(encode_dds, jobs):
                if result == "encoded":
                    dds_encoded += 1
                elif result == "encoded-half":
                    dds_half += 1
                elif result == "encoded-quarter":
                    dds_quarter += 1
                elif result == "skipped":
                    dds_skipped += 1
                else:
                    dds_failed += 1

    pruned = 0
    if not args.no_dds:
        # A.png/.jpg with a.dds sibling is dead weight: VitaFileHandle (and
        # the host harness) always prefer the.dds. Dropping them keeps the
        # payload (and the memory card) hundreds of MB smaller.
        for ext in ("*.png", "*.jpg"):
            for img in list(dst_root.rglob(ext)):
                if dds_path_for(img).is_file():
                    img.unlink()
                    pruned += 1

    stubbed = 0
    if not args.no_dds:
        stubbed = stub_dead_textures(dst_root)

    def tree_mb(p: Path) -> float:
        return sum(f.stat().st_size for f in p.rglob("*") if f.is_file()) / 1048576

    print(f"assets: {copied} files -> {dst_root} ({tree_mb(dst_root):.1f} MB, "
          f"was {tree_mb(src_root):.1f} MB); "
          f"dropped {dropped} font files for languages not in {sorted(langs)}")
    if not args.no_dds:
        print(f"dds: encoded {dds_encoded} (+{dds_half} half-res, "
              f"+{dds_quarter} quarter-res), "
              f"skipped {dds_skipped}, failed {dds_failed}; "
              f"padded {len(padded)} NPOT atlas pages; pruned {pruned} dds-paired pngs; "
              f"dead textures stubbed to 4x4: {stubbed}")

    jar = Path((args.work / "jar_path.txt").read_text().strip()).resolve()
    metadata = {
        "schema_version": 1,
        "jar_path": str(jar),
        "jar_sha256": sha256_file(jar),
        "languages": sorted(langs),
        "audio_kbps": args.audio_kbps,
        "audio_converted": len(audio_jobs),
        "dds": not args.no_dds,
        "dds_quarter_res_paths": list(QUARTER_RES_FORCE_PATHS),
    }
    (args.work / "assets-vita-metadata.json").write_text(
        json.dumps(metadata, indent=2, sort_keys=True) + "\n"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
