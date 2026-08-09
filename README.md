# STSPSVITA

STSPSVITA is a build pipeline for bringing Slay the Spire to PlayStation Vita.
Point it at your own PC copy of the game, run one script, and it produces a
Vita-ready VPK plus optimized assets for your device.

The repo ships only the porting tools and Vita-specific runtime code. It does
not include Slay the Spire game code, game assets, generated VPKs, or generated
asset packs; everything derived from the game is created locally from your own
`desktop-1.0.jar`.

Status: pre-alpha. Expect rough edges, long build times, and hardware-specific
issues.

## No Prebuilt VPK

This project does not ship a ready-made VPK. The Vita executable is generated
from your local `desktop-1.0.jar`, so a public VPK would contain transformed
Slay the Spire game code. The intended distribution model is:

1. Clone this source repo.
2. Provide your own legally purchased `desktop-1.0.jar`.
3. Run the build locally.
4. Install the locally generated VPK on your own Vita.

Do not upload generated VPKs or generated asset folders to GitHub Releases,
VitaDB, forums, Discord, or mirrors.

## Requirements

- PS Vita or PSTV with HENkaku/h-encore.
- `libshacccg.suprx` installed on the Vita.
- A legal PC copy of Slay the Spire containing `desktop-1.0.jar`.
- A Linux, macOS, or WSL2 build machine.
- VitaSDK, with `VITASDK` set and `$VITASDK/bin` on `PATH`.
- JDK 17 or newer.
- Gradle.
- CMake, Make, Python 3, GCC.
- ImageMagick 7, providing `magick`.
- `ffmpeg`, required unless you build with `--audio-kbps 0`.

## Windows Setup

Native Windows builds are not supported. Use WSL2 with Ubuntu.

Install WSL2 from an elevated PowerShell:

```powershell
wsl --install -d Ubuntu
```

Open Ubuntu, then install host build tools:

```sh
sudo apt update
sudo apt install -y \
  build-essential git make cmake python3 \
  openjdk-17-jdk imagemagick ffmpeg \
  unzip zip wget ca-certificates
```

Install Gradle. If your Ubuntu package is modern enough, this is fine:

```sh
sudo apt install -y gradle
```

Check versions:

```sh
java -version
gradle --version
```

Use JDK 17 or newer. If Ubuntu's Gradle package is too old, install Gradle via
SDKMAN or the official Gradle binary instead.

For reliability, keep the repo inside the WSL Linux filesystem, such as
`~/stspsvita`, not under `/mnt/c/...`.

If WSL runs out of memory during TeaVM transpilation, create this file on the
Windows side:

```text
C:\Users\<you>\.wslconfig
```

with:

```ini
[wsl2]
memory=8GB
processors=4
swap=8GB
```

Then restart WSL:

```powershell
wsl --shutdown
```

## VitaSDK Setup

Inside Linux, macOS, or WSL2:

```sh
sudo mkdir -p /usr/local/vitasdk
sudo chown "$USER:$USER" /usr/local/vitasdk

echo 'export VITASDK=/usr/local/vitasdk' >> ~/.bashrc
echo 'export PATH=$VITASDK/bin:$PATH' >> ~/.bashrc
source ~/.bashrc

git clone https://github.com/vitasdk/vdpm
cd vdpm
./bootstrap-vitasdk.sh
./install-all.sh
```

Open a new shell and verify:

```sh
echo "$VITASDK"
arm-vita-eabi-gcc --version
```

If the native link step fails with `cannot find -l...`, install or update the
missing VitaSDK package, then rerun the build.

## Quick Start

Clone the repo and copy in your game jar:

```sh
git clone https://github.com/mmrmagno/stspsvita.git
cd stspsvita

mkdir -p game
cp /path/to/SlayTheSpire/desktop-1.0.jar game/
```

On WSL, a Steam install is usually reachable through `/mnt/c`, for example:

```sh
cp "/mnt/c/Program Files (x86)/Steam/steamapps/common/SlayTheSpire/desktop-1.0.jar" game/
```

Build:

```sh
./build.sh --sts-dir game
```

`build.sh` is a dependency-checking wrapper around the Make targets used during
development. It preflights every required tool, fingerprints your jar, cleans
the previous generated output, and runs the full validated pipeline:

```text
verify -> extract -> assets -> livearea -> transpile -> packages -> validate
```

You can point it at an install folder instead of copying the jar, or name the
jar directly:

```sh
./build.sh --sts-dir "$HOME/.steam/root/steamapps/common/SlayTheSpire"
./build.sh --sts-jar /path/to/desktop-1.0.jar
```

DDS generation, half-resolution texture selection, PNG/JPG fallback handling,
LiveArea generation, TeaVM transpilation, Vita runtime patching, and native VPK
packaging are all performed by this chain. When a valid DDS is generated, its
source PNG/JPG is removed from the processed payload; images that are not good
DDS candidates remain in their original format.

A successful build produces four artifacts from one processed asset tree and
one generated C tree:

- `build/STSVITA.vpk`, the full package with assets bundled inside
- `build/STSVITA-slim.vpk`, the code-only package
- `work/assets_vita/`, the slim package's external asset payload
- `build/build-manifest.json`, recording the input, options, tool versions,
  asset counts, validation results, and output hashes

Install either VPK with VitaShell. The slim package is usually better while you
iterate: install it once, copy the assets once, then reinstall only code.

Audio is re-encoded at 32kbps/24kHz by default. Use `--audio-kbps 48` for higher
fidelity at the source rate, or `--audio-kbps 0` to copy the originals
untouched. `ffmpeg` is only required when the rate is nonzero.

After one full build you can rebuild just the code:

```sh
./build.sh --sts-dir game --incremental
```

Incremental mode reuses `work/assets_vita/` and `work/generated_c/` and emits
only the slim VPK. Run a full build when Java, backend, or runtime-generation
inputs change.

Always install the VPK and `work/assets_vita/` produced by the same clean
build. After updating the port, do not reuse an older processed asset folder.

Copy the contents of `work/assets_vita/` to:

```text
ux0:data/stsvita/assets/
```

Copy the contents, not the `assets_vita` directory itself.

The final layout should look like:

```text
ux0:data/stsvita/assets/build.properties
ux0:data/stsvita/assets/audio/...
ux0:data/stsvita/assets/images/...
```

You can also upload the assets over VitaShell FTP:

```sh
make deploy-assets VITA_IP=<your-vita-ip>
```

For Vita3K, replace its existing asset directory so stale files cannot survive:

```sh
VITA3K_UX0="$HOME/.local/share/Vita3K/Vita3K/ux0"
mkdir -p "$VITA3K_UX0/data/stsvita/assets"
rsync -a --delete work/assets_vita/ "$VITA3K_UX0/data/stsvita/assets/"
```

## Build Options

Keep extra language fonts. CJK fonts alone are about 160MB, so English-only is
the default:

```sh
./build.sh --sts-dir game --langs eng,jpn
```

Higher-fidelity audio at the source sample rate, or no re-encode at all:

```sh
./build.sh --sts-dir game --audio-kbps 48
./build.sh --sts-dir game --audio-kbps 0
```

The build refuses game versions it has not been validated against. If you are
deliberately testing an unknown but structurally compatible jar:

```sh
./build.sh --sts-dir game --allow-unsupported-input
```

Run `./build.sh --help` for all script flags.

## Transferring PC Progress

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
no longer match its manifest.

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
python3 -m unittest discover -s tools -p 'test_*.py'
```

## Manual Make Targets

`build.sh` is the supported interface. The Makefile stays available as the
composable contributor and debugging interface. `make release` is the full
pipeline that `build.sh` drives:

```sh
make release STS_DIR=game
```

For pipeline debugging, the stages can also be run directly:

```sh
make verify STS_DIR=game
make extract STS_DIR=game
make assets STS_DIR=game
make livearea STS_DIR=game
make transpile
make native BUNDLE_ASSETS=OFF
make validate STS_DIR=game
```

`make vpk` and `make vpk-slim` build a single package each without the
cross-checks that `release` runs. `make test` runs the host-side tool tests,
which need no jar, no VitaSDK and no device.

`extract`, `assets`, and `livearea` declare their prerequisites, so invoking
a later target may rerun earlier stages. Generated output is written to `work/`
and `build/`; both directories are ignored by git and must not be distributed.

## Troubleshooting

`provide --sts-dir or --sts-jar`

`build.sh` needs to be told where the game is. Pass `--sts-dir` with the folder
containing `desktop-1.0.jar`, or `--sts-jar` with the jar itself. If you copied
the jar into the drop folder, that is `--sts-dir game`.

The build refuses an unrecognised jar fingerprint

Only validated game versions are supported. Override deliberately with
`--allow-unsupported-input` if you are testing an unknown but structurally
compatible jar.

`VITASDK is not set`

Reload your shell after adding `VITASDK` to `.bashrc`, or run:

```sh
export VITASDK=/usr/local/vitasdk
export PATH=$VITASDK/bin:$PATH
```

`magick not found` or `convert not found`

Install ImageMagick:

```sh
sudo apt install -y imagemagick
```

Vita3K reports many missing `.dds` files

This is expected when the next log line opens the same path as `.png` or
`.jpg`. The asset pipeline only keeps DDS when compression is useful or
required; other images intentionally remain in their original format. A real
asset-copy problem is when both the DDS probe and its PNG/JPG fallback are
missing. Recopy the complete asset tree with the `rsync --delete` command
above. If card art, UI textures, or effects are blank, run `./build.sh --clean`
and replace both the installed VPK and the complete asset directory; that is
not an expected fallback-only condition.

TeaVM or Gradle runs out of memory

Give WSL more memory, close other applications, and rerun `./build.sh`.

Vita linker error: `cannot find -l<name>`

Your VitaSDK packages are incomplete or outdated. Run:

```sh
vitasdk-update
```

or install the missing package through VitaSDK's package manager.

## Repository Layout

| Path | Purpose |
| --- | --- |
| `build.sh` | One-command public build entrypoint. |
| `game/` | Local drop folder for `desktop-1.0.jar`; ignored by git. |
| `pipeline/` | Python verification, extraction, asset, and LiveArea tools. |
| `backend/` | libGDX Vita backend and desktop-service stubs. |
| `transpiler/` | TeaVM AOT harness and Vita launcher. |
| `native/` | Vita C entrypoint, glue, CMake build, and packaging. |
| `tools/` | Build helper tools, asset FTP upload, and PC progress import. |
| `teavm-patches/` | TeaVM runtime patch notes for the Vita target. |
| `docs/` | Architecture, feasibility research, and roadmap. |

## Legal

Slay the Spire is copyright Mega Crit Games. This project is not affiliated
with or endorsed by Mega Crit. Do not distribute built VPKs or generated asset
folders, because they contain game content from your copy. Distribute this
source repository instead.

## License

MIT for this build tooling. See [LICENSE](LICENSE) .
