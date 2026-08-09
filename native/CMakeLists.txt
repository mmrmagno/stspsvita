## VPK build: TeaVM-generated C + glue -> eboot.bin -> STSVITA.vpk
##
## Usage (driven by the top-level Makefile):
## cmake -S native -B build/native \
## -DCMAKE_TOOLCHAIN_FILE=$VITASDK/share/vita.toolchain.cmake \
## -DGENERATED_C=work/generated_c -DASSETS=work/assets_vita
cmake_minimum_required(VERSION 3.16)

if(NOT DEFINED CMAKE_TOOLCHAIN_FILE AND DEFINED ENV{VITASDK})
  set(CMAKE_TOOLCHAIN_FILE "$ENV{VITASDK}/share/vita.toolchain.cmake" CACHE PATH "toolchain")
endif()

project(STSVITA C)
include("${VITASDK}/share/vita.cmake" REQUIRED)

set(VITA_APP_NAME "Slay the Spire")
set(VITA_TITLEID  "STSV00001")
set(VITA_VERSION  "00.10")
# ATTRIBUTE2=12 -> enlarged memory mode (~365MB app budget)
set(VITA_MKSFOEX_FLAGS "${VITA_MKSFOEX_FLAGS} -d ATTRIBUTE2=12")

set(GENERATED_C "" CACHE PATH "directory of TeaVM-generated C (work/generated_c)")
set(ASSETS ""      CACHE PATH "processed asset payload (work/assets_vita)")
# LiveArea/sce_sys payload (icon0.png + livearea/). Generated from the user's
# game files by pipeline/livearea.py into work/sce_sys; falls back to the repo's
# native/sce_sys (README only -> default placeholder bubble) when not generated.
set(SCE_SYS "${CMAKE_CURRENT_SOURCE_DIR}/sce_sys" CACHE PATH "LiveArea/sce_sys directory")

if(NOT GENERATED_C OR NOT EXISTS "${GENERATED_C}")
  message(FATAL_ERROR "GENERATED_C not set/found — run `make transpile` first")
endif()

# Compile exactly TeaVM's reachable set (work/generated_c/all.txt), NOT a glob
# of *.c: TeaVM emits a few dead source files (e.g. ClassReflection.c) that
# reference methods it never generated bodies for. The host build links from
# all.txt too; globbing pulls the dead files in and breaks the link.
file(STRINGS "${GENERATED_C}/all.txt" TEAVM_REL_SOURCES)
list(TRANSFORM TEAVM_REL_SOURCES PREPEND "${GENERATED_C}/" OUTPUT_VARIABLE TEAVM_SOURCES)

add_executable(${PROJECT_NAME}
  ${TEAVM_SOURCES}
  src/main.c
  src/glue/gl_glue.c
  src/glue/gl_common_glue.c
  src/glue/input_glue.c
  src/glue/ime_glue.c
  src/glue/misc_glue.c
  src/glue/freetype_glue.c
  src/glue/audio_glue.c
  src/glue/vita_compat.c
)

# TEAVM_VITA switches the generated runtime onto its homebrew (TEAVM_PSP) paths
# and the uchar.h shim; it must reach the glue too (they force-include uchar.h),
# so define it target-wide.
target_compile_definitions(${PROJECT_NAME} PRIVATE TEAVM_VITA=1)

# TeaVM emits its own main(); rename it so src/main.c owns process startup
# (vitaGL + memory init must happen before the Java world boots). Scoped to the
# generated sources so the glue's real main() is left alone.
set_source_files_properties(${TEAVM_SOURCES} PROPERTIES
  COMPILE_DEFINITIONS "main=stsv_java_main")

# No -I into GENERATED_C: TeaVM names headers time.h/string.h/file.h, which
# would shadow libc's. Generated sources use relative quote-includes; the
# uchar.h shim provides char16_t where the libc lacks C11 uchar; Class.h is
# force-included because core.c uses meth_jl_Class_createClass without
# declaring it (multi-TU quirk); src/glue satisfies @Include("stsvita_glue.h").
target_include_directories(${PROJECT_NAME} PRIVATE
  src src/glue
  "${VITASDK}/arm-vita-eabi/include/freetype2")
target_compile_options(${PROJECT_NAME} PRIVATE
  -std=gnu11 -D_GNU_SOURCE
  # SHELL: keeps each "-include <file>" pair intact — without it CMake
  # de-duplicates the repeated -include flag and turns the second header into
  # a stray input file ("cannot specify -o with -c... with multiple files").
  "SHELL:-include ${GENERATED_C}/uchar.h"
  "SHELL:-include ${GENERATED_C}/classes/java/lang/Class.h"
  # TeaVM's generated C leans on pre-C23 implicit conversions that arm gcc 15
  # now errors on by default; demote them to warnings (matches the host build).
  -Wno-error=incompatible-pointer-types
  -Wno-error=int-conversion
  -Wno-error=implicit-function-declaration
  -O2 -mfpu=neon -mcpu=cortex-a9 -fno-strict-aliasing -Wl,-q)

# Suppress vitaGL's built-in spinning logo. The no-op wrapper lives in
# gl_glue.c; the project renders its own loading frame immediately after init.
target_link_options(${PROJECT_NAME} PRIVATE
  "SHELL:-Wl,--wrap=invoke_splashscreen"
  # Diagnostic-only interception: vitaGL's custom-shader draw path does not
  # check vgl_reserve_data_pool before memcpy. Keep the original behavior but
  # report invalid fallback results and the first City draw sizes first.
  "SHELL:-Wl,--wrap=vgl_reserve_data_pool"
  "SHELL:-Wl,--wrap=glDrawElements")

target_link_libraries(${PROJECT_NAME}
  vitaGL vitashark SceShaccCgExt mathneon SceShaccCg_stub
  # vitaGL/vitaShark are C++ — pull in the C++ runtime (operator new/delete,
  # std::string, exception ABI) they reference.
  stdc++
  # FreeType (glyph rasterization) + its image/compression deps
  freetype png16 z bz2
  # Audio: OpenAL-soft playback + libvorbisfile OGG decode (audio_glue.c)
  openal vorbisfile vorbis ogg
  taihen_stub SceAppMgr_stub SceCtrl_stub SceTouch_stub
  SceAudio_stub SceAudioIn_stub
  SceCommonDialog_stub SceDisplay_stub SceGxm_stub SceSysmodule_stub
  SceKernelDmacMgr_stub
  # Runtime/startup sce modules the C runtime + main.c call into:
  # ScePower (clocks), SceKernelThreadMgr (sceKernelDelayThread/fibers),
  # SceProcessmgr (sceKernelLibcTime), SceLibKernel (exit, clib),
  # SceIofilemgr (newlib file IO -> sceIo).
  ScePower_stub SceKernelThreadMgr_stub SceProcessmgr_stub
  SceLibKernel_stub SceIofilemgr_stub
  # SceRtc: wall-clock in the [hitch] logger (suspend vs real stall).
  SceRtc_stub
  pthread m
)

# Two VPK flavours share one eboot:
# BUNDLE_ASSETS=ON (default) -> full VPK, assets packed at app0:/assets
# BUNDLE_ASSETS=OFF -> slim ~7MB dev VPK; the game then reads assets
# from ux0:data/stsvita/assets (VitaFiles auto-
# detects), so reinstalling code is seconds.
option(BUNDLE_ASSETS "Pack the asset payload into the VPK" ON)

set(VPK_FILES)
if(BUNDLE_ASSETS)
  if(NOT ASSETS OR NOT EXISTS "${ASSETS}")
    message(FATAL_ERROR "BUNDLE_ASSETS is ON but ASSETS not set/found — run `make assets`, or build with -DBUNDLE_ASSETS=OFF")
  endif()
  list(APPEND VPK_FILES FILE ${ASSETS} assets)
endif()
list(APPEND VPK_FILES FILE ${SCE_SYS} sce_sys)
# Runtime startup art must live outside sce_sys: the Vita installer owns and
# filters LiveArea metadata, so unreferenced files in livearea/contents are not
# guaranteed to be copied into app0. Package a normal root-level copy instead.
set(LOADING_SCREEN "${SCE_SYS}/livearea/contents/loading.png")
if(EXISTS "${LOADING_SCREEN}")
  list(APPEND VPK_FILES FILE "${LOADING_SCREEN}" loading.png)
endif()

vita_create_self(eboot.bin ${PROJECT_NAME} UNSAFE)
vita_create_vpk(${PROJECT_NAME}.vpk ${VITA_TITLEID} eboot.bin
  VERSION ${VITA_VERSION}
  NAME ${VITA_APP_NAME}
  ${VPK_FILES}
)
