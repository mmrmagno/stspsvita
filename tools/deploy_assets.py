#!/usr/bin/env python3
"""Upload a staged build to the Vita over VitaShell's FTP.

Press SELECT in VitaShell to enable FTP (port 1337).

  tools/deploy_assets.py --src out/data/stsvita --host 192.168.1.42

Files are compared by SHA-256 with the list left by the previous upload, so a
file that changed without changing size is still sent. An interrupted upload
continues where it stopped and never looks complete to the launcher, because
assets.id and game.manifest go last.
"""
import argparse
import ftplib
import io
import sys
from pathlib import Path

INDEX = "files.sha256"
LAST = ("assets.id", "game.manifest")
DEFAULT_DEST = "ux0:/data/stsvita"


def parse_index(text: str) -> dict[str, str]:
    out = {}
    for line in text.splitlines():
        digest, sep, rel = line.partition("  ")
        if sep and len(digest) == 64 and rel and ".." not in rel.split("/"):
            out[rel] = digest
    return out


def ensure_dir(ftp: ftplib.FTP, path: str) -> None:
    parts, cur = path.split("/"), ""
    for p in parts:
        if not p:
            continue
        cur = f"{cur}/{p}" if cur else p
        try:
            ftp.mkd(cur)
        except ftplib.error_perm:
            pass


def remote_index(ftp: ftplib.FTP, dest: str) -> dict[str, str]:
    buf = io.BytesIO()
    try:
        ftp.retrbinary(f"RETR {dest}/{INDEX}", buf.write)
    except ftplib.error_perm:
        return {}
    return parse_index(buf.getvalue().decode("utf-8", "replace"))


def remote_size(ftp: ftplib.FTP, path: str) -> int | None:
    try:
        size = ftp.size(path)
    except ftplib.all_errors:
        return None
    return int(size) if size is not None else None


def deploy(ftp: ftplib.FTP, src: Path, dest: str, force: bool = False) -> tuple[int, int]:
    local = parse_index((src / INDEX).read_text())
    known = {} if force else remote_index(ftp, dest)
    made: set[str] = set()
    uploaded = skipped = 0

    def put(rel: str) -> None:
        remote = f"{dest}/{rel}"
        parent = remote.rsplit("/", 1)[0]
        if parent not in made:
            ensure_dir(ftp, parent)
            made.add(parent)
        with (src / rel).open("rb") as fh:
            ftp.storbinary(f"STOR {remote}", fh)

    def put_index(entries: dict[str, str]) -> None:
        body = "".join(f"{d}  {r}\n" for r, d in entries.items()).encode()
        ftp.storbinary(f"STOR {dest}/{INDEX}", io.BytesIO(body))

    def drop(rel: str) -> None:
        try:
            ftp.voidcmd(f"DELE {dest}/{rel}")
        except ftplib.error_perm:
            pass

    for rel in LAST:
        drop(rel)
    for rel in (r for r in known.keys() - local.keys() if r.startswith("assets/")):
        drop(rel)
    done = {r: d for r, d in local.items() if known.get(r) == d}
    ensure_dir(ftp, dest)
    put_index(done)
    for i, (rel, digest) in enumerate(local.items(), 1):
        size = (src / rel).stat().st_size
        if rel in done and remote_size(ftp, f"{dest}/{rel}") == size:
            skipped += 1
        else:
            put(rel)
            done[rel] = digest
            uploaded += 1
            if uploaded % 100 == 0:
                put_index(done)
        if i % 100 == 0 or i == len(local):
            print(f"  {i}/{len(local)} (uploaded {uploaded}, skipped {skipped})")
    for rel in (INDEX, *LAST):
        put(rel)
    return uploaded, skipped


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--src", type=Path, default=Path("out/data/stsvita"))
    ap.add_argument("--host", required=True, help="Vita IP (VitaShell FTP)")
    ap.add_argument("--port", type=int, default=1337)
    ap.add_argument("--dest", default=DEFAULT_DEST)
    ap.add_argument("--force", action="store_true", help="upload every file")
    args = ap.parse_args()

    if not (args.src / INDEX).is_file():
        print(f"not a staged build: {args.src}", file=sys.stderr)
        return 2
    ftp = ftplib.FTP()
    ftp.connect(args.host, args.port, timeout=30)
    ftp.login()
    try:
        ftp.voidcmd("TYPE I")
    except ftplib.all_errors:
        pass
    deploy(ftp, args.src, args.dest, args.force)
    ftp.quit()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
