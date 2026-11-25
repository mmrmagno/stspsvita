# STSPSVITA build orchestration.
#
#   ./build.sh --sts-dir ~/.steam/root/steamapps/common/SlayTheSpire
#
# `build.sh` is the supported release interface.  These targets remain the
# composable contributor/debug interface.
#
# Options:
#   LANGS=eng,jpn     game languages whose (CJK) fonts to keep
#   AUDIO_KBPS=32     re-encode OGGs (needs ffmpeg); 48 for higher fidelity,
#                     0 copies through

STS_DIR       ?=
LANGS         ?= eng
AUDIO_KBPS    ?= 32
WORK          := work
PY            := python3
VITA_IP       ?=
ALLOW_UNSUPPORTED ?= 0
OUT           ?= out
GRADLE_FLAGS  ?=
VERIFY_FLAGS  := $(if $(filter 1 ON yes true,$(ALLOW_UNSUPPORTED)),--allow-unknown,)

.PHONY: all release incremental verify extract \
        assets livearea transpile native validate \
        deploy-assets stage launcher bubble test clean clean-build
.NOTPARALLEL: release incremental

all: release

verify:
	@test -n "$(STS_DIR)" || { echo "set STS_DIR=<install directory or desktop-1.0.jar>"; exit 2; }
	$(PY) pipeline/verify.py --sts-dir "$(STS_DIR)" $(VERIFY_FLAGS)

extract: verify
	$(PY) pipeline/extract.py --sts-dir "$(STS_DIR)" --work $(WORK)

assets: extract
	$(PY) pipeline/assets.py --work $(WORK) --langs $(LANGS) --audio-kbps $(AUDIO_KBPS)

# LiveArea bubble icon + background + start gate, derived from the game files.
livearea: assets
	$(PY) pipeline/livearea.py --work $(WORK)

transpile:
	@test -f $(WORK)/jar_path.txt || { echo "run 'make extract' first"; exit 2; }
	cd transpiler && gradle $(GRADLE_FLAGS) transpile

native:
	@test -n "$$VITASDK" || { echo "VITASDK env var not set - install https://vitasdk.org"; exit 2; }
	cmake -S native -B build/native \
	  -DCMAKE_TOOLCHAIN_FILE=$$VITASDK/share/vita.toolchain.cmake \
	  -DGENERATED_C=$(abspath $(WORK)/generated_c)
	cmake --build build/native -j

release: assets livearea transpile native validate stage bubble

validate:
	$(PY) tools/validate_build.py --sts-input "$(STS_DIR)" --work "$(WORK)" \
	  --build build --langs "$(LANGS)" --audio-kbps "$(AUDIO_KBPS)"

# Fast native-only loop. Reuses the existing asset and generated-C trees; run
# `make transpile` first when backend/Java code changes.
incremental: verify
	@test -d "$(WORK)/assets_vita" || { echo "incremental build needs $(WORK)/assets_vita; run a full ./build.sh first"; exit 2; }
	@test -f "$(WORK)/generated_c/all.txt" || { echo "incremental build needs $(WORK)/generated_c; run a full ./build.sh first"; exit 2; }
	@test -f "$(WORK)/sce_sys/icon0.png" || { echo "incremental build needs $(WORK)/sce_sys; run a full ./build.sh first"; exit 2; }
	$(MAKE) native
	$(MAKE) validate STS_DIR="$(STS_DIR)" LANGS="$(LANGS)" AUDIO_KBPS="$(AUDIO_KBPS)"
	$(MAKE) stage bubble OUT="$(OUT)"

# One-time upload of the processed asset payload to the Vita over VitaShell FTP
# (enable it with SELECT in VitaShell; default port 1337).
deploy-assets:
	@test -f "$(OUT)/data/stsvita/files.sha256" || { echo "nothing staged, run a build first"; exit 2; }
	@test -n "$(VITA_IP)" || { echo "set VITA_IP=<vita ip>  (VitaShell FTP)"; exit 2; }
	$(PY) tools/deploy_assets.py --src "$(OUT)/data/stsvita" --host $(VITA_IP)

stage:
	$(PY) tools/stage_data.py --build build --work $(WORK) --out "$(OUT)"

launcher:
	@test -n "$$VITASDK" || { echo "VITASDK env var not set, install https://vitasdk.org"; exit 2; }
	cmake -S launcher -B build/launcher \
	  -DCMAKE_TOOLCHAIN_FILE=$$VITASDK/share/vita.toolchain.cmake
	cmake --build build/launcher -j
	@cp build/launcher/STSPSVITA.vpk build/STSPSVITA.vpk

# Same launcher with the bubble art from the user's own game. Never published.
bubble:
	@test -f "$(WORK)/sce_sys/icon0.png" || { echo "run 'make livearea' first"; exit 2; }
	@test -n "$$VITASDK" || { echo "VITASDK env var not set, install https://vitasdk.org"; exit 2; }
	cmake -S launcher -B build/bubble \
	  -DCMAKE_TOOLCHAIN_FILE=$$VITASDK/share/vita.toolchain.cmake \
	  -DSCE_SYS=$(abspath $(WORK)/sce_sys) -DAPP_NAME="Slay the Spire"
	cmake --build build/bubble -j
	@mkdir -p "$(OUT)"
	@cp build/bubble/STSPSVITA.vpk "$(OUT)/SlayTheSpire.vpk"
	@echo "VPK: $(OUT)/SlayTheSpire.vpk"

# Host-side tool tests. Synthetic fixtures only, never the game's own data,
# so this needs no jar, no VitaSDK and no device.
test:
	$(PY) -m unittest discover -s tools -p 'test_*.py' -v

# Reproducible clean. All game-derived and generated build trees are reset;
# anything else under work/ is left alone.
clean-build:
	rm -rf $(WORK)/assets_raw $(WORK)/assets_vita $(WORK)/gen \
	  $(WORK)/generated_c $(WORK)/jar_path.txt \
	  $(WORK)/assets-vita-metadata.json $(WORK)/sce_sys \
	  build transpiler/build transpiler/.gradle

clean: clean-build
