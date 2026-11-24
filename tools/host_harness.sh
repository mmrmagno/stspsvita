#!/bin/sh
# Build and run the desktop-C debugging harness: the transpiled game running
# natively on the build machine (headless glue), for debugging fibers/GC/
# gson/file-IO before touching Vita hardware.
#
# Prereqs: `make assets` and `make transpile` have been run.
# Usage: tools/host_harness.sh [--run-only]
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GEN="$ROOT/work/generated_c"
OBJS="$ROOT/work/host_objs"
BIN="$ROOT/work/stsvita_host"
HARNESS="$ROOT/work/harness"

[ -d "$GEN" ] || { echo "run 'make transpile' first"; exit 2; }

if [ "$1" != "--run-only" ]; then
    rm -rf "$OBJS" && mkdir -p "$OBJS"
    cd "$GEN"
    xargs -a all.txt -P"$(nproc)" -I{} sh -c '
        o="'"$OBJS"'/$(echo {} | tr "/@" "__").o"
        gcc -std=gnu11 -D_GNU_SOURCE -include uchar.h \
            -include classes/java/lang/Class.h \
            -I"'"$ROOT"'/native/src/glue" -c -O0 -w \
            -Wno-error=incompatible-pointer-types \
            -Wno-error=int-conversion \
            -Wno-error=implicit-function-declaration \
            -o "$o" {}'
    for glue in host_glue misc_glue gl_common_glue freetype_glue audio_glue; do
        [ -f "$ROOT/native/src/glue/$glue.c" ] || continue
        gcc -std=gnu11 -O2 -I"$ROOT/native/src/glue" \
            $(pkg-config --cflags freetype2 sdl2) $(pkg-config --cflags vorbisfile 2>/dev/null) -c \
            "$ROOT/native/src/glue/$glue.c" -o "$OBJS/$glue.o"
    done
    gcc -o "$BIN" "$OBJS"/*.o -lSDL2 -lGL $(pkg-config --libs freetype2) -lopenal $(pkg-config --libs vorbisfile) \
        -lm -lpthread -lrt
    echo "built $BIN"
fi

# Vita mount points are plain directory names on Linux (colons are legal).
mkdir -p "$HARNESS/app0:" "$HARNESS/ux0:data/stsvita"
ln -sfn ../../assets_vita "$HARNESS/app0:/assets"

cd "$HARNESS"
# Force SDL onto X11: on this box SDL2 is sdl2-compat over SDL3, which prefers
# Wayland whenever WAYLAND_DISPLAY is set and would open the window there,
# invisible to the documented DISPLAY=:1 + x11grab screenshot workflow.
export SDL_VIDEODRIVER=x11
exec stdbuf -o0 -e0 "$BIN"
