#!/usr/bin/env python3
"""Extract game assets from the user's jar into the work tree.

The jar itself is left untouched - it is the TeaVM classpath input for the
transpile stage. This stage only pulls the asset payload out.

Usage: extract.py --sts-dir <install dir> --work <work dir>
"""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path

from stsvita_common import find_jar, is_asset, open_jar


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--sts-dir", type=Path, required=True)
    ap.add_argument("--work", type=Path, default=Path("work"))
    args = ap.parse_args()

    jar = find_jar(args.sts_dir)
    out = args.work / "assets_raw"
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)

    count = 0
    total = 0
    with open_jar(jar) as z:
        for info in z.infolist():
            if not is_asset(info.filename):
                continue
            z.extract(info, out)
            count += 1
            total += info.file_size

    # Stable pointer to the jar for later stages, without copying 349MB.
    (args.work / "jar_path.txt").write_text(str(jar) + "\n")

    print(f"extract: {count} asset files, {total / 1048576:.1f} MB -> {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
