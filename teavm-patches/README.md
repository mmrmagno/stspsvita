# TEAVM_VITA runtime patch

TeaVM's C runtime gained homebrew-console support when PSP was merged
upstream (konsoletyper/teavm#1138, 2026-01-31): fixed malloc-based heap
instead of mmap, fiber implementation without ucontext, newlib quirks —
all behind `TEAVM_PSP` ifdefs in the runtime C sources.

The Vita needs the same set of switches (`TEAVM_VITA`), differing mainly in:

- heap sizing/placement (`_newlib_heap_size_user`, enlarged-memory mode)
- clock/time sources (sceRtc vs PSP rtc)
- file descriptors already routed via newlib→sceIo (no extra work expected)

Current implementation: `tools/vita_patch_runtime.py` reproducibly patches a
fresh TeaVM 0.15 generated-C tree during every transpile. Besides activating
the Vita/newlib paths, it carries the hardware-proven 32-bit array layout and
array-class alignment fixes, Vita clock/file/fiber adaptations, and temporary
diagnostics. M5 should split upstreamable runtime correctness fixes from local
diagnostics, add focused reproducers where practical, and submit the former to
TeaVM. This directory remains the design note; generated C is never hand-edited
as a permanent fix.
