# STSVITA build orchestration.
#./build.sh --sts-dir ~/.steam/root/steamapps/common/SlayTheSpire
# `build.sh` is the supported release interface. These targets remain the
# composable contributor/debug interface.
# Options:
# LANGS=eng,jpn game languages whose (CJK) fonts to keep
# AUDIO_KBPS=32 re-encode OGGs (needs ffmpeg); 48 for higher fidelity,
# 0 copies through

STS_DIR       ?=
LANGS         ?= eng
AUDIO_KBPS    ?= 32
WORK          := work
PY            := python3
BUNDLE_ASSETS ?= ON
VITA_IP       ?=
ALLOW_UNSUPPORTED ?= 0
VERIFY_FLAGS  := $(if $(filter 1 ON yes true,$(ALLOW_UNSUPPORTED)),--allow-unknown,)

.PHONY: all release packages vpk vpk-slim incremental-slim verify extract \
        assets livearea transpile native validate validate-slim \
        deploy-assets test clean clean-build
.NOTPARALLEL: release packages

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
	cd transpiler && gradle transpile

native:
	@test -n "$$VITASDK" || { echo "VITASDK env var not set — install https://vitasdk.org"; exit 2; }
	cmake -S native -B build/native \
	  -DCMAKE_TOOLCHAIN_FILE=$$VITASDK/share/vita.toolchain.cmake \
	  -DGENERATED_C=$(abspath $(WORK)/generated_c) \
	  -DASSETS=$(abspath $(WORK)/assets_vita) \
	  -DBUNDLE_ASSETS=$(BUNDLE_ASSETS) \
	  $(if $(wildcard $(WORK)/sce_sys),-DSCE_SYS=$(abspath $(WORK)/sce_sys),)
	cmake --build build/native -j
	@mkdir -p build
	@cp build/native/STSVITA.vpk build/STSVITA$(if $(filter OFF,$(BUNDLE_ASSETS)),-slim).vpk
	@echo "VPK: build/STSVITA$(if $(filter OFF,$(BUNDLE_ASSETS)),-slim).vpk"

# Canonical release: one verified asset tree and generated-C tree feed both
# package forms. Toggling BUNDLE_ASSETS only reruns CMake's packaging step;
# validate_build.py proves that the executable bytes remain identical.
release: assets livearea transpile packages validate

packages:
	@test -d "$(WORK)/assets_vita" || { echo "run 'make assets' first"; exit 2; }
	@test -f "$(WORK)/generated_c/all.txt" || { echo "run 'make transpile' first"; exit 2; }
	@test -f "$(WORK)/sce_sys/icon0.png" || { echo "run 'make livearea' first"; exit 2; }
	$(MAKE) native BUNDLE_ASSETS=ON
	$(MAKE) native BUNDLE_ASSETS=OFF

validate:
	$(PY) tools/validate_build.py --sts-input "$(STS_DIR)" --work "$(WORK)" \
	  --build build --langs "$(LANGS)" --audio-kbps "$(AUDIO_KBPS)"

validate-slim:
	$(PY) tools/validate_build.py --sts-input "$(STS_DIR)" --work "$(WORK)" \
	  --build build --langs "$(LANGS)" --audio-kbps "$(AUDIO_KBPS)" --slim-only

# Full, self-contained VPK (code + assets). Install once; large (~335MB).
vpk: assets livearea transpile native

# Slim, code-only VPK (~7MB) — reinstalls in seconds while iterating on code.
# The game reads assets from ux0:data/stsvita/assets (VitaFiles auto-detects
# when app0:/assets is absent), so push them once: make deploy-assets VITA_IP=..
vpk-slim: livearea transpile
	$(MAKE) native BUNDLE_ASSETS=OFF
	@echo "Slim VPK needs assets at ux0:data/stsvita/assets — make deploy-assets VITA_IP=.."

# Fast native-only loop. This deliberately reuses the existing asset and
# generated-C trees; run `make transpile` first when backend/Java code changes.
incremental-slim: verify
	@test -d "$(WORK)/assets_vita" || { echo "incremental build needs $(WORK)/assets_vita; run a full ./build.sh first"; exit 2; }
	@test -f "$(WORK)/generated_c/all.txt" || { echo "incremental build needs $(WORK)/generated_c; run a full ./build.sh first"; exit 2; }
	@test -f "$(WORK)/sce_sys/icon0.png" || { echo "incremental build needs $(WORK)/sce_sys; run a full ./build.sh first"; exit 2; }
	$(MAKE) native BUNDLE_ASSETS=OFF
	$(MAKE) validate-slim STS_DIR="$(STS_DIR)" LANGS="$(LANGS)" AUDIO_KBPS="$(AUDIO_KBPS)"

# One-time upload of the processed asset payload to the Vita over VitaShell FTP
# (enable it with SELECT in VitaShell; default port 1337).
deploy-assets:
	@test -d "$(WORK)/assets_vita" || { echo "no $(WORK)/assets_vita — run 'make assets' first"; exit 2; }
	@test -n "$(VITA_IP)" || { echo "set VITA_IP=<vita ip>  (VitaShell FTP)"; exit 2; }
	$(PY) tools/deploy_assets.py --src $(WORK)/assets_vita \
	  --host $(VITA_IP) --dest ux0:/data/stsvita/assets

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
