#!/usr/bin/env python3
"""Apply the TEAVM_VITA runtime patch to a freshly transpiled C tree.

TeaVM 0.15.0's C runtime carries homebrew-console paths behind `#if TEAVM_PSP`
(malloc heap instead of mmap, setjmp fibers, mktime instead of timegm,
sceKernelLibcTime clock) — see teavm-patches/README.md. The Vita needs the
exact same paths, so this activates them whenever TEAVM_VITA is defined (the
CMake build passes -DTEAVM_VITA=1) and fixes the handful of PSP-only APIs that
differ on the Vita (the pspkernel.h include -> psp2 threadmgr).

Idempotent and anchor-based: safe to re-run after every transpile, tolerant of
line-number drift. Run by the gradle `transpile` task; a no-op for the host
(desktop-GL) build, which never defines TEAVM_VITA.
"""
import sys
from pathlib import Path

SENTINEL = "/* STSVITA: TEAVM_VITA runtime patch */"


def patch_definitions(path: Path) -> bool:
    text = path.read_text()
    if SENTINEL in text:
        return False
    # Enable the PSP homebrew code paths whenever TEAVM_VITA is defined.
    anchor = "#ifndef TEAVM_USE_SETJMP"
    if anchor not in text:
        raise SystemExit(f"definitions.h: anchor {anchor!r} not found")
    inject = (
        f"{SENTINEL}\n"
        "#ifdef TEAVM_VITA\n"
        "    #undef TEAVM_UNIX\n"
        "    #define TEAVM_UNIX 0\n"
        "    #undef TEAVM_PSP\n"
        "    #define TEAVM_PSP 1\n"
        "#endif\n\n"
    )
    path.write_text(text.replace(anchor, inject + anchor, 1))
    return True


def patch_fiber(path: Path) -> bool:
    text = path.read_text()
    if SENTINEL in text:
        return False
    # With TEAVM_PSP active on Vita, fiber.c would pull PSP's <pspkernel.h>.
    # The Vita gets sceKernelDelayThread from psp2 instead.
    old = "#if TEAVM_PSP\n    #include <pspkernel.h>\n#endif"
    if old not in text:
        raise SystemExit("fiber.c: pspkernel.h include block not found")
    new = (
        f"{SENTINEL}\n"
        "#if TEAVM_VITA\n"
        "    #include <psp2/kernel/threadmgr.h>\n"
        "#elif TEAVM_PSP\n"
        "    #include <pspkernel.h>\n"
        "#endif"
    )
    path.write_text(text.replace(old, new, 1))
    return True


def patch_uchar(path: Path) -> bool:
    text = path.read_text()
    changed = False
    # (1) uchar.h is force-included (-include) BEFORE definitions.h runs, so
    # TEAVM_PSP isn't set yet and the shim would pull newlib's absent C11
    # <uchar.h>. Key it on TEAVM_VITA, which is always defined via -D.
    guard = "#if TEAVM_PSP || defined(TEAVM_VITA)"
    if guard not in text:
        if "#if TEAVM_PSP\n" not in text:
            raise SystemExit("uchar.h: leading '#if TEAVM_PSP' not found")
        text = text.replace("#if TEAVM_PSP\n", f"{SENTINEL}\n{guard}\n", 1)
        changed = True
    # (2) The char16_t typedef needs uint16_t; Vita's <wchar.h> doesn't pull
    # <stdint.h> in transitively (newlib), so add it explicitly — otherwise
    # char16_t is broken and every u"" string literal fails to initialize.
    if "#include <stdint.h>" not in text:
        text = text.replace("#include <wchar.h>",
                            "#include <wchar.h>\n#include <stdint.h>", 1)
        changed = True
    if changed:
        path.write_text(text)
    return changed


MBPATH_HELPER = '''
/* STSVITA: TeaVM's C VFS treats any path not starting with '/' as relative and
 * prepends getcwd(), which mangles Vita "drive:" paths (ux0:, app0:,...) into
 * invalid doubled paths like "ux0:/data/stsvita/ux0:data/.../foo". Recover the
 * intended path by cutting back to the LAST drive prefix; genuinely cwd-
 * relative paths (one drive colon, at the front) are left unchanged. */
static char* stsv_file_mbpath(char16_t* n, int32_t sz) {
    char* p = teavm_char16ToMb(n, sz);
    char* colon = strrchr(p, ':');
    if (colon != NULL) {
        char* s = colon;
        while (s > p) {
            char c = s[-1];
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                s--;
            } else {
                break;
            }
        }
        if (s != p) {
            memmove(p, s, strlen(s) + 1);
        }
    }
    return p;
}

'''


def patch_file(path: Path) -> bool:
    text = path.read_text()
    changed = False
    # (1) file.c implements teavm_file_* only for TEAVM_UNIX / TEAVM_WINDOWS —
    # no PSP branch, so TEAVM_UNIX=0 leaves the whole file-IO layer undefined.
    # Vita's newlib routes POSIX file IO (open/read/lseek/stat/opendir/getcwd/
    # getpwuid) to sceIo, so re-enable the UNIX branch on Vita.
    if "TEAVM_UNIX || defined(TEAVM_VITA)" not in text:
        old = "#if TEAVM_UNIX\n#include <unistd.h>"
        if old not in text:
            raise SystemExit("file.c: '#if TEAVM_UNIX' POSIX block not found")
        text = text.replace(
            old, f"{SENTINEL}\n#if TEAVM_UNIX || defined(TEAVM_VITA)\n#include <unistd.h>", 1)
        changed = True
    # (2) Normalize Vita drive paths: insert the helper and route every
    # teavm_file_* path conversion through it.
    if "stsv_file_mbpath" not in text:
        anchor = "int32_t teavm_file_homeDirectory(char16_t** result) {"
        if anchor not in text:
            raise SystemExit("file.c: teavm_file_homeDirectory anchor not found")
        text = text.replace(anchor, MBPATH_HELPER.lstrip("\n") + anchor, 1)
        text = text.replace("teavm_char16ToMb(name, nameSize)",
                            "stsv_file_mbpath(name, nameSize)")
        changed = True
    if changed:
        path.write_text(text)
    return changed


def patch_freetype_font_generator(path: Path) -> bool:
    text = path.read_text()
    old = "sfld_cbgggf_FreeTypeFontGenerator_maxTextureSize = INT32_C(1024);"
    new = "sfld_cbgggf_FreeTypeFontGenerator_maxTextureSize = INT32_C(256);"
    if new in text:
        return False
    if old not in text:
        raise SystemExit("FreeTypeFontGenerator.c: maxTextureSize anchor not found")
    path.write_text(text.replace(old, new, 1))
    return True


def patch_jsonvalue_switchmap(path: Path) -> bool:
    text = path.read_text()
    sentinel = "/* STSVITA: direct JsonValue.ValueType switch map */"
    if sentinel in text:
        return False
    start = "void meth_cbgu_JsonValue_1__clinit_() {"
    end = "\nvoid initclass_cbgu_JsonValue_1() {"
    start_idx = text.find(start)
    end_idx = text.find(end, start_idx)
    if start_idx < 0 or end_idx < 0:
        raise SystemExit("JsonValue@d1.c: switch-map clinit anchors not found")
    new_body = f'''void meth_cbgu_JsonValue_1__clinit_() {{
    TeaVM_Array* map;
    {sentinel}
    TEAVM_ALLOC_STACK(INT32_C(0));
    map = TEAVM_WITH_CALL_SITE_ID(140957, meth_otr_Allocator_allocateArray(&Arr_I_Cls, INT32_C(7)));
    sfld_cbgu_JsonValue_1__SwitchMap_com_badlogic_gdx_utils_JsonValue_ValueType = map;
    TEAVM_ARRAY_AT(map, int32_t, INT32_C(2)) = INT32_C(1);
    TEAVM_ARRAY_AT(map, int32_t, INT32_C(3)) = INT32_C(2);
    TEAVM_ARRAY_AT(map, int32_t, INT32_C(4)) = INT32_C(3);
    TEAVM_ARRAY_AT(map, int32_t, INT32_C(5)) = INT32_C(4);
    TEAVM_ARRAY_AT(map, int32_t, INT32_C(6)) = INT32_C(5);
    TEAVM_RELEASE_STACK;
}}
'''
    path.write_text(text[:start_idx] + new_body + text[end_idx:])
    return True


def patch_float_tostring(path: Path) -> bool:
    """Replace TeaVM's FloatAnalyzer-based Float.toString with snprintf.

    The stock path overruns the StringBuilder buffer for some values (an
    IndexOutOfBounds surfaces in String.<init> when gson serializes a save
    float on a room transition). snprintf %.9g round-trips a 32-bit float, and
    teavm_cToString manages its own GC frame.
    """
    text = path.read_text()
    if "STSVITA float toString" in text:
        return False
    start = "void* meth_jl_Float_toString(float teavm_local_1) {"
    end = "void* meth_jl_Float_toString_0("
    si = text.find(start)
    ei = text.find(end, si)
    if si < 0 or ei < 0:
        raise SystemExit("Float.c: meth_jl_Float_toString anchors not found")
    new_fn = (
        "void* meth_jl_Float_toString(float teavm_local_1) {\n"
        "    /* STSVITA float toString via snprintf (see vita_patch_runtime). */\n"
        "    char teavm_stsv_buf[40];\n"
        "    double teavm_stsv_d = (double) teavm_local_1;\n"
        "    if (teavm_stsv_d != teavm_stsv_d) { return (void*) teavm_cToString(\"NaN\"); }\n"
        "    if (teavm_stsv_d > 3.5e38 || teavm_stsv_d < -3.5e38) {\n"
        "        return (void*) teavm_cToString(teavm_stsv_d < 0.0 ? \"-Infinity\" : \"Infinity\");\n"
        "    }\n"
        "    snprintf(teavm_stsv_buf, sizeof(teavm_stsv_buf), \"%.9g\", teavm_stsv_d);\n"
        "    return (void*) teavm_cToString(teavm_stsv_buf);\n"
        "}\n\n"
    )
    text = text[:si] + new_fn + text[ei:]
    text = text.replace('#include "Float.h"\n',
                        '#include "Float.h"\n#include <stdio.h>\n#include "../../../string.h"\n', 1)
    path.write_text(text)
    return True


def patch_array_data_offset(path: Path) -> bool:
    """Make TEAVM_ARRAY_DATA use a fixed, address-independent data offset.

    Root cause of the digits-become-zero bug (volume 0%, dungeon percents):
    the stock macro aligns the ABSOLUTE pointer (align(array+12, 8) on
    32-bit), so a long[]/double[] whose data was written at +16 reads at +12
    after the compacting GC moves it to a 4-mod-8 address — every int64
    element is then read 4 bytes shifted (DoubleAnalyzer's mantissa tables
    became garbage after the first full GC). The allocator and the GC's
    objectSize already use the constant align(sizeof(TeaVM_Array), itemSize)
    offset, so the fixed offset is the consistent form. Reproduced and
    verified with the scratchpad 32-bit host probe (pre/post System.gc).
    Upstream-worthy TeaVM fix (M5).
    """
    text = path.read_text()
    sentinel = "/* STSVITA: fixed array data offset */"
    if sentinel in text:
        return False
    old = ("#define TEAVM_ARRAY_DATA(array, type) "
           "((type*) TEAVM_ALIGN((((TeaVM_Array*) (array)) + 1), sizeof(type)))")
    if old not in text:
        raise SystemExit("core.h: TEAVM_ARRAY_DATA macro not found")
    new = (f"{sentinel}\n"
           "#define TEAVM_ARRAY_DATA(array, type) ((type*) ((char*) (array) + "
           "((sizeof(TeaVM_Array) + sizeof(type) - 1) / sizeof(type) * sizeof(type))))")
    path.write_text(text.replace(old, new, 1))
    return True


def patch_reflection_data_offset(path: Path) -> bool:
    """reflection.c has the same address-aligned array-data computation."""
    text = path.read_text()
    sentinel = "/* STSVITA fixed offset */"
    if sentinel in text:
        return False
    old = "char* data = TEAVM_ALIGN(((TeaVM_Array*) array) + 1, size);"
    if text.count(old) != 2:
        raise SystemExit("reflection.c: expected exactly 2 array-data sites")
    new = ("char* data = (char*) array + "
           f"((sizeof(TeaVM_Array) + size - 1) / size * size); {sentinel}")
    path.write_text(text.replace(old, new))
    return True


def patch_dropdown_guard(path: Path) -> bool:
    """Null-guard DropdownMenu.setSelectedIndex against an empty dropdown.

    Opening the in-run settings screen on the Vita NPEs here: the resolution dropdown's selectionBox is null, which per the
    ctor's bytecode means it was constructed with an EMPTY labels list —
    the display-options enumeration produced nothing for the Vita's single
    non-standard 960x544 mode on some path. Selecting a resolution is
    meaningless on fixed hardware, so an early return is the whole fix;
    the sliders and toggles on the screen are unaffected.
    """
    text = path.read_text()
    sentinel = "/* STSVITA: empty-dropdown guard */"
    if sentinel in text:
        return False
    sig = ("void meth_cmcso_DropdownMenu_setSelectedIndex"
           "(void* teavm_this_, int32_t teavm_local_1) {")
    si = text.find(sig)
    if si < 0:
        raise SystemExit("DropdownMenu.c: setSelectedIndex not found")
    alloc = "TEAVM_ALLOC_STACK(INT32_C(1));"
    ai = text.find(alloc, si)
    if ai < 0:
        raise SystemExit("DropdownMenu.c: setSelectedIndex stack alloc not found")
    insert_at = ai + len(alloc)
    guard = (f"\n    {sentinel}\n"
             "    if (TEAVM_FIELD(teavm_this_, cls_cmcso_DropdownMenu, fld_selectionBox) == NULL) {\n"
             "        TEAVM_RELEASE_STACK;\n"
             "        return;\n"
             "    }")
    path.write_text(text[:insert_at] + guard + text[insert_at:])
    return True


def patch_controller_model_ps4(path: Path) -> bool:
    """Show PlayStation button glyphs for the Vita gamepad.

    CInputHelper picks the glyph set from the controller NAME: contains
    "360" -> XBOX_360, contains "Xbox One" -> XBOX_ONE, anything else ->
    XBOX_360 (there is no PS-name branch in the desktop jar). The PS4 glyph
    assets ship with the game and the ControllerModel.PS4 enum survives
    tree-shaking, so retarget the fallback branch — which is what our
    "PS Vita" controller hits — to PS4.
    """
    text = path.read_text()
    sentinel = "/* STSVITA: PS4 glyphs */"
    if sentinel in text:
        return False
    # The fallback branch is the one guarded by a NEGATED name.contains().
    guard = text.find("if ((!TEAVM_WITH_CALL_SITE_ID")
    while guard >= 0:
        line_end = text.find("\n", guard)
        if "meth_jl_String_contains" in text[guard:line_end]:
            break
        guard = text.find("if ((!TEAVM_WITH_CALL_SITE_ID", guard + 1)
    if guard < 0:
        raise SystemExit("CInputHelper.c: fallback model branch not found")
    old = "sfld_cmchc_CInputHelper_ControllerModel_XBOX_360"
    xi = text.find(old, guard)
    if xi < 0:
        raise SystemExit("CInputHelper.c: XBOX_360 in fallback branch not found")
    new = f"sfld_cmchc_CInputHelper_ControllerModel_PS4 {sentinel}"
    path.write_text(text[:xi] + new + text[xi + len(old):])
    return True


def patch_gc_timing(path: Path) -> bool:
    """Time every GC pass and log pauses >= 100ms WITH memory context (v2).

    The combat stutter is young pauses firing every couple of frames
    (294/session, p50 16ms). A young GC triggers when getNextChunkIfPossible
    finds no single free chunk >= the requested allocation, so the line logs
    the trigger size, freeMemory before/after, availableBytes, and the time
    since the previous GC ended — one device run discriminates heap
    exhaustion (free ~0 before), fragmentation (free large but req large),
    and shrink-policy tightness (avail small). req is stale for explicit
    System.gc() calls (only alloc-triggered GCs set it).
    Strip with the rest of the diagnostics for release (M5).
    """
    text = path.read_text()
    if "STSVITA: gc pause timing" in text:
        return False
    getnext = "void meth_otr_GC_getNextChunk(int32_t teavm_local_1) {"
    if getnext not in text:
        raise SystemExit("GC.c: getNextChunk definition not found")
    text = text.replace(
        getnext,
        "/* STSVITA: gc trigger size */\n"
        "static int32_t stsv_gc_req;\n"
        + getnext
        + "\n    stsv_gc_req = teavm_local_1;",
        1,
    )
    defn = "void meth_otr_GC_doCollectGarbage() {"
    if defn not in text:
        raise SystemExit("GC.c: doCollectGarbage definition not found")
    wrapper = (
        "/* STSVITA: gc pause timing v2 */\n"
        "#include <stdio.h>\n"
        "extern int64_t teavm_currentTimeNano(void);\n"
        "static int64_t stsv_gc_last_end_ns;\n"
        "static void meth_otr_GC_doCollectGarbage_stsv_inner(void);\n"
        "void meth_otr_GC_doCollectGarbage() {\n"
        "    int64_t stsv_t0 = teavm_currentTimeNano();\n"
        "    int32_t stsv_full = sfld_otr_GC_isFullGC;\n"
        "    int32_t stsv_free0 = sfld_otr_GC_freeMemory;\n"
        "    meth_otr_GC_doCollectGarbage_stsv_inner();\n"
        "    int64_t stsv_t1 = teavm_currentTimeNano();\n"
        "    int64_t stsv_ms = (stsv_t1 - stsv_t0) / INT64_C(1000000);\n"
        "    if (stsv_ms >= 100) {\n"
        "        int64_t stsv_dt = stsv_gc_last_end_ns\n"
        "                ? (stsv_t0 - stsv_gc_last_end_ns) / INT64_C(1000000) : INT64_C(-1);\n"
        "        fprintf(stderr, \"[gc] %s pause %lldms req=%dB free %d->%dKB avail %lldKB dt=%lldms\\n\",\n"
        "                stsv_full ? \"FULL\" : \"young\", (long long) stsv_ms,\n"
        "                (int) stsv_gc_req,\n"
        "                (int) (stsv_free0 >> 10), (int) (sfld_otr_GC_freeMemory >> 10),\n"
        "                (long long) (teavm_gc_availableBytes >> 10),\n"
        "                (long long) stsv_dt);\n"
        "    }\n"
        "    stsv_gc_last_end_ns = stsv_t1;\n"
        "}\n"
        "static void meth_otr_GC_doCollectGarbage_stsv_inner() {"
    )
    path.write_text(text.replace(defn, wrapper, 1))
    return True


def patch_arrayclass_align(path: Path) -> bool:
    """8-byte-align the dynamic array-class pool.

    Object headers pack the class pointer as (addr - teavm_beforeClasses) >> 3,
    which assumes every class struct is 8-byte aligned. On 32-bit the pool
    element TEAVM_OBJECT_CLASS is not a multiple of 8, so pool slots land on
    4-byte-aligned addresses; the >>3 truncates the low bits and UNPACK returns
    a misaligned class pointer -> itemType reads as NULL -> array clone /
    getComponentType / getDeclaredFields all break (NPEs cards.atlas etc.).
    Wrap the pool element in an 8-aligned union so every slot is 8-aligned.
    """
    text = path.read_text()
    sentinel = "/* STSVITA: 8-align dynamic class pool */"
    if sentinel in text:
        return False
    decl = "static TEAVM_OBJECT_CLASS teavm_dynamicClassPool[TEAVM_DYNAMIC_CLASS_POOL_CAPACITY];"
    if decl not in text:
        raise SystemExit("arrayclass.c: dynamic class pool declaration not found")
    # aligned(8) (not a long long member): long long is only 4-byte-aligned on
    # some 32-bit ABIs (x86-32), so the explicit attribute is what guarantees an
    # 8-byte stride/slot on every 32-bit target including arm-vita-eabi.
    new_decl = (
        f"{sentinel}\n"
        "typedef union { TEAVM_OBJECT_CLASS c; } __attribute__((aligned(8))) teavm_dyn_slot;\n"
        "static teavm_dyn_slot teavm_dynamicClassPool[TEAVM_DYNAMIC_CLASS_POOL_CAPACITY];"
    )
    text = text.replace(decl, new_decl, 1)
    alloc = "TEAVM_OBJECT_CLASS* ptr = &teavm_dynamicClassPool[teavm_dynamicClassPoolSize++];"
    if alloc not in text:
        raise SystemExit("arrayclass.c: pool alloc site not found")
    text = text.replace(alloc,
        "TEAVM_OBJECT_CLASS* ptr = &teavm_dynamicClassPool[teavm_dynamicClassPoolSize++].c;", 1)
    idx = "return &teavm_dynamicClassPool[index].parent;"
    if idx not in text:
        raise SystemExit("arrayclass.c: pool index accessor not found")
    text = text.replace(idx, "return &teavm_dynamicClassPool[index].c.parent;", 1)
    path.write_text(text)
    return True


def patch_time_clock(path: Path) -> bool:
    """Give System.nanoTime() sub-second resolution on the Vita.

    TeaVM's TEAVM_PSP path uses sceKernelLibcTime() (1-SECOND resolution) for
    both currentTimeMillis and currentTimeNano. VitaGraphics.deltaTime is
    (micros()-last)/1e6 with micros() = nanoTime()/1000, so a 1-second clock
    made deltaTime 0 on nearly every frame -> every StS animation and the intro
    fade froze -> the menu rendered but stayed at alpha 0 (black). Use the
    monotonic microsecond clock sceKernelGetProcessTimeWide() for nanoTime.
    """
    text = path.read_text()
    if "sceKernelGetProcessTimeWide" in text:
        return False
    old = ("    int64_t teavm_currentTimeNano() {\n"
           "        return (int64_t) sceKernelLibcTime(NULL) * 1000000000;\n"
           "    }")
    if old not in text:
        raise SystemExit("time.c: TEAVM_PSP currentTimeNano anchor not found")
    new = ("    int64_t teavm_currentTimeNano() {\n"
           "        /* STSVITA: monotonic us clock; sceKernelLibcTime is 1s-only\n"
           "         * and zeroed VitaGraphics.deltaTime -> frozen animations. */\n"
           "        return (int64_t) sceKernelGetProcessTimeWide() * 1000;\n"
           "    }")
    text = text.replace(old, new, 1)
    text = text.replace("#if TEAVM_PSP\n    int64_t teavm_currentTimeMillis()",
                        "#if TEAVM_PSP\n    #include <psp2/kernel/processmgr.h>\n"
                        "    int64_t teavm_currentTimeMillis()", 1)
    path.write_text(text)
    return True


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: vita_patch_runtime.py <generated_c_dir>")
    gen = Path(sys.argv[1])
    if not gen.is_dir():
        raise SystemExit(f"not a directory: {gen}")
    steps = [
        ("definitions.h", patch_definitions),
        ("fiber.c", patch_fiber),
        ("uchar.h", patch_uchar),
        ("file.c", patch_file),
        ("arrayclass.c", patch_arrayclass_align),
        ("core.h", patch_array_data_offset),
        ("reflection.c", patch_reflection_data_offset),
        ("time.c", patch_time_clock),
        ("classes/java/lang/Float.c", patch_float_tostring),
        ("classes/org/teavm/runtime/GC.c", patch_gc_timing),
        ("classes/com/megacrit/cardcrawl/screens/options/DropdownMenu.c",
         patch_dropdown_guard),
        ("classes/com/megacrit/cardcrawl/helpers/controller/CInputHelper.c",
         patch_controller_model_ps4),
        # The real root-cause fix is patch_arrayclass_align above (8-byte class
        # alignment). freetype maxTextureSize is a memory optimization; the
        # JsonValue switch-map hand-patch is now redundant with the align fix
        # but kept as a harmless safety net for the first hardware bring-up.
        ("classes/com/badlogic/gdx/graphics/g2d/freetype/FreeTypeFontGenerator.c",
         patch_freetype_font_generator),
        ("classes/com/badlogic/gdx/utils/JsonValue@d1.c",
         patch_jsonvalue_switchmap),
    ]
    for name, fn in steps:
        p = gen / name
        if not p.exists():
            raise SystemExit(f"missing generated file: {p}")
        changed = fn(p)
        print(f"[vita-patch] {name}: {'patched' if changed else 'already patched'}")


if __name__ == "__main__":
    main()
