# STSPSVITA

Play your own copy of Slay the Spire on a PS Vita.

## Why

I wanted to play Slay the Spire on my Vita and there was no way to do it. The PC
version is a Java game and the Vita can't run Java, so I compile the game's
bytecode to native ARM code instead.

## What it does

There are two parts:

- **The launcher**. It checks that everything is in place and starts the game.
- **The build**, which runs on your PC. It takes `desktop-1.0.jar` from your own
  copy of Slay the Spire and makes the game files, plus a `SlayTheSpire.vpk` that
  has the launcher with the game's own bubble and LiveArea.

The `STSPSVITA.vpk` in the releases is the same launcher with plain art and
nothing from the game in it.

I played it through all four acts with all four characters on a real Vita, and I
tested this version on a PCH-1000 and a PCH-2000. I have not tested it on a PS TV.

Don't share your build output. It is the game, compiled from your copy.

## What you need

- A hacked PS Vita with VitaShell and unsafe homebrew enabled.
- `libshacccg.suprx` on the Vita. [PIB Configuration Tool](https://github.com/SonicMastr/PIB-Configuration-Tool)
  installs it for you, ShaRKF00D works too. The launcher tells you if it is missing.
- Slay the Spire for PC, V2.3.4. I build from the Steam Linux version. Other
  versions are refused unless you pass `--allow-unsupported-input`.
- Docker or Podman, about 4 GB of disk for the image and 6 GB of free RAM.

## Install

1. Build the game files. Replace the first path with your Slay the Spire folder:

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

   The container has no network access and can only read your game folder.

2. Copy the contents of `out/` to `ux0:` on the Vita. You should end up with
   `ux0:SlayTheSpire.vpk`, `ux0:data/stsvita/game.manifest` and
   `ux0:data/stsvita/assets/`.

   Over Wi-Fi, press SELECT in VitaShell and run this from a clone of this repo.
   It only sends what changed:

   ```sh
   tools/deploy_assets.py --src out/data/stsvita --host <vita-ip>
   curl -T out/SlayTheSpire.vpk ftp://<vita-ip>:1337/ux0:/
   ```

3. Install `ux0:SlayTheSpire.vpk` with VitaShell. If you already installed
   `STSPSVITA.vpk`, this replaces it.

4. Start Slay the Spire. The first start installs the game and takes a bit longer.

Your saves are in `ux0:data/stsvita/saves` and `ux0:data/stsvita/preferences`.
Updating or reinstalling doesn't touch them.

## Updating

Pull the new builder image, build again, copy `out/` over the old files and install
`SlayTheSpire.vpk` again. Hold SELECT while the launcher starts to see which build you have.

## Build options

Put these at the end of the `docker run` command:

| Option | What it does |
|---|---|
| `--langs eng,jpn` | Keep more languages. The default is English only, the other fonts add about 160 MB |
| `--audio-kbps 48` | Better audio. The default is 32, and 0 copies the original files |
| `--allow-unsupported-input` | Try a game version I haven't tested |

## Moving your PC progress over

The port stores its writable game data under `ux0:data/stsvita/`, in the same
two folders and the same file formats the PC release uses:

| Folder | Contents |
| --- | --- |
| `saves/` | `<CHARACTER>.autosave`, the run you can Continue |
| `preferences/` | unlocks, achievements, seen cards, relics, bosses, stats, settings |

The port reads these through its own file layer, so PC bytes are Vita bytes and
no conversion is needed. `tools/import_progress.py` copies them across safely.

Close STSVITA on the Vita before importing. The game rewrites its own progress
when it exits, which would undo the import.

Game assets are never part of a progress transfer. The tool only reads `saves/`
and `preferences/`, and only writes those two folders. Nothing under `assets/`
is touched, and importing progress never installs or repairs a game build.

### Point it at your PC install

```sh
tools/import_progress.py --auto --ftp-host <your-vita-ip>
```

`--auto` searches your Steam library folders, including libraries on other
drives, and the usual GOG locations. If it finds more than one install it lists
them and asks you to choose. You can always name the folder yourself:

```sh
tools/import_progress.py --source "$HOME/.steam/root/steamapps/common/SlayTheSpire" \
  --ftp-host <your-vita-ip>
```

GOG and beta builds keep progress in `betaPreferences/` rather than
`preferences/`. The tool detects this and folds it into the Vita's
`preferences/` folder. Force the choice with `--source-prefs-dir betaPreferences`
if you ever need to.

### Enable VitaShell FTP

On the Vita, open VitaShell and press SELECT. It shows an address such as
`ftp://192.168.1.42:1337`. Pass the IP as `--ftp-host`. The default port is
already `1337`, so `--ftp-port` is only needed if yours differs.

You can also write to a folder instead, which is useful when you copy the SD
card directly:

```sh
tools/import_progress.py --auto --dest-dir /run/media/you/vita/data/stsvita
```

### Preview, then apply

Nothing is written without `--apply`. The default run is a preview that lists
every file it would add or replace:

```sh
tools/import_progress.py --auto --ftp-host 192.168.1.42
tools/import_progress.py --auto --ftp-host 192.168.1.42 --apply
```

Before writing anything, the current Vita progress is copied to
`progress-backups/<timestamp>/` on your PC together with a checksum manifest.
Every file written is then read back and verified by SHA-256. A failed transfer
or a checksum mismatch stops the run and prints the rollback command.

### Save slots

Slay the Spire has three save slots. Slot 0 has no filename prefix; slots 1 and
2 are prefixed `1_` and `2_`. The tool keeps them apart:

- A slot the Vita does not have yet is imported.
- A slot whose files are already byte-identical is skipped.
- A slot that exists on the Vita with different contents is reported as a
  conflict and left completely alone.

Conflicting slots are never partially written. Importing only the differing half
of a slot would splice PC saves onto Vita preferences and produce a run that
neither install ever had. Overwrite deliberately:

```sh
tools/import_progress.py --auto --ftp-host 192.168.1.42 --apply --replace-slots 0,2
tools/import_progress.py --auto --ftp-host 192.168.1.42 --apply --replace-all
```

### Undo an import

Every applied import prints the exact command that reverses it:

```sh
tools/import_progress.py --ftp-host 192.168.1.42 \
  --rollback-from progress-backups/20260809T101500Z --apply
```

A rollback also previews without `--apply`, and it refuses a backup whose files
no longer match its manifest. It puts back every file the import changed. Files the
import added stay on the Vita.

### Progress Transfer Troubleshooting

`no Slay the Spire install found automatically`

Your install is in a non-standard path. Pass `--source <dir>`.

`several installs found`

Choose one with `--source`.

`both preferences/ and betaPreferences/ hold progress`

Pass `--source-prefs-dir` to say which one to read.

`cannot reach destination`

VitaShell FTP is not running, the IP is wrong, or the Vita went to sleep. Open
VitaShell and press SELECT.

Slots are reported as conflicts

This is expected when both sides have been played. Use `--replace-slots` or
`--replace-all` once you have decided which progress wins.

The import applied but the Vita still shows the old run

STSVITA was still running and rewrote its progress on exit. Close it, roll back,
and import again.

`checksum mismatch after write`

The transfer was corrupted. Nothing is trusted after this. Roll back and retry.

Files that are not recognised progress, such as `steam_autocloud.vdf`,
symlinks, directories, and anything oversized or unknown, are skipped and
listed by name rather than copied silently. Run history under `runs/` is not
transferred, because the game does not need it to restore a save or an unlock.

To run the tool's tests, which use synthetic fixtures and never touch game
data:

```sh
make test
```

## Building without the container

You need VitaSDK, JDK 17 or newer, Gradle, CMake, Make, Python 3.9 or newer, GCC,
ImageMagick 7 and ffmpeg. The container pins the vitaGL and vitaShaRK versions
I tested with, see `docker/Dockerfile`.

```sh
./build.sh --sts-dir /path/to/SlayTheSpire
make launcher
make test
```

The files for the Vita end up in `out/`. After one full build, `--incremental`
rebuilds only the native code.

## Troubleshooting

| What you see | What to do |
|---|---|
| Shader compiler missing | Install `libshacccg.suprx`, see above |
| Game not installed | Copy the contents of `out/` to `ux0:` |
| Assets do not match | The copy didn't finish, or the assets are from another build. Copy all of `out/` again |
| Writing to the app folder failed | Restart the Vita and try again |
| `/out is not writable` | Create `out` first and keep the `--user` flag |
| The build runs out of memory | Give Docker Desktop or WSL at least 6 GB |

If the game crashes, please open an issue with `ux0:data/stsvita/stsvita.log`. If
you already started it again, the log you want is `stsvita.prev.log`.

## Legal

Slay the Spire is copyright Mega Crit Games. This project is unofficial and not
affiliated with or endorsed by Mega Crit. The launcher, the builder image and this
repo contain no game code and no game assets.

My code is MIT, see [LICENSE](LICENSE). The libraries it uses are in `LICENSES/`.
