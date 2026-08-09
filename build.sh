#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
cd "$ROOT"

STS_INPUT=
LANGS=eng
AUDIO_KBPS=32
INCREMENTAL=0
ALLOW_UNSUPPORTED=0

usage() {
    cat <<'EOF'
Usage: ./build.sh --sts-dir <install-dir> [options]
       ./build.sh --sts-jar <desktop-1.0.jar> [options]

Build a clean, validated full VPK, slim VPK, external asset tree, and manifest.

Options:
  --langs <list>       Comma-separated game languages to retain (default: eng)
  --audio-kbps <rate>  Re-encode OGG audio (default: 32 at 24kHz; 48 for higher fidelity; 0 copies unchanged)
  --incremental        Reuse assets/generated C and rebuild only the slim VPK
  --allow-unsupported-input
                       Contributor-only override for an unknown jar fingerprint
  -h, --help           Show this help
EOF
}

die() {
    printf 'error: %s\n' "$*" >&2
    exit 2
}

need_command() {
    command -v "$1" >/dev/null 2>&1 || die "$2"
}

while (($#)); do
    case "$1" in
        --sts-dir|--sts-jar)
            (($# >= 2)) || die "$1 needs a path"
            STS_INPUT=$2
            shift 2
            ;;
        --langs)
            (($# >= 2)) || die "--langs needs a comma-separated list"
            LANGS=$2
            shift 2
            ;;
        --audio-kbps)
            (($# >= 2)) || die "--audio-kbps needs a non-negative integer"
            AUDIO_KBPS=$2
            shift 2
            ;;
        --incremental)
            INCREMENTAL=1
            shift
            ;;
        --allow-unsupported-input)
            ALLOW_UNSUPPORTED=1
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            die "unknown option: $1 (use --help)"
            ;;
    esac
done

[[ -n "$STS_INPUT" ]] || die "provide --sts-dir or --sts-jar"
[[ "$AUDIO_KBPS" =~ ^[0-9]+$ ]] || die "--audio-kbps must be a non-negative integer"

# Common VitaSDK installs live here; an explicit VITASDK always wins.
if [[ -z "${VITASDK:-}" && -f "$HOME/vitasdk/share/vita.toolchain.cmake" ]]; then
    export VITASDK="$HOME/vitasdk"
fi
[[ -n "${VITASDK:-}" ]] || die "VITASDK is not set; install VitaSDK from https://vitasdk.org"
[[ -f "$VITASDK/share/vita.toolchain.cmake" ]] || die "VitaSDK CMake toolchain not found under $VITASDK"
export PATH="$VITASDK/bin:$PATH"

need_command make "GNU Make is required"
need_command python3 "Python 3.9 or newer is required"
need_command java "JDK 17 or newer is required"
need_command gradle "Gradle is required"
need_command cmake "CMake 3.16 or newer is required"
need_command magick "ImageMagick 7 ('magick') is required"
need_command arm-vita-eabi-gcc "VitaSDK compiler arm-vita-eabi-gcc was not found"
if ((AUDIO_KBPS > 0)); then
    need_command ffmpeg "ffmpeg is required when --audio-kbps is nonzero"
fi

python3 -c 'import sys; raise SystemExit(sys.version_info < (3, 9))' || \
    die "Python 3.9 or newer is required"
JAVA_MAJOR=$(java -version 2>&1 | sed -n '1s/.*version "\([0-9][0-9]*\).*/\1/p')
[[ -n "$JAVA_MAJOR" && "$JAVA_MAJOR" -ge 17 ]] || die "JDK 17 or newer is required"
gradle --version >/dev/null || die "Gradle is installed but could not start"
cmake --version >/dev/null || die "CMake is installed but could not start"
magick -version >/dev/null || die "ImageMagick is installed but could not start"
arm-vita-eabi-gcc --version >/dev/null || die "VitaSDK compiler could not start"

MAKE_ARGS=("STS_DIR=$STS_INPUT" "LANGS=$LANGS" "AUDIO_KBPS=$AUDIO_KBPS")
if ((ALLOW_UNSUPPORTED)); then
    MAKE_ARGS+=("ALLOW_UNSUPPORTED=1")
fi

if ((INCREMENTAL)); then
    make incremental-slim "${MAKE_ARGS[@]}"
else
    # Session logs are intentionally preserved by clean-build.
    make clean-build
    make release "${MAKE_ARGS[@]}"
fi
