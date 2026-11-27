#!/usr/bin/env python3
"""Compare two benchmark logs (STSV_BENCH=1 builds): before.log after.log."""
import argparse
import sys
from pathlib import Path

NOISE_PCT = 5

COLUMNS = [
    ("fps", "fps", True),
    ("ms_p50", "p50", False),
    ("ms_p95", "p95", False),
    ("ms_p99", "p99", False),
    ("ms_max", "max", False),
    ("game_p95", "game95", False),
    ("over17", ">17ms", False),
    ("over34", ">34ms", False),
    ("draws", "draws", False),
    ("gc", "gc", False),
    ("gc_ms", "gc_ms", False),
    ("alloc_kb", "alloc_kb", False),
    ("short", "short", False),
]


def parse(path: Path) -> dict[str, dict[str, float]]:
    runs: dict[str, dict[str, float]] = {}
    for line in path.read_text(errors="replace").splitlines():
        start = line.find("[bench] scen=")
        if start < 0:
            continue
        fields = {}
        for item in line[start + len("[bench] "):].split():
            key, _, value = item.partition("=")
            fields[key] = value
        name = fields.pop("scen")
        values = {}
        for key, value in fields.items():
            try:
                values[key] = float(value)
            except ValueError:
                pass
        runs[name] = values
    return runs


def cell(before: float, after: float, higher_is_better: bool) -> str:
    text = f"{before:g} -> {after:g}"
    if before == after:
        return text
    better = after > before if higher_is_better else after < before
    if before:
        pct = (after - before) / before * 100
        text += f" ({pct:+.0f}%)"
        if abs(pct) < NOISE_PCT:
            return text
    return text + (" ok" if better else " WORSE")


def table(before: dict, after: dict) -> list[str]:
    rows = []
    header = ["scenario"] + [label for _, label, _ in COLUMNS]
    rows.append("| " + " | ".join(header) + " |")
    rows.append("|" + "---|" * len(header))
    for name in before:
        if name not in after:
            rows.append(f"| {name} | missing in after |")
            continue
        cells = [name]
        for key, _, higher in COLUMNS:
            if key in before[name] and key in after[name]:
                cells.append(cell(before[name][key], after[name][key], higher))
            else:
                cells.append("-")
        rows.append("| " + " | ".join(cells) + " |")
    for name in after:
        if name not in before:
            rows.append(f"| {name} | missing in before |")
    return rows


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("before", type=Path)
    ap.add_argument("after", type=Path)
    args = ap.parse_args()
    before, after = parse(args.before), parse(args.after)
    if not before or not after:
        print("no [bench] lines found; was the build made with STSV_BENCH=1 and did it finish?",
              file=sys.stderr)
        return 2
    print("\n".join(table(before, after)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
