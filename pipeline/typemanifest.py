#!/usr/bin/env python3
"""Generate the gson type manifest from the user's jar.

TeaVM's C backend has field reflection but no generic-type, method, or
constructor reflection, so the gson facade (backend/stubs com.google.gson)
needs three things resolved at build time instead of runtime:

  1. TypeToken subclasses' captured type arguments (class Signature attrs)
  2. an instantiation factory (`X::new`) per deserialization target
  3. element types of generic collection fields (field Signature attrs)

This script scans the jar and emits:
  work/gen/java/com/stsvita/gen/TypeManifest.java  (registrations)
  work/gen/resources/stsvita-reflect.txt           (class -> fields, for the
                                                    build-time ReflectionSupplier)

Target discovery: seeds are TypeToken type arguments plus Class literals
passed to Gson.fromJson at call sites (found via javap -c); the set is then
closed over instance field types within game packages.

Usage: typemanifest.py --work work [--jar path]
"""

from __future__ import annotations

import argparse
import re
import struct
import subprocess
import sys
import zipfile
from pathlib import Path

GAME_PREFIXES = ("com/megacrit/", "de/robojumper/")
JDK_COLLECTIONS = {"java/util/Map", "java/util/HashMap", "java/util/List",
                   "java/util/ArrayList", "java/util/LinkedList"}


class ClassInfo:
    def __init__(self, name):
        self.name = name              # binary name, slashes
        self.super_name = None
        self.signature = None         # class-level Signature attr
        self.access = 0
        self.fields = []              # (access, name, descriptor, signature)
        self.public_ctors = []        # descriptors
        self.has_public_noarg_ctor = False


def parse_class(data: bytes) -> ClassInfo:
    cp_count = struct.unpack(">H", data[8:10])[0]
    off = 10
    cp = {}
    i = 1
    while i < cp_count:
        tag = data[off]
        if tag == 1:
            ln = struct.unpack(">H", data[off + 1:off + 3])[0]
            cp[i] = ("utf8", data[off + 3:off + 3 + ln].decode("utf-8", "replace"))
            off += 3 + ln
        elif tag in (3, 4):
            off += 5
        elif tag in (5, 6):
            off += 9
            i += 1
        elif tag == 7:
            cp[i] = ("class", struct.unpack(">H", data[off + 1:off + 3])[0])
            off += 3
        elif tag in (8, 16, 19, 20):
            off += 3
        elif tag in (9, 10, 11, 12, 17, 18):
            off += 5
        elif tag == 15:
            off += 4
        else:
            raise ValueError(f"unknown constant pool tag {tag}")
        i += 1

    def utf8(idx):
        return cp[idx][1]

    def cls_name(idx):
        return utf8(cp[idx][1])

    access = struct.unpack(">H", data[off:off + 2])[0]
    this_i, super_i = struct.unpack(">HH", data[off + 2:off + 6])
    info = ClassInfo(cls_name(this_i))
    info.access = access
    if super_i:
        info.super_name = cls_name(super_i)
    off += 6
    ifc = struct.unpack(">H", data[off:off + 2])[0]
    off += 2 + 2 * ifc

    def read_attrs(off, want_sig):
        sig = None
        n = struct.unpack(">H", data[off:off + 2])[0]
        off += 2
        for _ in range(n):
            name_i = struct.unpack(">H", data[off:off + 2])[0]
            ln = struct.unpack(">I", data[off + 2:off + 6])[0]
            if want_sig and utf8(name_i) == "Signature":
                sig = utf8(struct.unpack(">H", data[off + 6:off + 8])[0])
            off += 6 + ln
        return off, sig

    nfields = struct.unpack(">H", data[off:off + 2])[0]
    off += 2
    for _ in range(nfields):
        facc, fname_i, fdesc_i = struct.unpack(">HHH", data[off:off + 6])
        off, fsig = read_attrs(off + 6, True)
        info.fields.append((facc, utf8(fname_i), utf8(fdesc_i), fsig))

    nmethods = struct.unpack(">H", data[off:off + 2])[0]
    off += 2
    for _ in range(nmethods):
        macc, mname_i, mdesc_i = struct.unpack(">HHH", data[off:off + 6])
        off, _ = read_attrs(off + 6, False)
        if utf8(mname_i) == "<init>" and (macc & 0x0001):
            info.public_ctors.append(utf8(mdesc_i))
            if utf8(mdesc_i) == "()V":
                info.has_public_noarg_ctor = True

    _, info.signature = read_attrs(off, True)
    return info


def parse_type_args(sig: str):
    """First-level type arguments of `Lraw<ARGS>;` -> list of signature strings."""
    lt = sig.find("<")
    if lt < 0:
        return sig[1:-1], []
    raw = sig[1:lt]
    args, depth, cur = [], 0, ""
    for ch in sig[lt + 1:-2]:
        cur += ch
        if ch == "<":
            depth += 1
        elif ch == ">":
            depth -= 1
        elif ch == ";" and depth == 0:
            args.append(cur)
            cur = ""
    return raw, args


def sig_to_class(sig: str):
    """Signature -> plain binary class name, or None for wildcards/generics."""
    sig = sig.strip()
    if sig.startswith("L"):
        name = sig[1:-1]
        return name.split("<")[0]
    return None


def java_name(binary: str) -> str:
    return binary.replace("/", ".")


def source_ref(binary: str) -> str:
    """Name usable in Java source (inner classes use dots)."""
    return java_name(binary).replace("$", ".")


def descriptor_args(desc: str):
    """JVM method descriptor -> list of Java-source default-value expressions."""
    args = []
    body = desc[1:desc.index(")")]
    i = 0
    while i < len(body):
        ch = body[i]
        if ch == "L":
            end = body.index(";", i)
            args.append(f"({source_ref(body[i + 1:end])}) null")
            i = end + 1
        elif ch == "[":
            j = i
            while body[j] == "[":
                j += 1
            if body[j] == "L":
                j = body.index(";", j)
            args.append("null")
            i = j + 1
        else:
            args.append({"Z": "false", "C": "(char) 0", "B": "(byte) 0",
                         "S": "(short) 0", "I": "0", "J": "0L",
                         "F": "0f", "D": "0d"}[ch])
            i += 1
    return args


def find_fromjson_targets(jar_path, gson_users):
    """javap -c over gson-using classes; collect X for fromJson(.., X.class)."""
    targets = set()
    if not gson_users:
        return targets
    out = subprocess.run(
        ["javap", "-p", "-c", "-classpath", str(jar_path)]
        + [java_name(c) for c in gson_users],
        capture_output=True, text=True).stdout
    last_class = None
    for line in out.splitlines():
        m = re.search(r"ldc(?:_w)?\s+#\d+\s+// class ([\w/$]+)", line)
        if m:
            last_class = m.group(1)
            continue
        if "com/google/gson/Gson.fromJson" in line and "Ljava/lang/Class;" in line:
            if last_class and last_class.startswith(GAME_PREFIXES):
                targets.add(last_class)
    return targets


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--work", type=Path, default=Path("work"))
    ap.add_argument("--jar", type=Path)
    args = ap.parse_args()

    out_java = args.work / "gen/java/com/stsvita/gen/TypeManifest.java"
    out_res = args.work / "gen/resources/stsvita-reflect.txt"
    out_java.parent.mkdir(parents=True, exist_ok=True)
    out_res.parent.mkdir(parents=True, exist_ok=True)

    jar_path = args.jar
    if jar_path is None:
        ptr = args.work / "jar_path.txt"
        if ptr.exists():
            jar_path = Path(ptr.read_text().strip())
    if jar_path is None or not jar_path.exists():
        print("typemanifest: no jar available - emitting empty manifest "
              "(run `make extract` for a real one)")
        out_java.write_text(
            "package com.stsvita.gen;\n\n"
            "/** Placeholder - regenerate with pipeline/typemanifest.py. */\n"
            "public final class TypeManifest {\n"
            "    public static void install() {\n"
            "    }\n\n"
            "    private TypeManifest() {}\n"
            "}\n")
        out_res.write_text("")
        return 0

    z = zipfile.ZipFile(jar_path)
    classes = {}
    gson_users = []
    token_subclasses = []
    for n in z.namelist():
        if not n.endswith(".class") or not n.startswith(GAME_PREFIXES):
            continue
        data = z.read(n)
        info = parse_class(data)
        classes[info.name] = info
        if b"com/google/gson/Gson" in data:
            gson_users.append(info.name)
        if info.super_name == "com/google/gson/reflect/TypeToken":
            token_subclasses.append(info)

    # --- seeds ---
    seeds = set()
    tokens = []  # (subclass name, raw, [arg sigs])
    for info in token_subclasses:
        if not info.signature:
            continue
        _, outer_args = parse_type_args(info.signature)
        if not outer_args:
            continue
        raw, args_ = parse_type_args(outer_args[0])
        tokens.append((info.name, raw, args_))
        for a in args_:
            c = sig_to_class(a)
            if c and c.startswith(GAME_PREFIXES):
                seeds.add(c)
    seeds |= find_fromjson_targets(jar_path, gson_users)

    # --- closure over instance field types ---
    closure = set()
    warnings = []
    queue = sorted(seeds)
    field_elems = []  # (owner, field, elem class)
    while queue:
        cname = queue.pop()
        if cname in closure or cname not in classes:
            continue
        closure.add(cname)
        info = classes[cname]
        # include superclass fields
        if info.super_name and info.super_name.startswith(GAME_PREFIXES):
            queue.append(info.super_name)
        for facc, fname, fdesc, fsig in info.fields:
            if facc & 0x0008:  # static
                continue
            base = fdesc.lstrip("[")
            c = sig_to_class(base) if base.startswith("L") else None
            if c and c.startswith(GAME_PREFIXES):
                queue.append(c)
            if fsig and c in JDK_COLLECTIONS:
                raw, targs = parse_type_args(fsig)
                elem_sig = None
                if raw.endswith("Map") or raw.endswith("HashMap"):
                    if len(targs) == 2:
                        elem_sig = targs[1]
                elif targs:
                    elem_sig = targs[0]
                elem = sig_to_class(elem_sig) if elem_sig else None
                if elem:
                    field_elems.append((cname, fname, elem))
                    if elem.startswith(GAME_PREFIXES):
                        queue.append(elem)
                elif elem_sig:
                    warnings.append(f"unhandled generic field {cname}.{fname}: {fsig}")

    # --- emit ---
    lines = [
        "package com.stsvita.gen;",
        "",
        "import com.google.gson.Gson;",
        "import com.google.gson.internal.ParamType;",
        "import com.google.gson.reflect.TypeToken;",
        "",
        "/**",
        " * GENERATED by pipeline/typemanifest.py from the user's jar - do not edit,",
        " * do not commit. Registers everything the gson facade needs that TeaVM's",
        " * C backend cannot discover at runtime.",
        " */",
        "public final class TypeManifest {",
        "",
        "    public static void install() {",
    ]
    for sub, raw, targs in sorted(tokens):
        arg_refs = []
        for a in targs:
            c = sig_to_class(a)
            arg_refs.append(f"{source_ref(c)}.class" if c else "Object.class")
        lines.append(f'        TypeToken.stsvRegister("{java_name(sub)}",')
        lines.append(f'                ParamType.of({source_ref(raw)}.class, '
                     + ", ".join(arg_refs) + "));")
    lines.append("")
    skipped = []
    for cname in sorted(closure):
        info = classes[cname]
        public = bool(info.access & 0x0001)
        if "$" in cname and any(p.isdigit() for p in cname.split("$")[1:]):
            continue  # anonymous/local classes are never binding targets
        if public and info.has_public_noarg_ctor:
            lines.append(f'        Gson.stsvRegisterFactory("{java_name(cname)}",')
            lines.append(f'                {source_ref(cname)}::new);')
        elif public and info.public_ctors:
            # No no-arg ctor (real gson would use Unsafe): call the smallest
            # public ctor with type defaults; binding overwrites the fields.
            desc = min(info.public_ctors, key=lambda d: len(descriptor_args(d)))
            defaults = ", ".join(descriptor_args(desc))
            lines.append(f'        Gson.stsvRegisterFactory("{java_name(cname)}",')
            lines.append(f'                () -> new {source_ref(cname)}({defaults}));')
        else:
            skipped.append(cname)
    lines.append("")
    for owner, fname, elem in sorted(field_elems):
        lines.append(f'        Gson.stsvRegisterFieldElem("{java_name(owner)}#{fname}",')
        lines.append(f'                {source_ref(elem)}.class);')
    lines += [
        "    }",
        "",
        "    private TypeManifest() {}",
        "}",
        "",
    ]
    out_java.write_text("\n".join(lines))

    res_lines = []
    for cname in sorted(closure):
        info = classes[cname]
        fnames = [f[1] for f in info.fields if not f[0] & 0x0008]
        if fnames:
            res_lines.append(java_name(cname) + " " + " ".join(fnames))
    out_res.write_text("\n".join(res_lines) + "\n")

    for w in warnings:
        print(f"WARNING: {w}")
    for s in skipped:
        print(f"WARNING: no public no-arg ctor, factory skipped: {java_name(s)}")
    print(f"typemanifest: {len(tokens)} TypeTokens, {len(closure)} target classes "
          f"({len(skipped)} without factories), {len(field_elems)} generic fields "
          f"-> {out_java}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
