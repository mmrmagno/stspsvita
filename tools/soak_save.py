#!/usr/bin/env python3
"""Soak-test accelerator: buff an on-device autosave into a god run.

Slay the Spire saves are gson JSON, optionally obfuscated as
base64(bytes XOR cycle("key")) (SaveFileObfuscator).
The loader accepts both forms; this tool preserves whichever it found.

Usage:
  tools/soak_save.py --host 192.168.1.42 [--slot 1] [--char IRONCLAD] show
  tools/soak_save.py --host 192.168.1.42 [--slot 1] [--char IRONCLAD] buff

`buff` sets 999/999 HP, 9999 gold, all three Act 4 keys, is_final_act_on,
an insta-kill upgraded deck, +Fusion Hammer (4 energy), and three Fire
Potions — so a player can speedrun Acts 1-4 with one-turn fights while
still exercising every act's real content. Dev/soak tool only; never part
of the build.

`--no-act4` leaves the keys out so the run ends at the Act 3 boss. That
victory path (DeathScreen isVictory) is the only one granting the plain
RUBY/EMERALD/SAPPHIRE achievements, which the Watcher unlock requires —
a straight-to-Act-4 god run grants only *_PLUS and leaves her locked.
"""
import argparse
import base64
import ftplib
import io
import json
import sys

KEY = b"key"


def xor(data: bytes) -> bytes:
    return bytes(b ^ KEY[i % len(KEY)] for i, b in enumerate(data))


def decode(raw: bytes) -> tuple[dict, bool]:
    """Return (save dict, was_obfuscated)."""
    text = raw.decode("utf-8", errors="replace").strip()
    if text.startswith("{"):
        return json.loads(text), False
    plain = xor(base64.b64decode(text))
    return json.loads(plain.decode("utf-8")), True


def encode(save: dict, obfuscated: bool) -> bytes:
    text = json.dumps(save)
    if not obfuscated:
        return text.encode("utf-8")
    return base64.b64encode(xor(text.encode("utf-8")))


GOD_DECK = (
    [{"id": "Immolate", "upgrades": 1, "misc": 0}] * 5
    + [{"id": "Bludgeon", "upgrades": 1, "misc": 0}] * 4
    + [{"id": "Impervious", "upgrades": 1, "misc": 0}] * 3
    + [{"id": "Offering", "upgrades": 1, "misc": 0}] * 2
)

# The Watcher gets her own deck so the soak actually exercises stances
# (Wrath/Calm via Tantrum/Vigilance, Divinity via Worship mantra) and their
# VFX. No Blasphemy: it kills the player next turn and death deletes the
# autosave. IDs match localization keys, not class names.
WATCHER_GOD_DECK = (
    [{"id": "Tantrum", "upgrades": 1, "misc": 0}] * 4
    + [{"id": "Ragnarok", "upgrades": 1, "misc": 0}] * 3
    + [{"id": "Conclude", "upgrades": 1, "misc": 0}] * 3
    + [{"id": "Vigilance", "upgrades": 1, "misc": 0}] * 2
    + [{"id": "Worship", "upgrades": 1, "misc": 0}] * 2
)

GOD_DECKS = {"WATCHER": WATCHER_GOD_DECK}


def buff(save: dict, char: str, act4: bool = True) -> None:
    save["max_health"] = 999
    save["current_health"] = 999
    save["gold"] = 9999
    # Without keys/final-act the run ends at the Act 3 boss, which is the
    # ONLY place the plain RUBY/EMERALD/SAPPHIRE win achievements are
    # granted — and the Watcher unlock requires one of them. A pure Act 4
    # god run only grants *_PLUS and leaves the Watcher locked.
    save["has_ruby_key"] = act4
    save["has_emerald_key"] = act4
    save["has_sapphire_key"] = act4
    save["is_final_act_on"] = act4
    save["cards"] = GOD_DECKS.get(char, GOD_DECK)
    if "Fusion Hammer" not in save.get("relics", []):
        save.setdefault("relics", []).append("Fusion Hammer")
        save.setdefault("relic_counters", []).append(-1)
    save["potions"] = ["Fire Potion", "Fire Potion", "Fire Potion"]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", required=True)
    ap.add_argument("--port", type=int, default=1337)
    ap.add_argument("--slot", default="1", help="save slot prefix ('' for none)")
    ap.add_argument("--char", default="IRONCLAD")
    ap.add_argument("--no-act4", action="store_true",
                    help="no keys/final act: run ends at the Act 3 boss, "
                         "granting the plain win achievement (unlocks Watcher)")
    ap.add_argument("action", choices=["show", "buff"])
    args = ap.parse_args()

    prefix = f"{args.slot}_" if args.slot else ""
    path = f"ux0:/data/stsvita/saves/{prefix}{args.char}.autosave"

    ftp = ftplib.FTP()
    ftp.connect(args.host, args.port, timeout=30)
    ftp.login()
    buf = io.BytesIO()
    try:
        ftp.retrbinary(f"RETR {path}", buf.write)
    except ftplib.all_errors as e:
        print(f"cannot read {path}: {e}", file=sys.stderr)
        print("Start a run, pass Neow, then QUIT to menu so the autosave "
              "exists (death deletes it).", file=sys.stderr)
        return 2
    save, obfuscated = decode(buf.getvalue())

    print(f"{path} (obfuscated={obfuscated})")
    for k in ("name", "level_name", "act_num", "floor_num", "current_health",
              "max_health", "gold", "has_ruby_key", "has_emerald_key",
              "has_sapphire_key", "is_final_act_on"):
        print(f"  {k} = {save.get(k)}")

    if args.action == "buff":
        buff(save, args.char, act4=not args.no_act4)
        data = encode(save, obfuscated)
        ftp.storbinary(f"STOR {path}", io.BytesIO(data))
        # Read back and compare.
        chk = io.BytesIO()
        ftp.retrbinary(f"RETR {path}", chk.write)
        if chk.getvalue() != data:
            print("READBACK MISMATCH — do not trust this save", file=sys.stderr)
            return 3
        deck = GOD_DECKS.get(args.char, GOD_DECK)
        act4_msg = ("no keys, act 3 finish (win achievement + Watcher unlock)"
                    if args.no_act4 else "3 keys, final act on")
        print(f"buffed + verified ({len(data)} bytes): 999 HP, 9999 gold, "
              f"{act4_msg}, {len(deck)} god cards, Fusion Hammer")
    ftp.quit()
    return 0


if __name__ == "__main__":
    sys.exit(main() or 0)
