# STSPSVITA

**STSPSVITA** runs Slay the Spire on the PS Vita.

The shape is simple: your `desktop-1.0.jar` goes in, one container builds the game for the
Vita, and a small launcher starts it. Nothing from the game is in this repo or in the
releases.

## Why

I wanted to play Slay the Spire on my Vita and there was no way to do it. The PC version is
a Java game and the Vita cannot run Java (or at least not without emulation), so I compile the game's bytecode to native ARM
code instead.

## What it does

- The whole game: all four acts with all four characters, played through on a real Vita.
- Tested on a PCH-1000 and a PCH-2000. Not tested on a PS TV.
- Saves are the same files as on PC, and a tool copies your PC progress over.
- The launcher says what is missing and how to fix it.
- The build has no network access and can only read your game folder.

The build makes `SlayTheSpire.vpk`, the launcher with the game's own bubble and LiveArea.
The `STSPSVITA.vpk` in the releases is the same launcher with plain art.

Keep what you build to yourself. It is the game, compiled from your copy.

## Install

Needs:

- A hacked PS Vita with VitaShell and unsafe homebrew enabled.
- `libshacccg.suprx` on the Vita.
  [PIB Configuration Tool](https://github.com/SonicMastr/PIB-Configuration-Tool) installs it
  for you, ShaRKF00D works too.
- Slay the Spire for PC, **V2.3.4**. I build from the Steam Linux version. Other versions
  are refused unless you pass `--allow-unsupported-input`.
- Docker, about 4 GB of disk for the image and 6 GB of free RAM.

1. Build the game. Replace the first path with your Slay the Spire folder:

   ```sh
   mkdir -p out
   docker run --rm --network none --user "$(id -u):$(id -g)" \
     -v "$HOME/.steam/root/steamapps/common/SlayTheSpire:/game:ro" \
     -v "$PWD/out:/out" \
     ghcr.io/mmrmagno/stspsvita-builder:1.0
   ```

   On Windows, with Docker Desktop in PowerShell:

   ```powershell
   mkdir out
   docker run --rm --network none `
     -v "C:\Program Files (x86)\Steam\steamapps\common\SlayTheSpire:/game:ro" `
     -v "${PWD}\out:/out" `
     ghcr.io/mmrmagno/stspsvita-builder:1.0
   ```

2. Copy the contents of `out/` to `ux0:` on the Vita. Over Wi-Fi, press SELECT in VitaShell
   and run this from a clone of this repo, it only sends what changed:

   ```sh
   tools/deploy_assets.py --src out/data/stsvita --host <vita-ip>
   curl -T out/SlayTheSpire.vpk ftp://<vita-ip>:1337/ux0:/
   ```

3. Install `ux0:SlayTheSpire.vpk` with VitaShell.
4. Start Slay the Spire. The first start installs the game and takes a bit longer.

On Windows, put `python` in front of the `tools/` commands.

## Updating

Pull the new builder image and do the install again. Your saves in `ux0:data/stsvita/` are
not touched. Hold SELECT while the launcher starts to see which build you have.

## Build options

Put these at the end of the `docker run` command:

| Option | What it does |
|---|---|
| `--langs eng,jpn` | Keeps more languages. The default is English only, the other fonts add about 160 MB |
| `--audio-kbps 48` | Better audio. The default is 32, and 0 copies the original files |
| `--allow-unsupported-input` | Tries a game version I have not tested |

## Import PC progress

Your runs, unlocks and stats can come with you. The tool backs up the Vita first and checks
every file after writing it.

1. Close the game on the Vita. It writes its own progress when it exits, which would undo
   the import.
2. In VitaShell, press SELECT. It shows an address like `ftp://192.168.1.42:1337`.
3. Look at what it would do, then run it again with `--apply`:

   ```sh
   tools/import_progress.py --auto --ftp-host 192.168.1.42
   ```

`--auto` looks in your Steam libraries and the usual GOG folders. If it does not find the
game, use `--source "/path/to/SlayTheSpire"`. With the Vita's card in your PC, use
`--dest-dir /path/to/card/data/stsvita` instead of `--ftp-host`.

A slot that is empty on the Vita is imported, and one that is different is left alone. To
overwrite those, use `--replace-slots 0,2` or `--replace-all`.

Every import prints the command that undoes it. That puts back every file the import changed,
but files the import added stay on the Vita.

## Troubleshooting

| What you see | What to do |
|---|---|
| Shader compiler missing | Install `libshacccg.suprx`, see Install |
| Game not installed | Copy the contents of `out/` to `ux0:` |
| Assets do not match | The copy did not finish, or the assets are from another build. Copy all of `out/` again |
| Writing to the app folder failed | Restart the Vita and try again |
| `/out is not writable` | Create `out` first and keep the `--user` flag |
| The build runs out of memory | Give Docker Desktop or WSL at least 6 GB |
| `both preferences/ and betaPreferences/ hold progress` | Pick one with `--source-prefs-dir` |
| `cannot reach destination` | Open VitaShell, press SELECT and check the IP |
| The Vita still shows the old run | The game was still running. Close it, undo and import again |
| `checksum mismatch after write` | The copy went wrong. Undo it and try again |

If the game crashes, please
[open an issue](https://github.com/mmrmagno/stspsvita/issues/new/choose) with
`ux0:data/stsvita/stsvita.log`. If you already started it again, the log you want is
`stsvita.prev.log`.

## How it works

- The jar is checked against the version I tested, then unpacked. Textures are converted for
  the Vita's GPU, audio is re-encoded, and fonts you do not need are dropped.
- TeaVM turns the game's bytecode into C, and VitaSDK compiles that to ARM.
- `backend/` replaces desktop libGDX: graphics through vitaGL, sound through OpenAL, and the
  Vita's buttons, touch screen and files.
- The launcher verifies the game's checksum, copies it into its own app folder once, and
  starts it from there.
- The same jar and the same builder image give the same game, byte for byte.

## Building

`build.sh` does the same without the container. It needs VitaSDK, JDK 17 or newer, Gradle,
CMake, Make, Python 3.9 or newer, GCC, ImageMagick 7 and ffmpeg. The container pins the
vitaGL and vitaShaRK versions I tested with, see `docker/Dockerfile`.

```sh
./build.sh --sts-dir /path/to/SlayTheSpire
```

The files for the Vita end up in `out/`. After one full build, `--incremental` rebuilds only
the native code. `make launcher` builds the public `STSPSVITA.vpk`.

`make test` needs neither the game nor a Vita, and CI runs it on every pull request.

I write about my projects at [marc-os.com](https://marc-os.com) [post on this](https://marc-os.com/blog/stspsvita-building-a-slay-the-spire-vita-pipeline).
