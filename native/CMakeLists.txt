## Game build: TeaVM-generated C + glue -> eboot.bin, staged as game.bin
##
## Usage (driven by the top-level Makefile):
##   cmake -S native -B build/native \
##         -DCMAKE_TOOLCHAIN_FILE=$VITASDK/share/vita.toolchain.cmake \
##         -DGENERATED_C=work/generated_c
cmake_minimum_required(VERSION 3.16)

if(NOT DEFINED CMAKE_TOOLCHAIN_FILE AND DEFINED ENV{VITASDK})
  set(CMAKE_TOOLCHAIN_FILE "$ENV{VITASDK}/share/vita.toolchain.cmake" CACHE PATH "toolchain")
endif()

project(STSVITA C)
include("${VITASDK}/share/vita.cmake" REQUIRED)

set(GENERATED_C "" CACHE PATH "directory of TeaVM-generated C (work/generated_c)")

if(NOT GENERATED_C OR NOT EXISTS "${GENERATED_C}")
  message(FATAL_ERROR "GENERATED_C not set/found - run `make transpile` first")
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
  # SHELL: keeps each "-include <file>" pair intact - without it CMake
  # de-duplicates the repeated -include flag and turns the second header into
  # a stray input file ("cannot specify -o with -c ... with multiple files").
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
  "SHELL:-Wl,--wrap=vgl_reserve_data_pool")

target_link_libraries(${PROJECT_NAME}
  vitaGL vitashark SceShaccCgExt mathneon SceShaccCg_stub
  # vitaGL/vitaShark are C++ - pull in the C++ runtime (operator new/delete,
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
  #   ScePower (clocks), SceKernelThreadMgr (sceKernelDelayThread/fibers),
  #   SceProcessmgr (sceKernelLibcTime), SceLibKernel (exit, clib),
  #   SceIofilemgr (newlib file IO -> sceIo).
  ScePower_stub SceKernelThreadMgr_stub SceProcessmgr_stub
  SceLibKernel_stub SceIofilemgr_stub
  # SceRtc: wall-clock in the [hitch] logger (suspend vs real stall).
  SceRtc_stub
  pthread m
)

vita_create_self(eboot.bin ${PROJECT_NAME} UNSAFE)
