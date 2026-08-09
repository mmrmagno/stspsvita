# Feasibility research (2026-07)

> **Outcome update (2026-08-08):** the proposed architecture succeeded on
> physical hardware. STSVITA has cleared Acts 1–4 and the Corrupt Heart,
> exercised all four characters, survived save/power-cycle/Continue, and now
> runs the accepted City/title/texture configuration at stable user-observed
> FPS. The survey and risk ranking below are retained as historical design
> rationale; current delivery work is tracked in [ROADMAP.md](ROADMAP.md).

Survey of every known way to run a Java/libGDX game on PS Vita homebrew, plus
analysis of the actual game files. Conclusion: **TeaVM C backend + custom
libGDX Vita backend** is the only viable path, and it is genuinely viable.

## Prior art

- **No Slay the Spire Vita port exists or has been attempted**; no Java or
  libGDX game has ever shipped on Vita homebrew (VitaDB: 997 entries, zero
  matches). This project would be a first.
- The entire Java-on-Vita prior art is a 3-day 2016 PoC: soywiz's
  [jmedialayer-psvita-java](https://github.com/soywiz-archive/jmedialayer-psvita-java)
  (Kotlin → C++ via the now-dead JTransc, ran on real hardware).
- The proven libGDX-on-console blueprint is
  [SwitchGDX](https://github.com/SwitchGDX/switch-gdx) (Switch homebrew +
  Xbox UWP) on [Clearwing VM](https://github.com/TheLogicMaster/clearwing-vm)
  (Java→C++, ParparVM lineage). Shipped Mindustry and Shattered Pixel
  Dungeon, with documented GC stutter. No Vita target.
- PortMaster (ARM Linux handhelds *with* real JVMs) rates its open StS
  request "Low" feasibility — nobody has done it even there.

## How the official console ports were done

All five (PS4/Switch/XB1/iOS/Android) were done by **Sickhead Games**
(Tom Spilman's account, preserved via the
[libGDX console-support wiki](https://libgdx.com/wiki/articles/console-support)):
they **first tried direct Java→C++ transpilers for months and failed**
("resulting C++ pretty much didn't work at all"), then converted the game
*and libGDX itself* to C#, wrote a new backend, and AOT-compiled with BRUTE,
their proprietary .NET-IL→C++ compiler (also behind Stardew Valley and
Celeste console ports; BRUTE lists Vita as a target — that's how Stardew
reached Vita officially). Lessons: (1) the game logic is portable — it runs
under a GC in C# on consoles; (2) 2019-era Java transpilers were a tar pit;
today's TeaVM is a different generation of tool, but respect the warning.

## Approaches rejected

| Approach | Why not |
|---|---|
| JVM interpreter (JamVM/OpenJDK Zero port) | No Vita port exists; candidate VMs dead since ~2014; interpreter at 444MHz lands below the Balatro-on-LÖVE ~20fps precedent |
| GraalVM native-image | No 32-bit ARM at all (issue #1329 open since 2019), no newlib, cross-compile hardwired to linux-aarch64/musl |
| RoboVM/MobiVM fork | armv7 codegen deleted upstream in v2.3.24 (Nov 2025); POSIX-heavy runtime; months of VM-internals work |
| Android so-loader (Rinnegatamante method) | Doubly inapplicable: libGDX Android logic is DEX (not loadable native code), and the official StS Android build is Sickhead's C# port anyway |
| Clearwing VM + SwitchGDX pattern | Viable second choice — real libGDX games shipped — but no Vita target exists, GC stutters documented, narrower classlib than TeaVM |
| Manual C++/C# rewrite | Proven (Sickhead) but multi-engineer-years, and a rewrite *is* redistributable game code — incompatible with the ship-no-game-content model |

## Why TeaVM wins

[TeaVM](https://github.com/konsoletyper/teavm) v0.15.0 (June 2026), actively
maintained:

- **C backend** emits portable C11; kept and invested-in while the old WASM
  backend was deleted (0.14.0 added full opt-in reflection; June 2026 added
  `getResourceAsStream`, `String.intern`, synchronized methods).
- **PSP homebrew support merged upstream 2026-01-31**
  ([#1138](https://github.com/konsoletyper/teavm/pull/1138), by xpenatan,
  merged by konsoletyper) — 32-bit, newlib, no mmap, no ucontext: every
  Vita-specific runtime problem already solved behind `TEAVM_PSP` ifdefs.
  `TEAVM_VITA` is nearly mechanical and likely upstreamable.
- Precise **generational mark-compact GC**; green-thread fibers implement
  `Thread`/`synchronized`/`wait/notify` (no OS-thread parallelism).
- Native interop via `@Import`/`@Include`/`Address` — no JNI needed.
- Missing classlib APIs fail **at build time with the exact call chain**.
- [gdx-teavm](https://github.com/xpenatan/gdx-teavm) (active, June 2026)
  already writes libGDX backends in Java calling C directly (`backend-glfw`
  via GLEW imports; `gdx-freetype-c` for fonts; `TeaReflectionSupplier` with
  libGDX defaults). Its C-target audio is unimplemented (MockAudio) — our
  `VitaAudio` must be written from scratch. Note it targets libGDX 1.14;
  StS is 1.9.5, so we implement 1.9.5's backend interfaces ourselves.

## Game-file analysis (user's real V2.3.4 jar, dated 2022-12-20)

- Single fat jar, 349MB: ~2,300 game classes (`com.megacrit.cardcrawl`),
  libGDX **1.9.5** + LWJGL2 backend, Spine runtime (JSON skeletons), gson,
  jorbis/jlayer, steamworks4j, discord-rpc, Twitch (twirk), JNA, log4j.
- Assets in-jar: 2,228 PNGs (86 `.atlas`, max page 2048×2048 RGBA8888),
  432 OGGs (~90MB), **172MB fonts (~160MB CJK OTFs)**, 8MB localization
  JSON, 7 custom GLSL shader pairs, Spine `skeleton.json/.atlas` per
  character/monster.
- **Reflection-light**: cards/relics registered by direct instantiation
  (why ModTheSpire has to bytecode-patch `CardLibrary.add*Cards()`); only
  ~27 classes touch gson; `java.lang.reflect` appears in exactly
  `LocalizedStrings` and `SaveHelper`.
- Thread use: async savers, Steam tickers, metrics uploader, a few screens —
  strippable or fiber-safe.
- Renders against a 1920×1080 reference scaled by `Settings.scale`;
  officially supports 1280×720, so scaled-down layouts are a tested path.
  Vita 960×544 = exactly 0.5× reference.

### Memory experiment (the go/no-go test) — PASSED

2026-07-02, bundled JRE 8, `-Xmx256m`: game reaches the main menu, loads the
full card library, all characters, unlock/stats screens. No OOM. Desktop RSS
~580MB = JVM machinery + GL driver copies, not live heap. Vita's ~365MB
(enlarged-memory attribute) + 128MB CDRAM budget closes.

## Vita platform facts

- [VitaSDK](https://vitasdk.org): maintained (GCC 15.2, newlib, pthread,
  CMake toolchain, C++23).
- [vitaGL](https://github.com/Rinnegatamante/vitaGL) (commits through
  2026-07): GL1.x + GLES2-level shaders over sceGxm; runtime CG compilation
  via vitaShaRK + `libshacccg.suprx` (universal homebrew prerequisite);
  built-in GLSL→CG translator (imperfect — hand-translate the 7 StS shaders
  if needed); VBO/FBO; DXT/PVRTC; 4096² max texture; 80+ shipped ports.
- RAM: 512MB total; homebrew gets ~256MB, ~365MB with the enlarged-memory
  param.sfo attribute; textures in 128MB CDRAM via vitaGL pools.
- CPU: 4× Cortex-A9 (3 usable), 333→444MHz official, 500MHz via PSVshell.
  NEON hard-float.
- openal-soft, SDL2, freetype all packaged in VitaSDK.

## Top risks (ranked)

1. ~~RAM ceiling~~ — **retired** by the 256MB-heap experiment; re-verify on
   hardware at M3.
2. **TeaVM classlib coverage** of StS + libGDX 1.9.5 core + Spine: unknown
   volume of "method missing" build errors. Enumerable on day one of M2 —
   run the transpile and count. (This is where Sickhead's 2019 transpilers
   died; TeaVM's diagnostics make it a grind, not a mystery.)
3. **GC pauses / green threads**: mark-compact over a ~200MB heap at 444MHz;
   async-save and loading threads must cooperate as fibers.
4. **Ecosystem bus factor**: TeaVM-C beyond Win/Linux is months old,
   ~one maintainer; pin exact versions, vendor patches.
5. **Backend correctness**: vitaGL GLSL translation quirks, FBO/scissor/blend
   semantics, from-scratch audio, FreeType CJK RAM pressure.
