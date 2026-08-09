# libGDX 1.9.5 Vita backend

A libGDX backend written in Java, AOT-compiled together with the game by
TeaVM, that talks to Vita hardware through C functions (`native/src/glue/`)
imported with TeaVM's `@Import`/`@Include` interop — the same pattern
gdx-teavm's `backend-glfw` uses for desktop GL.

It implements the **1.9.5** backend interfaces (the version bundled in the
game jar), not modern libGDX — the `Graphics`/`Input` interfaces changed
between 1.9 and 1.14.

| Class | Status | Notes |
|---|---|---|
| `VitaApplication` | hardware-proven | main loop, lifecycle, postRunnable queue |
| `VitaGraphics` | hardware-proven | vitaGL context via glue, fixed 960×544 |
| `VitaGL20` | hardware-proven | `@Import` bindings into vitaGL (GLES2 surface) |
| `VitaInput` | hardware-proven | SceCtrl controller API, SceTouch pointer, Vita IME |
| `VitaAudio` | hardware-proven | OpenAL-soft; OGG music streaming and preloaded SFX/WAV |
| `VitaFiles` | hardware-proven | internal → `app0:/assets/`, local/external → `ux0:data/stsvita/`, bounded image cache |
| `VitaPreferences` | incomplete/unused by StS | compatibility stub; the game uses its own local progress files through `VitaFiles` |
| `VitaNet` | done | permanent no-op |

Physical-Vita coverage includes Acts 1–4, the Corrupt Heart, all four
characters, and save/power-cycle/Continue. The next storage feature is a
host-side, backup-first importer for the desktop game's `saves/` and
`preferences/` trees; it should not be built around `VitaPreferences` unless
that currently unused class is completed and proven necessary.

`stubs/` shadows jar-bundled integrations out of existence (classpath order:
stubs → backend → user jar): steamworks4j, discord-rpc, Twitch (twirk), JNA.
Only the classes/members the TeaVM linker actually reaches need to exist —
the M2 workflow is: run `make transpile`, add whatever member it reports
missing, repeat until link-clean.
