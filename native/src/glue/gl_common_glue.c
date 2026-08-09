#include "stsv_gl.h"
#include "stsvita_glue.h"

#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <stdint.h>

#if defined(__vita__) || defined(VITA)
#include <malloc.h>
#include <psp2/kernel/sysmem.h>
#endif

#if defined(__vita__) || defined(VITA)
static void stsv_city_layer_load_diag(void);
#endif

/* Last image file read (VitaFileHandle.readBytes notes it) — names the
 * texture in the [DIAG dxt] SKIP/SPILL lines. Loads are synchronous on the
 * single game thread (read -> decode -> upload), so the last-read image is
 * the upload's source for every real asset. Dynamic pages (glyph atlases)
 * have no file and may show a stale name; they never hit the guard. */
static char stsv_tex_path[192] = "?";

void stsv_tex_note_path(const void *utf8, int32_t len) {
    int n = (int) sizeof(stsv_tex_path) - 1;
    if (len < n) n = (int) len;
    memcpy(stsv_tex_path, utf8, (size_t) n);
    stsv_tex_path[n] = '\0';
#if defined(__vita__) || defined(VITA)
    if (strstr(stsv_tex_path, "/cityScene/") != NULL) {
        stsv_city_layer_load_diag();
    }
#endif
}

/* Per-frame runtime stb_dxt compression + glTexImage upload time, polled by
 * the [hitch] logger like the ft/subtex accumulators (combat frame drops,
 *). */
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
        static int mark_logs;
        if (mark_logs < 128) {
            mark_logs++;
            fprintf(stderr, "[DIAG subtex] MARK id=%d path=%s\n", id, stsv_tex_path);
        }
    }
}

/* Once a glyph page, always a glyph page: incremental FreeType packer pages are born nearly
 * empty (>=96% transparent -> kept uncompressed so glTexSubImage2D works).
 * But when a page later takes a FULL re-upload (PixmapPacker's dirty flush
 * at the end of a GlyphLayout), it is by then well below 96% transparent —
 * dungeonTitleFont's page was ~78% after "Exordium" — so the old heuristic
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
 * the act-transition title rendered only "i" —
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
 * from newlib malloc (VGL_MEM_EXTERNAL), so allow a BOUNDED spill there —
 * but ONLY after boot: the newlib headroom is also where vitaGL gets its
 * per-frame vertex buffers, and letting the boot preload eat 24MB of it
 * crashed the first menu draw (memcpy into a failed buffer alloc inside
 * _glDrawElements,). Post-create the native
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
static size_t stsv_spill_peak;
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
    fprintf(stderr, "[DIAG dxt] spill enabled (budget %dKB, reserve %dKB)\n",
            (int) (STSV_SPILL_BUDGET >> 10), (int) (STSV_SPILL_RESERVE >> 10));
}

static size_t stsv_pool_maxfree(void) {
    size_t maxfree = vglMemFree(VGL_MEM_VRAM);
    size_t fr = vglMemFree(VGL_MEM_RAM);  if (fr > maxfree) maxfree = fr;
    fr = vglMemFree(VGL_MEM_SLOW);         if (fr > maxfree) maxfree = fr;
    return maxfree;
}

/* Narrow, removable Act-transition diagnostics. All output goes directly to
 * unbuffered stderr so it survives the same abort that loses buffered INFO. */
static int stsv_city_draw_tracking;
static int stsv_city_draw_seq;
static int stsv_trace_current_draw;
static GLenum stsv_current_draw_mode;
static GLsizei stsv_current_draw_count;
static GLenum stsv_current_draw_type;
static const GLvoid *stsv_current_draw_indices;

static void stsv_diag_memory(const char *event) {
    struct mallinfo mi = mallinfo();
    SceKernelFreeMemorySizeInfo free_info = {sizeof(free_info), 0, 0, 0};
    int free_rc = sceKernelGetFreeMemorySize(&free_info);
    fprintf(stderr,
            "[act2-diag] mem event=%s "
            "pool_free=%lu/%lu/%luKB pool_total=%lu/%lu/%luKB "
            "spill=%luKB peak=%luKB "
            "newlib_free=%luKB used=%luKB arena=%luKB top=%luKB blocks=%lu "
            "kernel_free_rc=0x%08x user=%dKB cdram=%dKB phycont=%dKB\n",
            event,
            (unsigned long) (vglMemFree(VGL_MEM_VRAM) >> 10),
            (unsigned long) (vglMemFree(VGL_MEM_RAM) >> 10),
            (unsigned long) (vglMemFree(VGL_MEM_SLOW) >> 10),
            (unsigned long) (vglMemTotal(VGL_MEM_VRAM) >> 10),
            (unsigned long) (vglMemTotal(VGL_MEM_RAM) >> 10),
            (unsigned long) (vglMemTotal(VGL_MEM_SLOW) >> 10),
            (unsigned long) (stsv_spill_used >> 10),
            (unsigned long) (stsv_spill_peak >> 10),
            (unsigned long) (mi.fordblks >> 10),
            (unsigned long) (mi.uordblks >> 10),
            (unsigned long) (mi.arena >> 10),
            (unsigned long) (mi.keepcost >> 10),
            (unsigned long) mi.ordblks,
            (unsigned int) free_rc,
            free_info.size_user >> 10,
            free_info.size_cdram >> 10,
            free_info.size_phycont >> 10);
}

void stsv_transition_diag(const void *utf8, int32_t len) {
    char label[160];
    int n = (int) sizeof(label) - 1;
    if (len < n) n = (int) len;
    memcpy(label, utf8, (size_t) n);
    label[n] = '\0';
    fprintf(stderr, "[act2-diag] boundary=%s\n", label);
    stsv_diag_memory(label);
    if (strstr(label, "city-music-open") != NULL) {
        stsv_city_draw_tracking = 1;
        stsv_city_draw_seq = 0;
    }
}

static void stsv_city_layer_load_diag(void) {
    fprintf(stderr, "[act2-diag] city-layer stage=load-begin path=%s\n",
            stsv_tex_path);
    stsv_diag_memory("city-layer-load-begin");
}

static void stsv_city_layer_admit_diag(const char *decision, size_t need,
                                       int width, int height, const char *tag) {
    if (strstr(stsv_tex_path, "/cityScene/") == NULL) {
        return;
    }
    fprintf(stderr,
            "[act2-diag] city-layer stage=admission decision=%s%s "
            "size=%dx%d need=%luKB path=%s\n",
            decision, tag, width, height, (unsigned long) (need >> 10),
            stsv_tex_path);
    stsv_diag_memory("city-layer-admission");
}

static void stsv_city_layer_read_admit_diag(const char *decision, size_t need) {
    if (strstr(stsv_tex_path, "/cityScene/") == NULL) {
        return;
    }
    fprintf(stderr,
            "[act2-diag] city-layer stage=read-admission decision=%s "
            "need=%luKB path=%s\n",
            decision, (unsigned long) (need >> 10), stsv_tex_path);
    stsv_diag_memory("city-layer-read-admission");
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
 * a big.dds payload off the SD card — the load-then-drop waste (17MB of
 * char-select shoulders at 0.7s each, ~14s of the save-slot stall) paid the
 * full read+decode cost for textures the guard then blanked anyway. */
int32_t stsv_tex_would_fit(int32_t need) {
    size_t n = (size_t) need;
    if (stsv_pool_maxfree() > n + (1u << 20)) {
        stsv_city_layer_read_admit_diag("pool", n);
        return 1;
    }
    if ((stsv_spill_enabled || stsv_boot_critical_texture())
            && stsv_spill_used + n <= STSV_SPILL_BUDGET) {
        void *probe = malloc(n + STSV_SPILL_RESERVE);
        if (probe != NULL) {
            free(probe);
            stsv_city_layer_read_admit_diag("spill", n);
            return 1;
        }
    }
    stsv_city_layer_read_admit_diag("skip", n);
    return 0;
}

static int stsv_tex_can_alloc(size_t need, int width, int height, const char *tag) {
    size_t maxfree = stsv_pool_maxfree();
    if (maxfree > need + (1u << 20)) {
        stsv_city_layer_admit_diag("pool", need, width, height, tag);
        return 1;
    }
    int boot_critical = !stsv_spill_enabled && stsv_boot_critical_texture();
    if ((stsv_spill_enabled || boot_critical)
            && stsv_spill_used + need <= STSV_SPILL_BUDGET) {
        void *probe = malloc(need + STSV_SPILL_RESERVE);
        if (probe != NULL) {
            free(probe);
            stsv_spill_used += need;
            stsv_spill_note(stsv_bound_tex(), need);
            if (stsv_spill_used > stsv_spill_peak) {
                stsv_spill_peak = stsv_spill_used;
            }
            fprintf(stderr, "[DIAG dxt] %sSPILL%s %dx%d need=%dKB spill_used=%dKB path=%s\n",
                    boot_critical ? "BOOT-" : "", tag,
                    width, height, (int) (need >> 10),
                    (int) (stsv_spill_used >> 10), stsv_tex_path);
            stsv_city_layer_admit_diag("spill", need, width, height, tag);
            return 1;
        }
    }
    static int skips = 0;
    if (skips++ < 96) {
        fprintf(stderr, "[DIAG dxt] SKIP%s %dx%d need=%dKB maxfree=%dKB spill_used=%dKB path=%s\n",
                tag, width, height, (int) (need >> 10), (int) (maxfree >> 10),
                (int) (stsv_spill_used >> 10), stsv_tex_path);
    }
    stsv_city_layer_admit_diag("skip", need, width, height, tag);
    return 0;
}

/* The installed vitaGL custom-shader path calls vgl_reserve_data_pool(size)
 * and unconditionally memcpy()s into its result. Linker wrapping preserves
 * that behavior while exposing the first City draws and an actual invalid
 * fallback result. sceKernelFindMemBlockByAddr cannot validate vitaGL/newlib
 * suballocations: it rejects valid interior ranges and made the first version
 * of this diagnostic emit tens of thousands of false INVALID records. */
extern unsigned char *circular_data_pool[];
extern unsigned char *circular_data_pool_ptr[];
extern unsigned char *circular_data_pool_limit[];
extern int vgl_circular_idx;

void *__real_vgl_reserve_data_pool(uint32_t size);

void *__wrap_vgl_reserve_data_pool(uint32_t size) {
    int idx = vgl_circular_idx;
    uintptr_t base = 0, before = 0, limit = 0;
    if (idx >= 0 && idx < 4) {
        base = (uintptr_t) circular_data_pool[idx];
        before = (uintptr_t) circular_data_pool_ptr[idx];
        limit = (uintptr_t) circular_data_pool_limit[idx];
    }
    int overflow = before > limit || (uintptr_t) size > limit - before;
    void *result = __real_vgl_reserve_data_pool(size);
    int invalid = result == NULL || (uintptr_t) result < UINT32_C(0x10000000);
    if (stsv_trace_current_draw || invalid) {
        fprintf(stderr,
                "[act2-diag] draw-reserve seq=%d mode=0x%x count=%d "
                "type=0x%x indices=%p size=%u result=%p "
                "invalid=%d idx=%d overflow=%d "
                "circular=%p ptr=%p limit=%p post_ptr=%p\n",
                stsv_city_draw_seq, (unsigned int) stsv_current_draw_mode,
                (int) stsv_current_draw_count,
                (unsigned int) stsv_current_draw_type,
                stsv_current_draw_indices, (unsigned int) size, result,
                invalid, idx, overflow, (void *) base, (void *) before, (void *) limit,
                idx >= 0 && idx < 4 ? (void *) circular_data_pool_ptr[idx] : NULL);
    }
    if (invalid) {
        stsv_diag_memory("draw-reserve-INVALID");
    }
    return result;
}

void __real_glDrawElements(GLenum mode, GLsizei count, GLenum type,
                           const GLvoid *indices);

void __wrap_glDrawElements(GLenum mode, GLsizei count, GLenum type,
                           const GLvoid *indices) {
    int traced = 0;
    if (stsv_city_draw_tracking) {
        stsv_city_draw_seq++;
        traced = stsv_city_draw_seq <= 24;
    }
    stsv_current_draw_mode = mode;
    stsv_current_draw_count = count;
    stsv_current_draw_type = type;
    stsv_current_draw_indices = indices;
    if (traced) {
        fprintf(stderr,
                "[act2-diag] city-draw seq=%d mode=0x%x count=%d "
                "type=0x%x indices=%p\n",
                stsv_city_draw_seq, (unsigned int) mode, (int) count,
                (unsigned int) type, indices);
        if (stsv_city_draw_seq == 1) {
            stsv_diag_memory("city-first-draw");
        }
    }
    stsv_trace_current_draw = traced;
    __real_glDrawElements(mode, count, type, indices);
    stsv_trace_current_draw = 0;
    if (stsv_city_draw_seq >= 24) {
        stsv_city_draw_tracking = 0;
    }
}
#endif

#if !defined(__vita__) && !defined(VITA)
/* Host: pools are unbounded, spilling never happens, everything fits. */
void stsv_tex_spill_enable(void) {
}

void stsv_transition_diag(const void *utf8, int32_t len) {
    (void) utf8;
    (void) len;
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
     * their re-upload is — see stsv_mark_glyph_page. */
    if (level == 0 && stsv_is_glyph_page(bound_id)) {
        int64_t up_t0 = stsv_wall_us();
        glTexImage2D((GLenum) target, level, GL_RGBA, width, height, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, rgba);
        stsv_upload_time(up_t0);
        static int page_reup_logs;
        if (page_reup_logs < 32) {
            page_reup_logs++;
            fprintf(stderr, "[DIAG subtex] PAGE-reupload id=%d %dx%d kept-rgba\n",
                    bound_id, width, height);
        }
        return;
    }
    if (width >= 256 && height >= 256 && (width & 3) == 0 && (height & 3) == 0) {
        const unsigned char *src = (const unsigned char *) rgba;
        int64_t dxt_t0 = stsv_wall_us();
        /* One pass: detect transparency (DXT5 vs DXT1) and whether the image is
         * fully blank. A blank upload is a dynamic texture — e.g. an incremental
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
         * transparent page) that get filled via glTexSubImage2D — invalid on a
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
            /* Glyph-page creation trace (pairs with [DIAG subtex]). */
            static int page_logs;
            if (page_logs < 32) {
                page_logs++;
                fprintf(stderr,
                        "[DIAG subtex] PAGE-upload id=%d %dx%d transparent=%d%%\n",
                        bound_id, width, height,
                        (int) (transparent * 100 / npx));
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
     * glyph-page id redefined from a.dds is no longer a glyph page. */
    stsv_unmark_compressed(stsv_bound_tex());
    stsv_unmark_glyph_page(stsv_bound_tex());
#endif
    /* width/height are the LOGICAL dimensions (what the game's layout/UV math
     * sees via texture.getWidth()). Reduced-data.dds files keep the original
     * dims in their header but carry blocks for half- or quarter-size uploads;
     * infer the actual upload dims from image_size. A size that matches none
     * of those encodings is the skip-read stub (bare 128B header,
     * VitaFileHandle) or a truncated file — upload a 4x4 blank rather than let
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

/* RGBA sub-upload. If the bound texture was stored compressed, skip it — a
 * compressed texture can't take an RGBA sub-image and vitaGL would fault.
 * The mark list is kept accurate across delete/redefine (stsv_unmark_
 * compressed), so this only fires for textures that are genuinely
 * compressed right now — never for a fresh glyph page on a recycled id. */
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
    /* Bounded glyph-subtex trace: glyph METRICS
     * are proven healthy on device, so either sub-uploads die GPU-side or
     * they never happen. Log every skip, every GL error, and the first 96
     * calls (id + rect), so the next device log shows exactly which glyph
     * pixels reached vitaGL. */
    static int subtex_logs;
    int id = stsv_bound_tex();
    if (stsv_is_compressed(id)) {
        if (subtex_logs < 192) {
            subtex_logs++;
            fprintf(stderr, "[DIAG subtex] SKIP-marked id=%d %dx%d at %d,%d\n",
                    id, width, height, xoff, yoff);
        }
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
    GLenum err = glGetError();
    if (subtex_logs < 96 || (err != GL_NO_ERROR && subtex_logs < 192)) {
        subtex_logs++;
        fprintf(stderr, "[DIAG subtex] id=%d %dx%d at %d,%d err=0x%04x\n",
                id, width, height, xoff, yoff, (unsigned) err);
    }
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
