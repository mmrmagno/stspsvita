#!/usr/bin/env bash
set -euo pipefail

if [[ ! -f /game/desktop-1.0.jar ]]; then
    echo "error: mount the folder with desktop-1.0.jar:  -v /path/to/SlayTheSpire:/game:ro" >&2
    exit 2
fi
if [[ ! -w /out ]]; then
    echo "error: /out is not writable, add:  --user \"\$(id -u):\$(id -g)\" -v \"\$PWD/out:/out\"" >&2
    exit 2
fi

BUILD=/build/stspsvita
mkdir "$BUILD"
trap 'rm -rf "$BUILD"' EXIT
cp -a /src/. "$BUILD/"
export HOME=$BUILD/home GRADLE_USER_HOME=$BUILD/gradle-home GRADLE_FLAGS="--offline --no-daemon"
mkdir -p "$HOME"
cp -a /opt/gradle-home "$GRADLE_USER_HOME"
cd "$BUILD"
./build.sh --sts-dir /game --out /out "$@"
