# STSVITA Architecture

## The problem

Slay the Spire (desktop) is a Java 8 / libGDX 1.9.5 game shipped as one fat jar
(`desktop-1.0.jar`, ~349MB: ~2,300 game classes under `com.megacrit.cardcrawl`,
bundled libraries, and all assets). The PS Vita (ARMv7-A Cortex-A9, ~365MB
usable RAM with the enlarged-memory attribute, 128MB CDRAM for textures, newlib
libc, no JVM) cannot run Java.

## The approach: whole-program AOT via TeaVM's C backend

We compile the game's bytecode — taken from the **user's own jar at build
time** — to portable C11 with [TeaVM](https://github.com/konsoletyper/teavm)
(v0.15+), then compile that C with VitaSDK. This was chosen over the
alternatives after a full feasibility survey ([RESEARCH.md](RESEARCH.md)):
JVM interpreters are far too slow (cf. Balatro-on-LÖVE at ~20fps), GraalVM has
no 32-bit ARM support, RoboVM deleted armv7, and Android so-loader is
categorically inapplicable (libGDX Android logic is DEX, and the official
Android build is Sickhead's C# port anyway).

Why TeaVM specifically:

- **C backend is maintained and growing**: PSP homebrew support (32-bit,
  newlib, no mmap — every problem the Vita has) was merged upstream Jan 2026
  (konsoletyper/teavm#1138). A `TEAVM_VITA` variant is nearly mechanical.
- **Precise generational mark-compact GC** — matters at our RAM budget.
- **Build-time reflection metadata** (opt-in per class) covers StS's
  reflection use, which is light by design: cards/relics/monsters are
  registered by direct instantiation (`CardLibrary.addRedCards()` …); only
  gson-driven save/localization JSON and a few `newInstance` calls reflect
  (~27 classes touch gson).
- **Missing classlib APIs fail at build time with exact call chains** — the
  porting surface is enumerable, not discovered at runtime.
- **gdx-teavm** (xpenatan) proves the pattern of writing libGDX backends in
  Java that call C directly through TeaVM's `@Import`/`@Include` interop
  (its `backend-glfw` does exactly this with GLEW; `gdx-freetype-c` exists).

Green threads: TeaVM's C runtime provides `Thread`/`synchronized`/`wait/notify`
as cooperative fibers on one OS thread. StS's thread use (async savers, Steam
tickers, metrics) is either strippable or fiber-safe.

## Components

### 1. `pipeline/` — asset & jar processing (Python)

- `verify.py` — fingerprints the user's jar (size/SHA-256/manifest/libGDX
  version); warns on unknown builds. Supported reference: V2.3.4, jar dated
  2022-12-20, libGDX 1.9.5.
- `extract.py` — splits the jar: assets → `work/assets_raw/`, keeps the jar
  itself as the TeaVM classpath input.
- `assets.py` — produces `work/assets_vita/`:
  - **Textures: logical dimensions unchanged, memory via DXT and selective
    reduced block data.** StS mixes draws sized by atlas-region dimensions
    with draws sized by constants × `Settings.scale`, so DDS headers preserve
    the authored dimensions for layout/UV math. Most compatible art carries
    half-size DXT5/DXT1 block data for a native-screen-resolution upload;
    device-proven UI/card/VFX/chest exceptions stay full. The loader infers
    the actual upload dimensions from the compressed payload size. (A
    quarter-data candidate for the five City parallax PNGs was tried
    2026-07-16 and visually rejected on hardware — half-data is native screen
    resolution, quarter is a visible downgrade; they are back at half-data
    and admission headroom comes from the 112MB-Java/240MB-newlib rebalance.)
    vitaGL uploads these via `glCompressedTexImage2D`, and the
    backend's Pixmap/TextureData replacement prefers a `.dds` sibling.
  - **Fonts**: 172MB of the jar is fonts, ~160MB of it CJK (`font/zhs`,
    `zht`, `jpn`, `kor`). Default build keeps only the requested languages'
    fonts (English-only ≈ 12MB of fonts).
  - **Audio**: OGGs default to a fatal/atomic 32kbps libvorbis pass resampled
    to 24kHz (hardware-listened and accepted); 48kbps retains the source rate
    for higher fidelity, and `AUDIO_KBPS=0` copies originals. Music streams
    from the selected app0/ux0 asset root, while SFX are preloaded.
  - **Strip**: desktop natives (dll/so/dylib), `win-x86/…` dirs, mod/Twitch
    assets.
- Result: ~349MB jar → ~100MB VPK payload (English-only).

### 2. `backend/` — libGDX Vita backend (Java, compiled with the game)

Implements libGDX **1.9.5**'s backend interfaces (not modern 1.14 — the
interfaces changed) against Vita native APIs via TeaVM interop:

| Class | Implements | Native side |
|---|---|---|
| `VitaApplication` | `Application` | main loop, lifecycle, fiber-friendly `postRunnable` |
| `VitaGraphics` | `Graphics` | vitaGL context, 960×544, vsync |
| `VitaGL20` | `GL20` | `@Import` straight into vitaGL (GLES2-level) |
| `VitaInput` | `Input` | SceCtrl buttons/sticks + SceTouch front panel → pointer events |
| `VitaAudio` | `Audio` | OpenAL-soft (vitasdk package); `VitaMusic` streams OGG via stb_vorbis, `VitaSound` preloads |
| `VitaFiles` / `VitaFileHandle` | `Files` | `app0:` (internal/assets) + `ux0:data/stsvita/` (local/external: saves, prefs, logs) |
| `VitaNet` | `Net` | permanent no-op |
| `VitaPreferences` | `Preferences` | Unimplemented stub, and unreached in practice: StS keeps progress in its own `saves/`+`preferences/` files via `SaveHelper`/`Prefs` through `VitaFiles`, and only the dead `saveMigration` path calls `getPreferences()` |

`backend/stubs/` replaces jar-internal integrations with no-ops that satisfy
the TeaVM linker: `com.codedisaster.steamworks.*` (SteamAPI.init → false, so
the game takes its existing non-Steam paths), `net.arikia.dev.drpc.*`
(Discord), `com.gikk.twirk.*` + `de.robojumper.ststwitch.*` (Twitch),
`com.sun.jna.*`. Stub classes are put **before** the game jar on the TeaVM
classpath, shadowing the originals.

Shaders: StS has 7 tiny GLSL pairs (blur, outline, water, silhouettes,
grayscale) + libGDX's SpriteBatch default. vitaGL translates GLSL→CG at
runtime via vitaShaRK; any that mis-translate get hand-written CG versions
in `native/shaders/` selected by filename at `glCompileShader` time.

### 3. `transpiler/` — TeaVM harness (Gradle)

- `VitaLauncher` replaces `com.megacrit.cardcrawl.desktop.DesktopLauncher` as
  the main class: constructs `CardCrawlGame` inside `VitaApplication`, forces
  960×544, `Settings.scale = 0.5`, controller-mode UI on, non-Steam mode.
- Classpath order: `stubs` → `backend` → **user's `desktop-1.0.jar`** →
  TeaVM classlib. The LWJGL/desktop-backend classes inside the user's jar are
  simply never reached, so the AOT tree-shake drops them.
- Reflection config: a `ReflectionSupplier` whitelists the gson-deserialized
  model classes (localization strings, save fields) discovered at build time.
- Output: `work/generated_c/` — portable C11 + TeaVM runtime.

### 4. `native/` — VPK build (C + CMake)

- `main.c`: enlarged-memory mode (`ATTRIBUTE2=0x0C` in param.sfo +
  240MB newlib heap containing the 112MB TeaVM heap plus native malloc/draw
  headroom), init
  vitaGL with CDRAM pools, mount paths, hand off to the TeaVM entry point.
- `glue/*.c`: the C functions the Java backend `@Import`s — thin wrappers
  over vitaGL, SceCtrl/SceTouch, OpenAL, sceIo, sceRtc.
- CMake (VitaSDK toolchain) compiles generated C + glue, links vitaGL,
  vitaShaRK, mathneon, OpenAL, and produces `STSVITA.vpk` with LiveArea
  assets from `native/sce_sys/`.

### 5. `teavm-patches/`

The `TEAVM_VITA` runtime patch (clone of the merged `TEAVM_PSP` ifdefs:
malloc-based fixed heap instead of mmap, no ucontext fibers, newlib quirks).
Goal is to upstream it and keep this directory empty.

## Memory budget (the #1 risk — measured, not guessed)

**Accepted hardware configuration (2026-07-17 onward):** TeaVM Java heap
112MB, newlib heap 240MB, vitaGL RAM texture pool about 77MB, VRAM 96MB, and
PHYCONT about 26MB. The rebalance kept the native non-Java budget unchanged,
dropped boot texture spill from roughly 6MB to 0KB in the acceptance log, and
admitted all five native-resolution City layers. Acts 1–4 plus the Heart and
all four characters have since run on physical hardware.

**Experiment (2026-07-02, real V2.3.4 jar, bundled JRE 8):** the game boots
to the main menu and fully loads the card library with **`-Xmx256m`** — no
OOM. Desktop RSS (~580MB) is JVM machinery (JIT, metaspace, direct buffers)
plus GL-driver texture copies, none of which exist in an AOT build.

| | Accepted Vita budget (enlarged mode) |
|---|---|
| newlib malloc heap | 240MB total |
| TeaVM Java heap | 112MB committed inside newlib |
| Native malloc + vitaGL draw fallback | ~128MB budget inside the same newlib heap |
| vitaGL RAM texture pool | ~77MB carved from remaining USER_RW |
| vitaGL VRAM / PHYCONT pools | ~96MB CDRAM / ~26MB PHYCONT |
| Audio buffers + FreeType | charged to native newlib headroom |

The three-way 240/112/77 balance is hardware-accepted and draw-buffer
headroom is load-bearing. Do not broadly retune it; use matched physical logs
to identify a concrete allocation or lifetime issue first.

## Performance expectation

Card game logic at AOT-native speed on 3× Cortex-A9 @ 444–500MHz is not the
bottleneck; watch items are GC pauses (generational GC + a steady-state heap
well under the cap should keep minor collections in single-digit ms), Spine
skeleton updates (NEON-compiled float math), and SpriteBatch flush counts
(vitaGL is mature here — 80+ shipped ports).

## What other owners do

`git clone` → install the documented toolchain →
`./build.sh --sts-dir <their install>` → install the full VPK, or deploy the
generated external asset tree and use the matching slim VPK. The root script
preflights dependencies and orchestrates the Make stages; it does not duplicate
pipeline/CMake logic. Both packages are validated against one generated-C tree
and one asset tree, and `build/build-manifest.json` records the exact input,
options, tools, repository state, asset counts, and output hashes. Unknown jar
fingerprints are rejected by default rather than failing later in transpilation.

### Progress storage

Writable progress lives below `ux0:data/stsvita/`, in the two folders the
desktop game also uses:

- `saves/<CHARACTER>.autosave` (+ `.backUp`): the resumable run
- `preferences/STS*` (+ `.backUp`): unlocks, achievements, seen
  cards/relics/bosses, per-character stats, settings, and the active slot

Slot 0 is unprefixed; slots 1 and 2 carry a `1_` / `2_` filename prefix
(`SaveHelper.slotName`).

Both trees are reached through `SaveHelper` -> `Prefs` -> `Gdx.files.local()`
-> `VitaFileHandle`, i.e. ordinary file IO on JSON the game writes itself. The
`Preferences` interface is *not* on this path: `Gdx.app.getPreferences()` is
only called from `CardCrawlGame.migrateHelper`/`saveMigration`, the legacy
one-time migration off libGDX's XML preferences. That is why `VitaPreferences`
being an unimplemented stub costs nothing today, and also why implementing it
would not, on its own, move any progress.

GOG and beta desktop builds store preferences in `betaPreferences/` rather
than `preferences/` (`SaveHelper.getSaveDir()` returns it when
`Settings.isBeta || isGog()`). The Vita build resolves its distributor from
`BuildSettings` and always uses `preferences/`, so a GOG source folds into
that name on import.

`tools/import_progress.py` implements the release-facing transfer on exactly
these rules: an explicit filename allowlist, never reading or writing the
external `assets/` tree, backup-before-write to a timestamped host folder,
all-or-nothing slot semantics, and a SHA-256 read-back of every file after
local copy or FTP upload.
