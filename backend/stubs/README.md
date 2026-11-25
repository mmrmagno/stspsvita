# Linker stubs

No-op replacements for desktop-only libraries bundled inside the game jar.
They sit **first** on the TeaVM classpath, shadowing the real classes, so the
game's integration code compiles/links but takes its built-in "not available"
paths (the same ones it uses when Steam isn't running on desktop).

To add one, run `make transpile`. TeaVM reports each missing class or member with the
call chain that reaches it. Only what it reaches needs to exist.

Packages shadowed:

- `org.apache.logging.log4j` - loggers print to stdout; keeps log4j-core's
  reflection/JMX machinery (~170 link errors' worth) out of the build
- `com.codedisaster.steamworks` - steamworks4j (`SteamAPI.init()` → false);
  the `*Native` JNI classes are additionally denativized by ClasslibShims
- `net.arikia.dev.drpc` - discord-rpc
- `de.robojumper.ststwitch` - Twitch integration (config reports absent)
- `com.google.gson` - full facade over libGDX's JsonReader: TeaVM's C
  backend has field reflection but no generic-type/method/constructor
  reflection, so TypeToken resolution and instantiation come from the
  build-time type manifest generated from the user's jar
- `com.badlogic.gdx.utils.SharedLibraryLoader` / `GdxNativesLoader` - no
  native libs exist in an AOT build
- `com.badlogic.gdx.controllers.Controllers` - the real one discovers
  managers via Class.forName; `VitaInput` registers the Vita gamepad here

Game-package shadows (policy: only zero-game-logic plumbing, each documented
in-file): `AsyncSaver` (LinkedBlockingQueue → synchronous writes),
`BotDataUploader` (telemetry, java.net), `DisplayConfig` (desktop display
negotiation via java.util.Scanner; every value is a hardware constant here).

Build-time IR transforms (`transpiler/.../ClasslibShims.java`): Scanner →
WordScanner retarget in game classes, Class.getResource/Locale shims,
method-reflection bodies neutralized, JNI denativization.
