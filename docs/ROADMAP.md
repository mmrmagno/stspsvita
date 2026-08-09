# Roadmap

## M0 — Research & architecture ✅ (2026-07-02)

- Feasibility survey ([RESEARCH.md](RESEARCH.md)); approach locked:
  TeaVM C backend + custom libGDX 1.9.5 Vita backend.
- Go/no-go memory experiment passed (game boots in a 256MB heap).
- Repo scaffold, asset pipeline implemented and tested against a real jar.

## M1 — Asset pipeline complete (except optional font optimization)

- [x] Jar fingerprinting/verification (`pipeline/verify.py`)
- [x] Extraction, native/font stripping, language selection (`pipeline/assets.py`)
- [x] DXT5/DXT1 conversion with runtime `.dds` replacement; selective
      device-proven half-resolution policy, full-resolution UI/card/VFX/chest
      exceptions, NPOT atlas padding, JPG/PNG collision-safe sibling names,
      and dead-texture stubs are reproducible in `pipeline/assets.py`. The
      five-file quarter-data City parallax candidate was visually rejected on
      hardware 2026-07-17 (below native resolution) and reverted to half-data;
      the protected full-resolution policies are unchanged.
- [ ] Font subsetting per language selection; pre-baked bitmap fonts if
      runtime FreeType proves RAM-hungry on hardware
- [x] OGG re-encode; encoder failures are fatal and atomic rather than
      silently copying originals under incorrect metadata. **Canonical
      default is now 32kbps/24kHz** (user listening test 2026-07-17:
      "slightly worse but acceptable"; kbps<=32 adds `-ar 24000` since
      libvorbis refuses <~45kbps at 44.1kHz stereo). `--audio-kbps 48`
      remains supported for higher fidelity at the source rate.

## M2 — Transpile milestone: `hello spire` compiles

- [x] TeaVM 0.15 harness runs over stubs + backend + user jar
- [x] Stub set complete enough to link — **transpile is link-clean**
      (260 errors → 0 over six iterations; see stubs/ and ClasslibShims)
- [x] Classlib gaps enumerated and closed: log4j/discord/twitch/steamworks
      shadowed; gson replaced by a facade over libGDX JsonReader (TeaVM's C
      backend has no generic/method/constructor reflection); Scanner
      retargeted to WordScanner via IR rewrite; 94 JNI natives denativized
      (steamworks runtime-dead; gdx2d/FreeType/BufferUtils = the M3 list)
- [x] **89MB of C generated; TeaVM runtime + CardCrawlGame.c + Strike_Red.c
      compile with host GCC** (`-std=gnu11 -D_GNU_SOURCE -include uchar.h`,
      no `-I` into the tree — TeaVM's header names shadow libc's)
- [x] Type manifest generated from the user's jar (`pipeline/typemanifest.py`,
      runs automatically before every transpile): 18 TypeTokens, 28 target
      factories (incl. default-arg ctors where gson would use Unsafe),
      71 generic fields; `StsReflectionSupplier` feeds TeaVM the field
      metadata via ServiceLoader
- [x] Full-tree host compile: all 3,163 objects build clean; links into a
      34MB native Linux executable
- [x] **Desktop-C harness boots the game to its first OpenGL call**
      (`tools/host_harness.sh`): Steam fallback, save system, gson-backed
      prefs, Settings init, Matrix4 (GWT pure-Java emulation), clean
      shutdown — all verified running as C. Boot stops at
      `VitaGL20.glViewport`, i.e. the unwritten M3 graphics backend.
- [x] `TEAVM_VITA` runtime patch; full tree cross-compiles and links under
      arm-vita-eabi-gcc (reproducibly applied by `vita_patch_runtime.py`)
- Exit criterion met on the CPU side; rendering starts M3.

## M3 — Graphics/input/files backend ✅

Desktop-GL harness (`tools/host_harness.sh`) runs the transpiled game on Linux
with an SDL2 window + real OpenGL, so the whole backend is debugged on PC
before hardware. Where it stands:

- [x] `VitaGL20` — all 156 GL20 methods generated (`tools/gen_gl.py`) as
      `@Import`s into platform GL; string/convenience methods hand-written;
      `glGet*/glGen*` use a heap-bounce path for output buffers
- [x] `Gdx2DPixmap` replacement (software pixmap + stb_image decode glue)
- [x] `BufferUtils` replacement; `Buffers.addr` resolves NIO buffer addresses
      (array-backed + per-frame-pinned copies for views — stock TeaVM prunes
      the direct-buffer address interface, so no direct-buffer fast path)
- [x] FreeType backend (`FreeType.java` TeaVM-interop shadow + `freetype_glue.c`,
      font data copied to stable malloc so the GC can't move it under FreeType)
- [x] `VitaInput` (touch→pointer, SceCtrl buttons); `VitaGraphics` GL bringup
- [x] gson facade array fix (String[]/primitive arrays without reflect.Array),
      `ReflectionPool` factory registry, windowed `DisplayConfig`, benign Steam
      native stubs — everything `CardCrawlGame.create()` touches
- [x] **`CardCrawlGame.create()` completes and the game reaches
      `MainMenuScreen` with zero exceptions** in the desktop-GL harness
- [x] **Main menu renders** (Mega Crit splash → Save-Slot screen) in the
      desktop-GL harness. The "render loop stall" was a misdiagnosis: the loop
      ran fine (~145fps, zero exceptions) but the GL viewport was `0x0`, so
      every fragment was clipped. StS `create()` calls `FitViewport.apply()`
      before the viewport is `update()`d and its `resize()` is empty, so
      `glViewport(0,0,0,0)` was never corrected. `VitaApplication.mainLoop`
      now calls `Gdx.gl.glViewport(0,0,w,h)` after `create()` (mirroring
      `LwjglApplication.mainLoop`). Harness forces `SDL_VIDEODRIVER=x11` so the
      window lands on `:1` (sdl2-compat→SDL3 was defaulting to Wayland).
- [x] vitaGL backend + `TEAVM_VITA` + hardware boot

## M3b — First boot on hardware ✅

- [x] **Full tree cross-compiles & links for the Vita → `STSVITA.vpk`.** The
      `TEAVM_VITA` runtime patch (`tools/vita_patch_runtime.py`, auto-applied by
      the gradle `transpile` task) activates TeaVM's homebrew `TEAVM_PSP` paths
      (malloc heap, setjmp fibers, `mktime`, `sceKernelLibcTime`), fixes the
      `pspkernel.h`→`psp2` include, adds `<stdint.h>` to the `uchar.h` shim, and
      re-enables the POSIX `file.c` branch (Vita newlib → sceIo). CMake fixes:
      `SHELL:` on `-include` (de-dup bug), build from `all.txt` not a `*.c` glob
      (skips TeaVM's dead files), all glue sources + freetype + `stdc++` + the
      startup sce stubs (`ScePower`/`SceKernelThreadMgr`/`SceProcessmgr`/
      `SceLibKernel`/`SceIofilemgr`), `getpwuid` stub (`vita_compat.c`), dropped
      the absent `vglEnd()`. Produces a 7 MB `eboot.bin` (valid SELF) / 335 MB
      VPK. NOT yet booted on hardware.
- [x] Two build flavours (one eboot): `make vpk` = full self-contained VPK
      (~335 MB, assets at `app0:/assets`); `make vpk-slim` = code-only VPK
      (~7 MB, reinstalls in seconds) that reads assets from
      `ux0:data/stsvita/assets`. `VitaFiles` auto-detects the base (app0 else
      ux0), so the same binary serves both. `make deploy-assets VITA_IP=..`
      uploads the payload to ux0: over VitaShell FTP (`tools/deploy_assets.py`).
- [x] LiveArea generated from the user's game files at build time
      (`pipeline/livearea.py`, `make livearea`, ImageMagick): 128×128 bubble
      icon (logo over the title scene), 840×500 background (title scene,
      black atlas padding trimmed), 280×158 "Slay the Spire" start gate, plus
      an "a1" `template.xml`. Packed via CMake `SCE_SYS` (work/sce_sys, falls
      back to the placeholder native/sce_sys). Nothing game-derived committed.
- [x] vitaGL init + VitaGL20 binding; SpriteBatch draws; **main menu + save-slot
      screen render on hardware** (2026-07-11)
- [x] VitaFiles over app0:/ux0: (assets load from ux0 slim payload); preferences
      read/write via the save path
- [x] Input: front touch, native controller mode with PlayStation glyphs, and
      `sceImeDialog` name entry work on hardware. Seed entry and optional rear
      touch trigger mappings remain polish items.
- [~] Real heap/pool, GC pauses, FPS, and load times have extensive diagnostic
      coverage; a reproducible release performance suite remains an M5 item.
- Exit criterion met: menu and full gameplay run at accepted frame rates on
      physical hardware.

### Status 2026-07-11 — menu reached, into a run

The port now boots to the menu on hardware and starts a run. Fixes that got it
there (all on branch `m4-dds-and-32bit-reflection-fix`):
- **32-bit class-pointer bug (the big one):** object headers pack the class ptr
  as `(addr-base)>>3`, assuming 8-byte-aligned class structs; the dynamic
  array-class pool element is 212 B on 32-bit (not /8), so unpacking returned a
  misaligned class → `itemType` NULL → all array reflection (clone,
  getComponentType, getDeclaredFields) broke. Fix: 8-align the pool
  (`vita_patch_runtime.py`). This was the cards.atlas NPE / early crash.
- **.dds pipeline (M4 texture work) landed** and works: atlases fit VRAM, peak
  pixmap ~9 MB. Compressed-upload path got the same pool-full guard as RGBA
  (was faulting → C2-12828). `oldCards` (20 MB, never shown) stubbed to 4x4 for
  headroom.
- **Frame clock:** `nanoTime` used `sceKernelLibcTime` (1 s resolution) →
  `deltaTime`≈0 → menu rendered but never faded in (black). Fixed to
  `sceKernelGetProcessTimeWide` (µs).
- **Heap rebalance** Java 192→144 MB (freed by .dds) → native headroom for
  FreeType (was OOMing on fonts).
- **`Class.newInstance` → `NewInstanceRegistry`** so map room types instantiate
  (Embark → map worked).

Remaining before "playable" (now M4): the **auto-save crash** (TeaVM
`Float.toString` classlib bug on room transition — current blocker), **audio**
(silent), **SceCtrl buttons + real on-screen keyboard**, and the **green-box
font** (glyph pixel-format).

## M4 — Full game playable ✅ (hardware accepted 2026-08-08)

- [x] Audio (OpenAL music streaming + SFX/WAV support); music streams from its
      path instead of being copied through the Java heap. Hardware has verified
      music, ambience, SFX, and the software-safe gain path.
- [x] Spine characters/monsters, scene backgrounds, cards, UI, chests, and VFX
      render with the current full/half-resolution asset policy. Do not weaken
      the full-resolution exceptions without new physical-Vita evidence.
- [x] **Complete Act 1 run without crash** — done (full act + boss, ~60fps).
      Saves persist; save/power-cycle/Continue was subsequently verified.
- [x] **Act 2 entry stability gate — PASSED 2026-07-17** on the 240MB-newlib
      / 112MB-Java / 77MB-RAM-pool rebalance (native budget unchanged at
      128MB). Hardware evidence (log sha c093aba2): RAM pool 77MB, boot
      spill 0KB all session, all five City layers admitted in-pool at
      half-data (native res, user-accepted visuals), shop rug + merchantHand
      + VFX load, ~60fps City combat, 5 GC pauses >=15ms total, clean
      shutdown, no core. Follow-ups fixed and hardware-verified the same day:
      the act-title "i" bug (stale compressed-id marks on recycled GL ids —
      glyph sub-uploads silently dropped; unmark on delete/redefine) and
      relic-view/deck-browse SD-read hitches (20MB LRU image byte cache in
      VitaFileHandle).
- [x] **Acts 1–4 + Corrupt Heart clear on hardware** (2026-07-19): a full
      campaign reached the victory screen with zero log defects; shops,
      events, campfires, maps, chests, VFX, and save/power-cycle/Continue were
      exercised. Ironclad, Silent, and Defect were covered in that soak.
- [x] **All four characters exercised.** On 2026-08-08 the user reported
      completing a run with the newly available character—the remaining
      Watcher coverage item from session 19. The City title and floor were
      correct and FPS felt stable throughout. A fresh log hash, exact pool/
      spill counters, and the optional late-act power-cycle repetition were
      not captured and remain audit items, not M4 blockers.
- [~] Measured performance work — phase/IO/GC/texture diagnostics exist, but
      repeatable frame-time percentiles, transition timings, pool high-water
      marks, and matched before/after scenarios are still required. Current FPS
      is user-accepted; optimize later only from a matched measured scenario.

Historical update 2026-07-12: real controller mode (gdx-controllers pad +
cursor via setCursorPosition), PS4 glyphs, sceImeDialog auto-keyboard on
the name field, WAV SFX, the 32-bit array-data GC bug (root cause of the
volume-0% digit corruption), NPOT atlas-page padding (Ironclad/monster
scale), jpg→dds (menu sky, 40s menu stall). Memory-ceiling learning: the
280MB newlib heap initially appeared load-bearing because vitaGL draw buffers
live in its free space. Later measured work safely moved to the accepted
240MB-newlib/112MB-Java/77MB-pool balance without reducing the 128MB native
budget. Current M5 detail is in the milestone section below.

## M5 — Polish, contributor UX & release

The foundation is largely complete: one supported build command, canonical
full/slim packages, input fingerprinting, generated LiveArea art, external
asset deployment, validation, and manifested identities all work. The short
release-critical path is now:

1. **Steam-progress transfer, landed and awaiting hardware confirmation:**
   `tools/import_progress.py` discovers or accepts a desktop install (Steam
   libraries incl. secondary drives, GOG), imports only allowlisted files under
   `saves/` and `preferences/`, never reads or writes `assets/`, defaults to a
   dry run, backs the Vita up to a timestamped host folder before any write,
   verifies every file by SHA-256 read-back, and supports both local staging
   and VitaShell FTP plus a `--rollback-from` restore. 36 synthetic-fixture
   tests cover dry-run, apply, slot conflicts, traversal/symlink rejection,
   asset exclusion, checksum mismatch, interrupted transfer, and FTP via a fake
   server. Validated host-side against a real Steam install (94 files,
   byte-identical, source unmodified). **Still to do: one import onto the
   physical Vita.**
2. **Release cleanup and installation UX:** strip removable diagnostics,
   finish the full-versus-slim install/upgrade/troubleshooting guide, and make
   manifest verification approachable. Keep the accepted memory/visual/audio
   policies unchanged.
3. **Coverage follow-ups:** sleep/resume, a late-act power-cycle repetition,
   unlock/stat import verification, daily/custom modes, and measured
   performance scenarios. These extend confidence rather than reopen M4.
4. **Release automation:** synthetic-fixture CI, repeatable hardware test
   checklist/results, license/source notices, tagged artifacts that contain no
   game content, and upstreamable TeaVM Vita patches.

Performance work remains evidence-driven within those gates. Fix only a
largest repeatable hitch at a time. The accepted default is 32kbps/24kHz;
48kbps remains the higher-fidelity option and is not a stability requirement.

- [x] Canonical root build interface produces stable full/slim names from the
      same generated-C and asset trees; strict input fingerprinting and an
      explicit native-only incremental mode are included
- [x] Automated package validation + build manifest: readable archives,
      required executable/LiveArea files, exact full assets, asset-free slim,
      identical executable bytes, PNG/DDS sibling policy, asset counts, tool
      versions, repository/input fingerprints, and output hashes
- [~] All four characters exercised and completed campaigns proven; repeated
      full runs per character, daily/custom modes, and endless soak remain
- [x] LiveArea and native loading art generated from the user's own game files
- [ ] Rear-touch policy and sleep/resume handling
- [~] Guided, backup-first Steam/GOG progress importer + restore guide for
      `ux0:data/stsvita/{saves,preferences}`: tool, tests and docs done and
      host-validated; one physical-Vita import still outstanding
- [ ] CI that builds the pipeline (with a synthetic fixture jar — never the game)
- [ ] Reproducible hardware performance suite: cold boot/menu, Act 2 Continue,
      combat card/VFX, map/relic/potion/reward/shop/campfire, transition/save,
      complete-act and multi-act soak
- [ ] Publish; upstream `TEAVM_VITA` patch to TeaVM

## Explicit non-goals

- Mods/ModTheSpire (needs runtime classloading — impossible under AOT)
- Multiplayer/leaderboards (Steam-tied), Twitch/Discord integration
- Shipping any Mega Crit content in this repo, ever
