cmake_minimum_required(VERSION 3.16)

if(NOT DEFINED CMAKE_TOOLCHAIN_FILE AND DEFINED ENV{VITASDK})
  set(CMAKE_TOOLCHAIN_FILE "$ENV{VITASDK}/share/vita.toolchain.cmake" CACHE PATH "toolchain")
endif()

project(STSPSVITA C)
include("${VITASDK}/share/vita.cmake" REQUIRED)

set(STSV_TITLEID "STSV00001")
set(STSV_VERSION "01.01")
set(APP_NAME "STSPSVITA" CACHE STRING "bubble name")
set(SCE_SYS "${CMAKE_CURRENT_SOURCE_DIR}/sce_sys" CACHE PATH "bubble and LiveArea art")
set(VITA_MKSFOEX_FLAGS "${VITA_MKSFOEX_FLAGS} -d ATTRIBUTE2=12")

add_executable(launcher src/main.c src/manifest.c src/sha256.c)
target_compile_definitions(launcher PRIVATE
  STSV_TITLEID="${STSV_TITLEID}" STSV_VERSION="${STSV_VERSION}")
target_compile_options(launcher PRIVATE -std=gnu11 -O2 -Wall -Wextra)
target_link_libraries(launcher
  vita2d freetype png16 jpeg z bz2 m
  SceAppMgr_stub SceCommonDialog_stub SceCtrl_stub SceDisplay_stub SceGxm_stub
  SceIofilemgr_stub SceLibKernel_stub ScePgf_stub ScePower_stub
  SceProcessmgr_stub SceSysmem_stub SceSysmodule_stub SceKernelThreadMgr_stub)

vita_create_self(eboot.bin launcher UNSAFE)
vita_create_vpk(STSPSVITA.vpk ${STSV_TITLEID} eboot.bin
  VERSION ${STSV_VERSION}
  NAME ${APP_NAME}
  FILE ${SCE_SYS} sce_sys
)
