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

/* Last image file read (VitaFileHandle.readBytes notes it) - names the
 * texture in the [tex] SKIP/SPILL lines. Loads are synchronous on the
 * single game thread (read -> decode -> upload), so the last-read image is
 * the upload's source for every real asset. Dynamic pages (glyph atlases)
 * have no file and may show a stale name; they never hit the guard. */
static char stsv_tex_path[192] = "?";

void stsv_tex_note_path(const void *utf8, int32_t len) {
    int n = (int) sizeof(stsv_tex_path) - 1;
    if (len < n) n = (int) len;
    memcpy(stsv_tex_path, utf8, (size_t) n);
    stsv_tex_path[n] = '\0';
}

/* Per-frame runtime stb_dxt compression + glTexImage upload time, polled by
 * the [hitch] logger like the ft/subtex accumulators (combat frame drops). */
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
/* Texture IDs we stored compressed. glTexSubImage2D with RGBA into a compressed
 * texture is invalid and faults vitaGL, so we track them and skip such
 * sub-uploads (see stsv_tex_sub_image_rgba). Mostly-transparent pages (font
 * glyph atlases, which ARE sub-uploaded) are left uncompressed instead. */
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

/* Once a glyph page, always a glyph page (the act-title "i"
 * bug's true mechanism): incremental FreeType packer pages are born nearly
 * empty (>=96% transparent -> kept uncompressed so glTexSubImage2D works).
 * But when a page later takes a FULL re-upload (PixmapPacker's dirty flush
 * at the end of a GlyphLayout), it is by then well below 96% transparent -
 * dungeonTitleFont's page was ~78% after "Exordium" - so the old heuristic
 * DXT-compressed it and marked it, and every glyph rasterized after that
 * flush was silently dropped by the sub-upload guard. "Exordium" (in the
 * compressed upload) rendered; "The City" kept only its shared glyph "i".
 * Track ids that ever uploaded as a glyph page and never compress them. */
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

/* Recycled ids again: a deleted glyph page's id must not pin the next
 * texture to the uncompressed path. */
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

/* GL recycles texture ids. Without this, a deleted compressed texture's id
 * stays marked, and when vitaGL hands the same id to a NEW uncompressed
 * texture, stsv_tex_sub_image_rgba silently drops every sub-upload into it.
 * Device-diagnosed: the act-transition title rendered only "i":
 * dungeonTitleFont's glyph page (created mid-run, after an act of monster
 * atlas create/delete churn) landed on a stale-marked id, so every glyph
 * rasterized after the page's initial full upload was invisible. Called on
 * delete and on any full (re)definition; compressed uploads re-mark. */
static void stsv_unmark_compressed(int id) {
    for (int i = 0; i < stsv_compressed_n; ) {
        if (stsv_compressed_ids[i] == id) {
            stsv_compressed_ids[i] = stsv_compressed_ids[--stsv_compressed_n];
        } else {
            i++;
        }
    }
}

/* When every vitaGL pool is full, textures used to be dropped to a 4x4 blank
 * (black backgrounds, invisible elites/bosses). vitaGL can also allocate
 * from newlib malloc (VGL_MEM_EXTERNAL), so allow a BOUNDED spill there -
 * but ONLY after boot: the newlib headroom is also where vitaGL gets its
 * per-frame vertex buffers, and letting the boot preload eat 24MB of it
 * crashed the first menu draw (memcpy into a failed buffer alloc inside
 * _glDrawElements, C2-12828 dump). Post-create the native
 * working set is stable, and the mid-run loads (boss atlases, scene
 * backgrounds) are exactly the drops the player actually sees. Each
 * admission is gated by a malloc probe that keeps a generous reserve for
 * FreeType + vitaGL runtime allocations. */
/* Emergency valve only: a 16MB spill starved vitaGL's draw-time
 * vertex buffers (memcpy crash in _glDrawElements loading an elite fight).
 * The current Act-2 candidate gets its texture headroom from the dedicated
 * RAM pool (main.c newlib 280->272MB); the spill stays tiny and heavily
 * reserved. The 272MB/6MB pair still requires physical-Vita acceptance. */
#define STSV_SPILL_BUDGET   (6u << 20)
#define STSV_SPILL_RESERVE  (48u << 20)
static size_t stsv_spill_used;
static int stsv_spill_enabled;

/* Live accounting: without a refund on glDeleteTextures the budget is a
 * lifetime high-water mark, and the character-select panels (loaded right
 * after boot, disposed on run start) consumed all 16MB before the first
 * fight. Track which texture ids were admitted and
 * give the bytes back when they are deleted or re-uploaded. */
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

/* Called from Java once the game's create() has finished (VitaApplication). */
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

/* These UI textures are loaded at the end of ImageMaster's preload or while
 * create() constructs DungeonMapScreen. The dedicated pools can already be
 * below their safety margin by then, which used to replace them with 4x4
 * blanks. Admit only this known-critical set into the existing bounded spill
 * before create() returns; unrelated boot art remains barred so draw buffers
 * retain their native headroom. */
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
        /* Last boot-time blanks: the four characters'
         * corpse + idle sheets and the megaCrit splash load at the very end
         * of the preload while pool maxfree is transiently ~14KB and the
         * spill is still gated. AbstractPlayer holds these textures for the
         * whole run (blank corpse on death screens). ~480KB total; the boot
         * spill is otherwise unused (0KB) since the 77MB-pool rebalance. */
        || (strstr(stsv_tex_path, "/images/characters/") != NULL
            && (strstr(stsv_tex_path, "corpse") != NULL
                || strstr(stsv_tex_path, "/idle/") != NULL))
        || strstr(stsv_tex_path, "/images/megaCrit") != NULL;
}

/* Read-only preview of stsv_tex_can_alloc: would a texture of this size be
 * admitted right now? Nothing is reserved. VitaFileHandle asks BEFORE reading
 * a big .dds payload off the SD card, the load-then-drop waste (17MB of
 * char-select shoulders at 0.7s each, ~14s of the save-slot stall) paid the
 * full read+decode cost for textures the guard then blanked anyway. */
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

/* Upload an RGBA8888 image. On the Vita, atlas pages (2048² RGBA = 16MB) blow
 * the texture budget, so compress them to DXT5 (4x smaller) on the fly and
 * upload the blocks; small/odd-sized textures and the host build upload plain
 * RGBA unchanged. */
void stsv_tex_image_rgba(int target, int level, int width, int height, const void *rgba) {
#if defined(__vita__) || defined(VITA)
    /* Full redefinition: the bound id is no longer compressed (recycled ids
     * kept stale marks and killed later glyph sub-uploads; see unmark). */
    int bound_id = stsv_bound_tex();
    stsv_unmark_compressed(bound_id);
    /* Known glyph pages must stay uncompressed forever, no matter how full
     * their re-upload is - see stsv_mark_glyph_page. */
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
        /* One pass: detect transparency (DXT5 vs DXT1) and whether the image is
         * fully blank. A blank upload is a dynamic texture - e.g. an incremental
         * FreeType glyph atlas that gets filled later via glTexSubImage2D, which
         * is INVALID on a compressed texture (vitaGL memcpy's out of bounds and
         * faults). Leave those uncompressed so sub-uploads work. */
        int has_alpha = 0;
        size_t npx = (size_t) width * height, transparent = 0;
        for (size_t i = 0; i < npx; i++) {
            unsigned char a = src[i * 4 + 3];
            if (a != 0xff) has_alpha = 1;
            if (a == 0) transparent++;
        }
        /* Near-empty pages are dynamic glyph atlases (a few glyphs on a big
         * transparent page) that get filled via glTexSubImage2D - invalid on a
         * compressed texture. Leave those uncompressed. The threshold is high
         * (96%) so that only genuine glyph pages qualify; sparse-but-real card/
         * image atlases stay compressed, keeping native heap free for fonts. */
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
            /* Guard: vitaGL's gpu_alloc_compressed_texture FAULTS (data abort)
             * when no allocation path can hold the texture. Upload when a pool
             * has room OR the bounded newlib spill accepts it; otherwise drop
             * to a 4x4 blank so the run survives (that atlas page renders
             * empty) instead of hard-crashing. */
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
    /* Redefinition clears any stale compressed mark on a recycled id; the
     * successful upload below re-marks. The 4x4-blank drop paths then leave
     * the texture correctly unmarked (they are plain RGBA). A recycled
     * glyph-page id redefined from a .dds is no longer a glyph page. */
    stsv_unmark_compressed(stsv_bound_tex());
    stsv_unmark_glyph_page(stsv_bound_tex());
#endif
    /* width/height are the LOGICAL dimensions (what the game's layout/UV math
     * sees via texture.getWidth()). Reduced-data .dds files keep the original
     * dims in their header but carry blocks for half- or quarter-size uploads;
     * infer the actual upload dims from image_size. A size that matches none
     * of those encodings is the skip-read stub (bare 128B header,
     * VitaFileHandle) or a truncated file - upload a 4x4 blank rather than let
     * vitaGL read out of bounds. */
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
    /* Same guard as stsv_tex_image_rgba: vitaGL's texture allocator FAULTS
     * (data abort inside vglGetTexDataPointer / gpu_alloc_compressed_texture)
     * when no allocation path can hold the texture. Pool room OR bounded
     * newlib spill; otherwise drop to a 4x4 blank so the run survives (that
     * page renders empty) instead of hard-crashing (C2-12828-1). */
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
    /* vitaGL reports a benign GL_INVALID_ENUM after compressed uploads.
     * Drain it so it cannot leak into game code, without synchronously
     * logging every texture load to the memory card. */
    (void) glGetError();
    stsv_mark_compressed();
#endif
}

/* RGBA sub-upload. If the bound texture was stored compressed, skip it - a
 * compressed texture can't take an RGBA sub-image and vitaGL would fault.
 * The mark list is kept accurate across delete/redefine (stsv_unmark_
 * compressed), so this only fires for textures that are genuinely
 * compressed right now - never for a fresh glyph page on a recycled id. */
/* Per-frame sub-upload time (glyph atlas writes), polled by the [hitch]
 * logger together with the FreeType accumulator. */
extern int64_t stsv_wall_us(void);
static int64_t stsv_subtex_us_accum;
static int32_t stsv_subtex_calls_accum;

/* Returns ms*1000 + calls (calls capped at 999) and resets. */
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
