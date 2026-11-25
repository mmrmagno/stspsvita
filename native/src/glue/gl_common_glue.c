#include "stsv_gl.h"
#include "stsvita_glue.h"
#include "heap_probe.h"

#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <stdint.h>

#if defined(__vita__) || defined(VITA)
#include <malloc.h>
#include <psp2/kernel/sysmem.h>
#endif

/* Last image file read, only used to name a texture in the log. */
static char stsv_tex_path[192] = "?";

void stsv_tex_note_path(const void *utf8, int32_t len) {
    int n = (int) sizeof(stsv_tex_path) - 1;
    if (len < n) n = (int) len;
    memcpy(stsv_tex_path, utf8, (size_t) n);
    stsv_tex_path[n] = '\0';
}

/* Per-frame counters, read by the [hitch] logger. */
static int64_t stsv_dxt_us_accum;
static int32_t stsv_dxt_calls_accum;
static int64_t stsv_upload_us_accum;
static int32_t stsv_upload_calls_accum;

static int64_t stsv_pack_stats(int64_t *us, int32_t *calls) {
    int64_t ms = *us / 1000;
    int32_t n = *calls > 999 ? 999 : *calls;
    *us = 0;
    *calls = 0;
    return ms * 1000 + n;
}

int64_t stsv_dxt_frame_stats(void) {
    return stsv_pack_stats(&stsv_dxt_us_accum, &stsv_dxt_calls_accum);
}

int64_t stsv_upload_frame_stats(void) {
    return stsv_pack_stats(&stsv_upload_us_accum, &stsv_upload_calls_accum);
}

static void stsv_upload_time(int64_t t0) {
    stsv_upload_us_accum += stsv_wall_us() - t0;
    stsv_upload_calls_accum++;
}

#ifndef GL_COMPRESSED_RGBA_S3TC_DXT5_EXT
#define GL_COMPRESSED_RGBA_S3TC_DXT5_EXT 0x83F3
#endif
#ifndef GL_COMPRESSED_RGB_S3TC_DXT1_EXT
#define GL_COMPRESSED_RGB_S3TC_DXT1_EXT 0x83F0
#endif

#if defined(__vita__) || defined(VITA)
#define STB_DXT_IMPLEMENTATION
#define STB_DXT_STATIC
#include "../../third_party/stb_dxt.h"
#endif

#if defined(__vita__) || defined(VITA)
/* glTexSubImage2D with RGBA into a compressed texture faults vitaGL, so
 * remember which ids are compressed. */
#define STSV_MAX_COMPRESSED 1024
static int stsv_compressed_ids[STSV_MAX_COMPRESSED];
static int stsv_compressed_n;

static int stsv_bound_tex(void) {
    GLint id = 0;
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &id);
    return (int) id;
}

static void stsv_mark_compressed(void) {
    int id = stsv_bound_tex();
    if (id != 0 && stsv_compressed_n < STSV_MAX_COMPRESSED) {
        stsv_compressed_ids[stsv_compressed_n++] = id;
    }
}

/* FreeType glyph pages start almost empty and fill up later through
 * glTexSubImage2D, so an id that was ever a glyph page is never compressed. */
#define STSV_MAX_GLYPH_PAGES 256
static int stsv_glyph_page_ids[STSV_MAX_GLYPH_PAGES];
static int stsv_glyph_page_n;

static int stsv_is_glyph_page(int id) {
    for (int i = 0; i < stsv_glyph_page_n; i++) {
        if (stsv_glyph_page_ids[i] == id) return 1;
    }
    return 0;
}

static void stsv_mark_glyph_page(int id) {
    if (id != 0 && !stsv_is_glyph_page(id)
            && stsv_glyph_page_n < STSV_MAX_GLYPH_PAGES) {
        stsv_glyph_page_ids[stsv_glyph_page_n++] = id;
    }
}

static void stsv_unmark_glyph_page(int id) {
    for (int i = 0; i < stsv_glyph_page_n; ) {
        if (stsv_glyph_page_ids[i] == id) {
            stsv_glyph_page_ids[i] = stsv_glyph_page_ids[--stsv_glyph_page_n];
        } else {
            i++;
        }
    }
}

static int stsv_is_compressed(int id) {
    for (int i = 0; i < stsv_compressed_n; i++) {
        if (stsv_compressed_ids[i] == id) return 1;
    }
    return 0;
}

/* GL reuses ids: a stale mark would drop every sub-upload to the new texture. */
static void stsv_unmark_compressed(int id) {
    for (int i = 0; i < stsv_compressed_n; ) {
        if (stsv_compressed_ids[i] == id) {
            stsv_compressed_ids[i] = stsv_compressed_ids[--stsv_compressed_n];
        } else {
            i++;
        }
    }
}

/* When the vitaGL pools are full, a few textures may go to newlib malloc. Kept
 * small, and off during boot, because vitaGL takes its vertex buffers from the
 * same heap and crashes in _glDrawElements when that allocation fails. */
#define STSV_SPILL_BUDGET   (6u << 20)
#define STSV_SPILL_RESERVE  (48u << 20)
static size_t stsv_spill_used;
static int stsv_spill_enabled;

/* Gives the bytes back when a texture is deleted or uploaded again. */
#define STSV_SPILL_TRACK 128
static struct { int id; size_t bytes; } stsv_spilled[STSV_SPILL_TRACK];

static void stsv_spill_refund(int id) {
    for (int i = 0; i < STSV_SPILL_TRACK; i++) {
        if (stsv_spilled[i].id == id) {
            stsv_spill_used -= stsv_spilled[i].bytes;
            stsv_spilled[i].id = 0;
            stsv_spilled[i].bytes = 0;
        }
    }
}

static void stsv_spill_note(int id, size_t bytes) {
    if (id == 0) {
        return;
    }
    stsv_spill_refund(id); /* re-upload of an already-spilled texture */
    for (int i = 0; i < STSV_SPILL_TRACK; i++) {
        if (stsv_spilled[i].id == 0) {
            stsv_spilled[i].id = id;
            stsv_spilled[i].bytes = bytes;
            return;
        }
    }
}

void stsv_tex_spill_enable(void) {
    stsv_spill_enabled = 1;
    fprintf(stderr, "[tex] spill enabled (budget %dKB, reserve %dKB)\n",
            (int) (STSV_SPILL_BUDGET >> 10), (int) (STSV_SPILL_RESERVE >> 10));
}

static size_t stsv_pool_maxfree(void) {
    size_t maxfree = vglMemFree(VGL_MEM_VRAM);
    size_t fr = vglMemFree(VGL_MEM_RAM);  if (fr > maxfree) maxfree = fr;
    fr = vglMemFree(VGL_MEM_SLOW);         if (fr > maxfree) maxfree = fr;
    return maxfree;
}

/* Loaded late in the boot preload, when the pools are already full, and the
 * game keeps them all run. These may spill before create() returns. */
static int stsv_boot_critical_texture(void) {
    return strstr(stsv_tex_path, "/images/ui/campfire/") != NULL
        || strstr(stsv_tex_path, "/images/ui/topPanel/") != NULL
        || strstr(stsv_tex_path, "/images/ui/map/legend2") != NULL
        || strstr(stsv_tex_path, "/images/ui/map/map") != NULL
        || strstr(stsv_tex_path, "/images/ui/option/inputPaneFg") != NULL
        || strstr(stsv_tex_path, "/images/ui/event/roomTextPanel") != NULL
        || (strstr(stsv_tex_path, "/images/npcs/") != NULL
            && strstr(stsv_tex_path, "Chest") != NULL)
        || strstr(stsv_tex_path, "/powers/powers") != NULL
        || (strstr(stsv_tex_path, "/images/characters/") != NULL
            && (strstr(stsv_tex_path, "corpse") != NULL
                || strstr(stsv_tex_path, "/idle/") != NULL))
        || strstr(stsv_tex_path, "/images/megaCrit") != NULL;
}

/* Lets VitaFileHandle skip reading a texture that would be dropped anyway. */
int32_t stsv_tex_would_fit(int32_t need) {
    size_t n = (size_t) need;
    if (stsv_pool_maxfree() > n + (1u << 20)) {
        return 1;
    }
    if ((stsv_spill_enabled || stsv_boot_critical_texture())
            && stsv_spill_used + n <= STSV_SPILL_BUDGET) {
        if (stsv_heap_can_alloc(n + STSV_SPILL_RESERVE)) {
            return 1;
        }
    }
    return 0;
}

static int stsv_tex_can_alloc(size_t need, int width, int height, const char *tag) {
    size_t maxfree = stsv_pool_maxfree();
    if (maxfree > need + (1u << 20)) {
        return 1;
    }
    int boot_critical = !stsv_spill_enabled && stsv_boot_critical_texture();
    if ((stsv_spill_enabled || boot_critical)
            && stsv_spill_used + need <= STSV_SPILL_BUDGET) {
        if (stsv_heap_can_alloc(need + STSV_SPILL_RESERVE)) {
            stsv_spill_used += need;
            stsv_spill_note(stsv_bound_tex(), need);
            fprintf(stderr, "[tex] %sSPILL%s %dx%d need=%dKB spill_used=%dKB path=%s\n",
                    boot_critical ? "BOOT-" : "", tag,
                    width, height, (int) (need >> 10),
                    (int) (stsv_spill_used >> 10), stsv_tex_path);
            return 1;
        }
    }
    static int skips = 0;
    if (skips++ < 96) {
        fprintf(stderr, "[tex] SKIP%s %dx%d need=%dKB maxfree=%dKB spill_used=%dKB path=%s\n",
                tag, width, height, (int) (need >> 10), (int) (maxfree >> 10),
                (int) (stsv_spill_used >> 10), stsv_tex_path);
    }
    return 0;
}

/* vitaGL's draw path memcpy()s into this result without checking it. Report an
 * unusable reservation before the crash that follows. */
extern unsigned char *circular_data_pool_ptr[];
extern unsigned char *circular_data_pool_limit[];
extern int vgl_circular_idx;

void *__real_vgl_reserve_data_pool(uint32_t size);

void *__wrap_vgl_reserve_data_pool(uint32_t size) {
    int idx = vgl_circular_idx;
    uintptr_t before = 0, limit = 0;
    if (idx >= 0 && idx < 4) {
        before = (uintptr_t) circular_data_pool_ptr[idx];
        limit = (uintptr_t) circular_data_pool_limit[idx];
    }
    void *result = __real_vgl_reserve_data_pool(size);
    if (result == NULL || (uintptr_t) result < UINT32_C(0x10000000)) {
        fprintf(stderr, "[gl] draw reservation failed size=%u result=%p idx=%d ptr=%p limit=%p\n",
                (unsigned int) size, result, idx, (void *) before, (void *) limit);
    }
    return result;
}
#endif

#if !defined(__vita__) && !defined(VITA)
/* Host: pools are unbounded, spilling never happens, everything fits. */
void stsv_tex_spill_enable(void) {
}

int32_t stsv_tex_would_fit(int32_t need) {
    (void) need;
    return 1;
}
#endif

/* Compresses large images to DXT on upload, or the atlases do not fit. */
void stsv_tex_image_rgba(int target, int level, int width, int height, const void *rgba) {
#if defined(__vita__) || defined(VITA)
    int bound_id = stsv_bound_tex();
    stsv_unmark_compressed(bound_id);
    if (level == 0 && stsv_is_glyph_page(bound_id)) {
        int64_t up_t0 = stsv_wall_us();
        glTexImage2D((GLenum) target, level, GL_RGBA, width, height, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, rgba);
        stsv_upload_time(up_t0);
        return;
    }
    if (width >= 256 && height >= 256 && (width & 3) == 0 && (height & 3) == 0) {
        const unsigned char *src = (const unsigned char *) rgba;
        int64_t dxt_t0 = stsv_wall_us();
        int has_alpha = 0;
        size_t npx = (size_t) width * height, transparent = 0;
        for (size_t i = 0; i < npx; i++) {
            unsigned char a = src[i * 4 + 3];
            if (a != 0xff) has_alpha = 1;
            if (a == 0) transparent++;
        }
        /* At least 96% transparent means a glyph page: leave it uncompressed. */
        if (transparent * 100 >= npx * 96) {
            stsv_dxt_us_accum += stsv_wall_us() - dxt_t0;
            stsv_dxt_calls_accum++;
            int64_t up_t0 = stsv_wall_us();
            glTexImage2D((GLenum) target, level, GL_RGBA, width, height, 0,
                         GL_RGBA, GL_UNSIGNED_BYTE, rgba);
            stsv_upload_time(up_t0);
            if (level == 0) {
                stsv_mark_glyph_page(bound_id);
            }
            return;
        }
        int bw = width / 4, bh = height / 4;
        int block_bytes = has_alpha ? 16 : 8;
        GLenum fmt = has_alpha ? GL_COMPRESSED_RGBA_S3TC_DXT5_EXT
                               : GL_COMPRESSED_RGB_S3TC_DXT1_EXT;
        size_t outSize = (size_t) bw * bh * block_bytes;
        unsigned char *out = malloc(outSize);
        if (out != NULL) {
            unsigned char *dst = out;
            unsigned char block[64];
            for (int by = 0; by < bh; by++) {
                for (int bx = 0; bx < bw; bx++) {
                    for (int row = 0; row < 4; row++) {
                        const unsigned char *s =
                            src + (((size_t) (by * 4 + row) * width) + (size_t) bx * 4) * 4;
                        memcpy(block + row * 16, s, 16);
                    }
                    stb_compress_dxt_block(dst, block, has_alpha, STB_DXT_NORMAL);
                    dst += block_bytes;
                }
            }
            stsv_dxt_us_accum += stsv_wall_us() - dxt_t0;
            stsv_dxt_calls_accum++;
            /* vitaGL faults when nothing can hold the texture, so draw a blank. */
            if (stsv_tex_can_alloc(outSize, width, height, "")) {
                int64_t up_t0 = stsv_wall_us();
                glCompressedTexImage2D((GLenum) target, level, fmt,
                                       width, height, 0, (GLsizei) outSize, out);
                stsv_upload_time(up_t0);
                stsv_mark_compressed();
            } else {
                unsigned char blank[64] = {0};
                glTexImage2D((GLenum) target, level, GL_RGBA, 4, 4, 0,
                             GL_RGBA, GL_UNSIGNED_BYTE, blank);
            }
            free(out);
            return;
        }
    }
#endif
    int64_t up_t0 = stsv_wall_us();
    glTexImage2D((GLenum) target, level, GL_RGBA, width, height, 0,
                 GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    stsv_upload_time(up_t0);
}

void stsv_tex_image_compressed(int target, int level, int internal_format,
                               int width, int height, int border,
                               int image_size, const void *blocks) {
#if defined(__vita__) || defined(VITA)
    stsv_unmark_compressed(stsv_bound_tex());
    stsv_unmark_glyph_page(stsv_bound_tex());
#endif
    /* width/height are the original size the game lays out with. The data may
     * be half or quarter size, so get the real size from image_size. Anything
     * else is a truncated file or the header-only stub from VitaFileHandle. */
    int block_bytes = internal_format == GL_COMPRESSED_RGB_S3TC_DXT1_EXT ? 8 : 16;
    int up_w = width, up_h = height;
    size_t full_size = (size_t) (width / 4) * (size_t) (height / 4) * block_bytes;
    size_t half_size = (width % 8 == 0 && height % 8 == 0)
            ? (size_t) (width / 8) * (size_t) (height / 8) * block_bytes : 0;
    size_t quarter_size = (width % 16 == 0 && height % 16 == 0)
            ? (size_t) (width / 16) * (size_t) (height / 16) * block_bytes : 0;
    if ((size_t) image_size == quarter_size && quarter_size != 0) {
        up_w = width / 4;
        up_h = height / 4;
    } else if ((size_t) image_size == half_size && half_size != 0) {
        up_w = width / 2;
        up_h = height / 2;
    } else if ((size_t) image_size != full_size) {
        unsigned char blank[64] = {0};
        glTexImage2D((GLenum) target, level, GL_RGBA, 4, 4, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, blank);
        return;
    }
#if defined(__vita__) || defined(VITA)
    if (!stsv_tex_can_alloc((size_t) image_size, up_w, up_h, "-compressed")) {
        unsigned char blank[64] = {0};
        glTexImage2D((GLenum) target, level, GL_RGBA, 4, 4, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, blank);
        return;
    }
#endif
    int64_t up_t0 = stsv_wall_us();
    glCompressedTexImage2D((GLenum) target, level, (GLenum) internal_format,
                           up_w, up_h, border, image_size, blocks);
    stsv_upload_time(up_t0);
#if defined(__vita__) || defined(VITA)
    /* vitaGL leaves a GL_INVALID_ENUM behind after compressed uploads. */
    (void) glGetError();
    stsv_mark_compressed();
#endif
}

extern int64_t stsv_wall_us(void);
static int64_t stsv_subtex_us_accum;
static int32_t stsv_subtex_calls_accum;

int64_t stsv_subtex_frame_stats(void) {
    int64_t ms = stsv_subtex_us_accum / 1000;
    int32_t calls = stsv_subtex_calls_accum > 999 ? 999 : stsv_subtex_calls_accum;
    stsv_subtex_us_accum = 0;
    stsv_subtex_calls_accum = 0;
    return ms * 1000 + calls;
}

void stsv_tex_sub_image_rgba(int target, int level, int xoff, int yoff,
                             int width, int height, const void *rgba) {
#if defined(__vita__) || defined(VITA)
    int id = stsv_bound_tex();
    if (stsv_is_compressed(id)) {
        return;
    }
    while (glGetError() != GL_NO_ERROR) { /* drain stale */ }
#endif
    int64_t t0 = stsv_wall_us();
    glTexSubImage2D((GLenum) target, level, xoff, yoff, width, height,
                    GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    stsv_subtex_us_accum += stsv_wall_us() - t0;
    stsv_subtex_calls_accum++;
#if defined(__vita__) || defined(VITA)
    while (glGetError() != GL_NO_ERROR) {}
#endif
}

/* glShaderSource takes char** in GL; Java passes a single NUL-terminated
 * string. void* param avoids char/int8_t signedness pedantry at the @Import
 * boundary (TeaVM array data is int8_t*). */
void stsv_glShaderSource(int shader, const void *source) {
    const char *sources[1] = {(const char *) source};
    glShaderSource((GLuint) shader, 1, sources, 0);
}

/* libGDX GL20 convenience methods: the real GL API only has the plural
 * glGenX/glDeleteX forms taking a count + buffer. Wrap with count=1. */

int stsv_gl_gen_texture(void) {
    GLuint id = 0;
    glGenTextures(1, &id);
    return (int) id;
}

void stsv_gl_delete_texture(int texture) {
    GLuint id = (GLuint) texture;
#if defined(__vita__) || defined(VITA)
    stsv_spill_refund(texture);
    stsv_unmark_compressed(texture);
    stsv_unmark_glyph_page(texture);
#endif
    glDeleteTextures(1, &id);
}

int stsv_gl_gen_buffer(void) {
    GLuint id = 0;
    glGenBuffers(1, &id);
    return (int) id;
}

void stsv_gl_delete_buffer(int buffer) {
    GLuint id = (GLuint) buffer;
    glDeleteBuffers(1, &id);
}

int stsv_gl_gen_framebuffer(void) {
    GLuint id = 0;
    glGenFramebuffers(1, &id);
    return (int) id;
}

void stsv_gl_delete_framebuffer(int framebuffer) {
    GLuint id = (GLuint) framebuffer;
    glDeleteFramebuffers(1, &id);
}

int stsv_gl_gen_renderbuffer(void) {
    GLuint id = 0;
    glGenRenderbuffers(1, &id);
    return (int) id;
}

void stsv_gl_delete_renderbuffer(int renderbuffer) {
    GLuint id = (GLuint) renderbuffer;
    glDeleteRenderbuffers(1, &id);
}
