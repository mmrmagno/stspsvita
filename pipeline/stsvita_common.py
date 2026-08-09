"""Shared constants and helpers for the STSVITA build pipeline."""

from __future__ import annotations

import hashlib
import sys
import zipfile
from pathlib import Path

JAR_NAME = "desktop-1.0.jar"

# Known-good jars this pipeline has been developed against.
# Other builds usually work; verify.py warns rather than refuses.
KNOWN_JARS = {
    # sha256 -> human-readable description; extended as contributors report
    # fingerprints from other platforms/storefronts
    "cfad868ac8d65a88e71a0bf096fb09f78811e553effe0787c5309a655e081673":
        "Steam Linux, V2.3.4 (jar dated 2022-12-20, libGDX 1.9.5)",
}

# Top-level jar directories that are game assets (everything the VPK ships).
ASSET_DIRS = {
    "achievements", "audio", "beyondScene", "bottomScene", "cards", "cardui",
    "changelog", "cityScene", "endingScene", "font", "images", "localization",
    "oldCards", "orbs", "powers", "shaders", "title", "vfx",
}

# Loose classpath files the game/libGDX loads at runtime.
ASSET_LOOSE_FILES = {
    "default.fnt", "default.png", "particle.png", "pre_particle.png",
    "splash.jpg", "uiskin.atlas", "uiskin.json", "uiskin.png", "sfd.ser",
    "build.properties",
}

# Code package roots (compiled by TeaVM straight from the jar, never extracted).
CODE_DIRS = {"com", "org", "net", "de", "javazoom", "META-INF"}

# Desktop-only native library baggage.
NATIVE_DIRS = {"win-x86", "win-x64", "linux", "darwin"}
NATIVE_EXTS = {".dll", ".so", ".dylib", ".jnilib", ".a"}

# font/<dir> needed per game language. Languages not listed use the base
# (latin) fonts that always ship.
CJK_FONT_DIRS = {"zhs", "zht", "jpn", "kor"}

DEFAULT_LANGS = "eng"


def find_jar(sts_dir: Path) -> Path:
    # The public build interface accepts either the installation directory or
    # the jar itself. Keeping this normalization here means verify/extract and
    # every Make-based entry point resolve the exact same input.
    jar = sts_dir if sts_dir.is_file() else sts_dir / JAR_NAME
    if not jar.is_file():
        sys.exit(
            f"error: {jar} not found.\n"
            f"Point the build at your Slay the Spire install directory "
            f"(the folder containing {JAR_NAME}) or at {JAR_NAME} itself."
        )
    return jar.resolve()


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def is_asset(name: str) -> bool:
    """True for jar entries that ship in the VPK's asset payload."""
    if name.endswith("/"):
        return False
    top = name.split("/", 1)[0]
    if "/" in name:
        return top in ASSET_DIRS
    if Path(name).suffix.lower() in NATIVE_EXTS:
        return False
    return name in ASSET_LOOSE_FILES


def open_jar(jar: Path) -> zipfile.ZipFile:
    try:
        return zipfile.ZipFile(jar)
    except zipfile.BadZipFile:
        sys.exit(f"error: {jar} is not a valid jar/zip file")
