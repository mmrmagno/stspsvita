#include "stsv_misc.h"

#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <malloc.h>
#if defined(__vita__) || defined(VITA)
#include <vitaGL.h>
#include <psp2/rtc.h>
#else
#include <sys/time.h>
#endif

/* Wall-clock microseconds (keeps counting through suspend, unlike
 * sceKernelGetProcessTimeWide). The [hitch] logger compares both to tell a
 * real frame stall from the console being suspended/minimized mid-frame. */
int64_t stsv_wall_us(void) {
#if defined(__vita__) || defined(VITA)
    SceRtcTick tick;
    sceRtcGetCurrentTick(&tick);
    return (int64_t) tick.tick;
#else
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (int64_t) tv.tv_sec * 1000000 + tv.tv_usec;
#endif
}

#define STB_IMAGE_IMPLEMENTATION
#define STBI_NO_HDR
#define STBI_NO_LINEAR
#define STBI_NO_PIC
#define STBI_NO_PNM
#define STBI_NO_PSD
#define STBI_NO_TGA
#define STBI_NO_GIF
#include "../../third_party/stb_image.h"

/* Per-frame stb decode time, polled by the [hitch] logger like the
 * ft/subtex accumulators (combat frame drops). */
static int64_t stsv_img_us_accum;
static int32_t stsv_img_calls_accum;

int64_t stsv_img_frame_stats(void) {
    int64_t ms = stsv_img_us_accum / 1000;
    int32_t calls = stsv_img_calls_accum > 999 ? 999 : stsv_img_calls_accum;
    stsv_img_us_accum = 0;
    stsv_img_calls_accum = 0;
    return ms * 1000 + calls;
}

void *stsv_img_load(const void *data, int32_t len, int32_t *outDims) {
    int w = 0, h = 0, comp = 0;
    int64_t t0 = stsv_wall_us();
    unsigned char *pixels =
        stbi_load_from_memory(data, len, &w, &h, &comp, 4 /* force RGBA */);
    stsv_img_us_accum += stsv_wall_us() - t0;
    stsv_img_calls_accum++;
    if (pixels) {
        outDims[0] = w;
        outDims[1] = h;
    } else {
        struct mallinfo mi = mallinfo();
        /* largest contiguous block we can still grab */
        size_t probe = 0;
        for (size_t t = 64u << 20; t >= (1u << 20); t >>= 1) {
            void *m = malloc(t);
            if (m) { free(m); probe = t; break; }
        }
        fprintf(stderr,
            "[DIAG stb] decode FAILED reason=%s | heap arena=%dMB used=%dMB free=%dMB largestmalloc=%dMB\n",
            stbi_failure_reason(), mi.arena >> 20, mi.uordblks >> 20,
            mi.fordblks >> 20, (int) (probe >> 20));
#if defined(__vita__) || defined(VITA)
        fprintf(stderr,
            "[DIAG vgl] VRAM used=%dMB/%dMB  RAM used=%dMB/%dMB  PHYCONT used=%dMB/%dMB  EXTERNAL(newlib) used=%dMB\n",
            (int) ((vglMemTotal(VGL_MEM_VRAM) - vglMemFree(VGL_MEM_VRAM)) >> 20),
            (int) (vglMemTotal(VGL_MEM_VRAM) >> 20),
            (int) ((vglMemTotal(VGL_MEM_RAM) - vglMemFree(VGL_MEM_RAM)) >> 20),
            (int) (vglMemTotal(VGL_MEM_RAM) >> 20),
            (int) ((vglMemTotal(VGL_MEM_SLOW) - vglMemFree(VGL_MEM_SLOW)) >> 20),
            (int) (vglMemTotal(VGL_MEM_SLOW) >> 20),
            (int) ((vglMemTotal(VGL_MEM_EXTERNAL) - vglMemFree(VGL_MEM_EXTERNAL)) >> 20));
#endif
    }
    return pixels;
}

void stsv_img_free(void *pixels) {
    stbi_image_free(pixels);
}

void stsv_memcpy(void *dst, const void *src, int32_t bytes) {
    memcpy(dst, src, (size_t) bytes);
}

void stsv_memset(void *dst, int32_t value, int32_t bytes) {
    memset(dst, value, (size_t) bytes);
}
