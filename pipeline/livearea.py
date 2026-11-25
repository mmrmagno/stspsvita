#!/usr/bin/env python3
"""Generate the Vita LiveArea assets (bubble icon, background, start gate) from
the user's own game files - same pipeline model as the rest of the assets:
nothing game-derived is committed to the repo, everything is produced at build
time into work/sce_sys/ and packed into SlayTheSpire.vpk.

Sources (from the processed payload in work/assets_vita):
  title/title.jpg                     -> background scene
  images/ui/title_logo/<lang>.png     -> "Slay the Spire" logo

Outputs (work/sce_sys/):
  icon0.png                              128x128  home-screen bubble
  livearea/contents/bg0.png              840x500  LiveArea background
  livearea/contents/startup.png          280x158  the "start game" gate image
  livearea/contents/template.xml         style "a1" layout

Requires ImageMagick (`magick`). Run via `make livearea`.
"""
import argparse
import shutil
import subprocess
import sys
from pathlib import Path

TEMPLATE_XML = """<?xml version="1.0" encoding="utf-8" standalone="yes"?>
<livearea style="a1" format-ver="01.00" content-rev="1">
  <livearea-background>
    <image>bg0.png</image>
  </livearea-background>
  <gate>
    <startup-image>startup.png</startup-image>
  </gate>
</livearea>
"""


def magick(*args: str) -> None:
    subprocess.run(["magick", *args], check=True)


def to_png8(path: Path) -> None:
    """Convert a PNG to 8-bit palettized in place. The Vita package installer
    rejects 24/32-bit PNGs in sce_sys with error 0x8010113D - LiveArea images
    must be 8-bit. pngquant preserves soft alpha in the palette; fall back to
    ImageMagick's PNG8 (binary transparency only) if it's unavailable."""
    tmp = path.with_suffix(".8bit.png")
    if shutil.which("pngquant"):
        subprocess.run(["pngquant", "--force", "--output", str(tmp), "256", "--", str(path)],
                       check=True)
        tmp.replace(path)
    else:
        magick(str(path), f"PNG8:{path}")


def pick(*candidates: Path) -> Path:
    for c in candidates:
        if c.exists():
            return c
    raise SystemExit(f"none of these source images exist: {[str(c) for c in candidates]}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", default="work")
    ap.add_argument("--lang", default="eng", help="title-logo language (title_logo/<lang>.png)")
    args = ap.parse_args()

    if not shutil.which("magick"):
        print("ImageMagick (`magick`) not found - needed to build LiveArea assets", file=sys.stderr)
        return 2

    # LiveArea art needs the original PNG/JPG inputs. The Vita asset pass may
    # replace those files with DDS siblings, while assets_raw remains intact.
    assets = Path(args.work) / "assets_raw"
    bg_src = pick(assets / "title" / "title.jpg", assets / "title" / "title.png")
    logo_dir = assets / "images" / "ui" / "title_logo"
    logo_src = pick(logo_dir / f"{args.lang}.png", logo_dir / "eng.png",
                    *sorted(logo_dir.glob("*.png")))

    out = Path(args.work) / "sce_sys"
    contents = out / "livearea" / "contents"
    contents.mkdir(parents=True, exist_ok=True)

    bg0 = contents / "bg0.png"
    startup = contents / "startup.png"
    loading = contents / "loading.png"
    icon0 = out / "icon0.png"

    # title.jpg is a 2048² atlas page: the scene fills the top-left and the
    # rest is black padding. Trim it off first (a 1px black border makes every
    # corner black so -trim removes the padding from whichever sides it's on).
    scene = out / "_scene.png"
    magick(str(bg_src), "-bordercolor", "black", "-border", "1",
           "-fuzz", "3%", "-trim", "+repage", str(scene))

    # LiveArea background: cover-fit the trimmed scene to 840x500.
    magick(str(scene), "-resize", "840x500^", "-gravity", "center",
           "-extent", "840x500", str(bg0))

    # Start gate: the logo centred on a transparent 280x158 frame.
    magick("-size", "280x158", "xc:none",
           "(", str(logo_src), "-resize", "260x140", ")",
           "-gravity", "center", "-composite", str(startup))

    # Native boot loading screen: replace vitaGL's spinning logo with the
    # game's own title scene while TeaVM and CardCrawlGame preload. Keep this
    # derived from the user's game files, just like the LiveArea artwork, so
    # no Mega Crit content is stored in the repository.
    magick(str(scene), "-resize", "960x544^", "-gravity", "center",
           "-extent", "960x544", "-modulate", "48,72,100",
           "-fill", "rgba(5,8,12,0.24)", "-draw", "rectangle 0,0 960,544",
           "(", str(logo_src), "-resize", "430x243", ")",
           "-gravity", "center", "-geometry", "+0-42", "-composite",
           "-stroke", "#d6b55f", "-strokewidth", "1", "-fill", "none",
           "-draw", "line 354,464 438,464 line 522,464 606,464",
           "-stroke", "none", "-fill", "#8f7438",
           "-draw", "polygon 450,464 455,459 460,464 455,469 "
                    "polygon 500,464 505,459 510,464 505,469",
           "-fill", "#f1d77d",
           "-draw", "polygon 475,464 480,457 485,464 480,471",
           str(loading))

    # Bubble icon: a darkened square crop of the scene with the logo on top.
    magick(str(scene), "-resize", "128x128^", "-gravity", "center",
           "-extent", "128x128", "-modulate", "80",
           "(", str(logo_src), "-resize", "116x92", ")",
           "-gravity", "center", "-composite", str(icon0))

    scene.unlink()

    # The Vita packager requires 8-bit LiveArea PNGs (else install fails with
    # 0x8010113D). Quantize the generated images.
    for p in (icon0, bg0, startup, loading):
        to_png8(p)

    (contents / "template.xml").write_text(TEMPLATE_XML)

    print(f"[livearea] bg   <- {bg_src.relative_to(assets)}")
    print(f"[livearea] logo <- {logo_src.relative_to(assets)}")
    print(f"[livearea] wrote {icon0}, {bg0}, {startup}, {loading}, template.xml")
    return 0


if __name__ == "__main__":
    sys.exit(main())
