#!/usr/bin/env bash
# Compile what can be compiled without the game. Runs inside the builder image.
set -euo pipefail

SRC=${1:-/src}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

JARS=$(find /opt/gradle-home -name '*.jar' | tr '\n' ':')
# NewInstanceRegistry names game classes, so it needs the jar.
find "$SRC/backend" -name '*.java' ! -name NewInstanceRegistry.java > "$OUT/sources"
mkdir -p "$OUT/gen/com/stsvita/gen"
printf 'package com.stsvita.gen;\npublic final class BuildFlags {\n    public static final boolean BENCH = false;\n}\n' \
    > "$OUT/gen/com/stsvita/gen/BuildFlags.java"
echo "$OUT/gen/com/stsvita/gen/BuildFlags.java" >> "$OUT/sources"
javac -nowarn -encoding UTF-8 -cp "$JARS" -d "$OUT/classes" "@$OUT/sources"

cd "$SRC/native/src"
for f in main.c glue/*.c; do
    [[ $f == glue/host_glue.c ]] && continue
    arm-vita-eabi-gcc -fsyntax-only -std=gnu11 -D_GNU_SOURCE -DTEAVM_VITA=1 \
        -I. -Iglue -I"$VITASDK/arm-vita-eabi/include/freetype2" \
        -mfpu=neon -mcpu=cortex-a9 -Werror=implicit-function-declaration "$f"
done
echo "compile check: OK"
