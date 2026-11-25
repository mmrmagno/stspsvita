/* FreeType glue for the TeaVM-interop FreeType.java shadow
 * (backend/stubs/.../g2d/freetype/FreeType.java).
 *
 * Each stsv_ft_* function mirrors one jnigen-embedded C body from
 * gdx-freetype 1.9.5's FreeType.java, with JNI types replaced by fixed-width
 * ones: FT pointers travel as int64_t (cast through uintptr_t so 32-bit Vita
 * and 64-bit host both work), booleans as int32_t (0/1).
 *
 * Compiles unchanged against the host's system freetype2 and vitasdk's
 * freetype - no platform-specific code.
 */

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include <ft2build.h>
#include FT_FREETYPE_H
#include FT_STROKER_H
#include FT_GLYPH_H

#include "stsvita_glue.h"

#define FT_PTR(type, v) ((type)(uintptr_t)(v))
#define PTR_JLONG(p) ((int64_t)(uintptr_t)(p))

static int32_t stsv_ft_lastError = 0;

int32_t stsv_ft_last_error(void) {
    return stsv_ft_lastError;
}

/* --- library --- */

int64_t stsv_ft_init(void) {
    FT_Library library = 0;
    FT_Error error = FT_Init_FreeType(&library);
    if (error) {
        stsv_ft_lastError = error;
        return 0;
    }
    return PTR_JLONG(library);
}

void stsv_ft_done_freetype(int64_t library) {
    FT_Done_FreeType(FT_PTR(FT_Library, library));
}

int64_t stsv_ft_new_memory_face(int64_t library, void* data, int32_t dataSize, int32_t faceIndex) {
    /* FreeType keeps referencing the font bytes for the face's lifetime, but
     * `data` points into a GC-movable Java array (TeaVM's mark-compact GC
     * relocates it). Copy into stable malloc memory, stash the copy pointer
     * in face->generic.data, and free it in stsv_ft_done_face. */
    void* copy = malloc((size_t) dataSize);
    if (!copy) {
        stsv_ft_lastError = FT_Err_Out_Of_Memory;
        return 0;
    }
    memcpy(copy, data, (size_t) dataSize);
    FT_Face face = 0;
    FT_Error error = FT_New_Memory_Face(FT_PTR(FT_Library, library), (const FT_Byte*)copy, dataSize, faceIndex, &face);
    if (error) {
        free(copy);
        stsv_ft_lastError = error;
        return 0;
    }
    face->generic.data = copy;
    face->generic.finalizer = 0;
    return PTR_JLONG(face);
}

int64_t stsv_ft_stroker_new(int64_t library) {
    FT_Stroker stroker;
    FT_Error error = FT_Stroker_New(FT_PTR(FT_Library, library), &stroker);
    if (error) {
        stsv_ft_lastError = error;
        return 0;
    }
    return PTR_JLONG(stroker);
}

/* --- face --- */

void stsv_ft_done_face(int64_t face) {
    FT_Face f = FT_PTR(FT_Face, face);
    void* copy = f->generic.data;   /* the malloc'd font-bytes copy, if any */
    FT_Done_Face(f);
    free(copy);
}

int32_t stsv_ft_face_flags(int64_t face) {
    return (int32_t)FT_PTR(FT_Face, face)->face_flags;
}

int32_t stsv_ft_style_flags(int64_t face) {
    return (int32_t)FT_PTR(FT_Face, face)->style_flags;
}

int32_t stsv_ft_num_glyphs(int64_t face) {
    return (int32_t)FT_PTR(FT_Face, face)->num_glyphs;
}

int32_t stsv_ft_face_ascender(int64_t face) {
    return FT_PTR(FT_Face, face)->ascender;
}

int32_t stsv_ft_face_descender(int64_t face) {
    return FT_PTR(FT_Face, face)->descender;
}

int32_t stsv_ft_face_height(int64_t face) {
    return FT_PTR(FT_Face, face)->height;
}

int32_t stsv_ft_max_advance_width(int64_t face) {
    return FT_PTR(FT_Face, face)->max_advance_width;
}

int32_t stsv_ft_max_advance_height(int64_t face) {
    return FT_PTR(FT_Face, face)->max_advance_height;
}

int32_t stsv_ft_underline_position(int64_t face) {
    return FT_PTR(FT_Face, face)->underline_position;
}

int32_t stsv_ft_underline_thickness(int64_t face) {
    return FT_PTR(FT_Face, face)->underline_thickness;
}

int32_t stsv_ft_select_size(int64_t face, int32_t strike_index) {
    return !FT_Select_Size(FT_PTR(FT_Face, face), strike_index);
}

int32_t stsv_ft_set_char_size(int64_t face, int32_t charWidth, int32_t charHeight, int32_t horzResolution, int32_t vertResolution) {
    return !FT_Set_Char_Size(FT_PTR(FT_Face, face), charWidth, charHeight, horzResolution, vertResolution);
}

int32_t stsv_ft_set_pixel_sizes(int64_t face, int32_t pixelWidth, int32_t pixelHeight) {
    return !FT_Set_Pixel_Sizes(FT_PTR(FT_Face, face), pixelWidth, pixelHeight);
}

int32_t stsv_ft_load_glyph(int64_t face, int32_t glyphIndex, int32_t loadFlags) {
    return !FT_Load_Glyph(FT_PTR(FT_Face, face), glyphIndex, loadFlags);
}

int32_t stsv_ft_load_char(int64_t face, int32_t charCode, int32_t loadFlags) {
    return !FT_Load_Char(FT_PTR(FT_Face, face), charCode, loadFlags);
}

int64_t stsv_ft_face_glyph(int64_t face) {
    return PTR_JLONG(FT_PTR(FT_Face, face)->glyph);
}

int64_t stsv_ft_face_size(int64_t face) {
    return PTR_JLONG(FT_PTR(FT_Face, face)->size);
}

int32_t stsv_ft_has_kerning(int64_t face) {
    return FT_HAS_KERNING(FT_PTR(FT_Face, face)) ? 1 : 0;
}

int32_t stsv_ft_get_kerning(int64_t face, int32_t leftGlyph, int32_t rightGlyph, int32_t kernMode) {
    FT_Vector kerning;
    FT_Error error = FT_Get_Kerning(FT_PTR(FT_Face, face), leftGlyph, rightGlyph, kernMode, &kerning);
    if (error) return 0;
    return (int32_t)kerning.x;
}

int32_t stsv_ft_get_char_index(int64_t face, int32_t charCode) {
    return (int32_t)FT_Get_Char_Index(FT_PTR(FT_Face, face), charCode);
}

/* --- size / size metrics --- */

int64_t stsv_ft_size_metrics(int64_t size) {
    return PTR_JLONG(&FT_PTR(FT_Size, size)->metrics);
}

int32_t stsv_ft_sm_xppem(int64_t metrics) {
    return FT_PTR(FT_Size_Metrics*, metrics)->x_ppem;
}

int32_t stsv_ft_sm_yppem(int64_t metrics) {
    return FT_PTR(FT_Size_Metrics*, metrics)->y_ppem;
}

int32_t stsv_ft_sm_xscale(int64_t metrics) {
    return (int32_t)FT_PTR(FT_Size_Metrics*, metrics)->x_scale;
}

int32_t stsv_ft_sm_yscale(int64_t metrics) {
    /* NOTE: gdx-freetype 1.9.5's jnigen body for getYscale reads x_scale.
     * Reproduced bit-for-bit so behavior matches the desktop game. */
    return (int32_t)FT_PTR(FT_Size_Metrics*, metrics)->x_scale;
}

int32_t stsv_ft_sm_ascender(int64_t metrics) {
    return (int32_t)FT_PTR(FT_Size_Metrics*, metrics)->ascender;
}

int32_t stsv_ft_sm_descender(int64_t metrics) {
    return (int32_t)FT_PTR(FT_Size_Metrics*, metrics)->descender;
}

int32_t stsv_ft_sm_height(int64_t metrics) {
    return (int32_t)FT_PTR(FT_Size_Metrics*, metrics)->height;
}

int32_t stsv_ft_sm_max_advance(int64_t metrics) {
    return (int32_t)FT_PTR(FT_Size_Metrics*, metrics)->max_advance;
}

/* --- glyph slot --- */

int64_t stsv_ft_slot_metrics(int64_t slot) {
    return PTR_JLONG(&FT_PTR(FT_GlyphSlot, slot)->metrics);
}

int32_t stsv_ft_slot_linear_hori_advance(int64_t slot) {
    return (int32_t)FT_PTR(FT_GlyphSlot, slot)->linearHoriAdvance;
}

int32_t stsv_ft_slot_linear_vert_advance(int64_t slot) {
    return (int32_t)FT_PTR(FT_GlyphSlot, slot)->linearVertAdvance;
}

int32_t stsv_ft_slot_advance_x(int64_t slot) {
    return (int32_t)FT_PTR(FT_GlyphSlot, slot)->advance.x;
}

int32_t stsv_ft_slot_advance_y(int64_t slot) {
    return (int32_t)FT_PTR(FT_GlyphSlot, slot)->advance.y;
}

int32_t stsv_ft_slot_format(int64_t slot) {
    return (int32_t)FT_PTR(FT_GlyphSlot, slot)->format;
}

int64_t stsv_ft_slot_bitmap(int64_t slot) {
    return PTR_JLONG(&FT_PTR(FT_GlyphSlot, slot)->bitmap);
}

int32_t stsv_ft_slot_bitmap_left(int64_t slot) {
    return FT_PTR(FT_GlyphSlot, slot)->bitmap_left;
}

int32_t stsv_ft_slot_bitmap_top(int64_t slot) {
    return FT_PTR(FT_GlyphSlot, slot)->bitmap_top;
}

/* Per-frame rasterization time, polled by the [hitch] logger (glyph
 * generation is the prime suspect for the un-attributed combat frame
 * drops - damage numbers rasterize new glyph sizes mid-fight). */
extern int64_t stsv_wall_us(void);
static int64_t stsv_ft_us_accum;
static int32_t stsv_ft_calls_accum;

/* Returns ms*1000 + calls (calls capped at 999) and resets. */
int64_t stsv_ft_frame_stats(void) {
    int64_t ms = stsv_ft_us_accum / 1000;
    int32_t calls = stsv_ft_calls_accum > 999 ? 999 : stsv_ft_calls_accum;
    stsv_ft_us_accum = 0;
    stsv_ft_calls_accum = 0;
    return ms * 1000 + calls;
}

int32_t stsv_ft_render_glyph(int64_t slot, int32_t renderMode) {
    int64_t t0 = stsv_wall_us();
    int32_t r = !FT_Render_Glyph(FT_PTR(FT_GlyphSlot, slot), (FT_Render_Mode)renderMode);
    stsv_ft_us_accum += stsv_wall_us() - t0;
    stsv_ft_calls_accum++;
    return r;
}

int64_t stsv_ft_get_glyph(int64_t glyphSlot) {
    FT_Glyph glyph;
    FT_Error error = FT_Get_Glyph(FT_PTR(FT_GlyphSlot, glyphSlot), &glyph);
    if (error) {
        stsv_ft_lastError = error;
        return 0;
    }
    return PTR_JLONG(glyph);
}

/* --- standalone glyph (FT_Glyph / FT_BitmapGlyph) --- */

void stsv_ft_done_glyph(int64_t glyph) {
    FT_Done_Glyph(FT_PTR(FT_Glyph, glyph));
}

int64_t stsv_ft_stroke_border(int64_t glyph, int64_t stroker, int32_t inside) {
    FT_Glyph border_glyph = FT_PTR(FT_Glyph, glyph);
    FT_Glyph_StrokeBorder(&border_glyph, FT_PTR(FT_Stroker, stroker), inside, 1);
    return PTR_JLONG(border_glyph);
}

int64_t stsv_ft_glyph_to_bitmap(int64_t glyph, int32_t renderMode) {
    int64_t t0 = stsv_wall_us();
    FT_Glyph bitmap = FT_PTR(FT_Glyph, glyph);
    FT_Error error = FT_Glyph_To_Bitmap(&bitmap, (FT_Render_Mode)renderMode, NULL, 1);
    stsv_ft_us_accum += stsv_wall_us() - t0;
    stsv_ft_calls_accum++;
    if (error) {
        stsv_ft_lastError = error;
        return 0;
    }
    return PTR_JLONG(bitmap);
}

int64_t stsv_ft_glyph_bitmap(int64_t glyph) {
    return PTR_JLONG(&FT_PTR(FT_BitmapGlyph, glyph)->bitmap);
}

int32_t stsv_ft_glyph_left(int64_t glyph) {
    return FT_PTR(FT_BitmapGlyph, glyph)->left;
}

int32_t stsv_ft_glyph_top(int64_t glyph) {
    return FT_PTR(FT_BitmapGlyph, glyph)->top;
}

/* --- bitmap --- */

int32_t stsv_ft_bitmap_rows(int64_t bitmap) {
    return (int32_t)FT_PTR(FT_Bitmap*, bitmap)->rows;
}

int32_t stsv_ft_bitmap_width(int64_t bitmap) {
    return (int32_t)FT_PTR(FT_Bitmap*, bitmap)->width;
}

int32_t stsv_ft_bitmap_pitch(int64_t bitmap) {
    return FT_PTR(FT_Bitmap*, bitmap)->pitch;
}

/* Replaces the JNI original's NewDirectByteBuffer view: copies the glyph
 * bitmap (rows * |pitch| bytes) into a Java-owned direct buffer. */
void stsv_ft_bitmap_copy(int64_t bitmap, void* dst, int32_t size) {
    memcpy(dst, FT_PTR(FT_Bitmap*, bitmap)->buffer, (size_t)size);
}

int32_t stsv_ft_bitmap_num_grays(int64_t bitmap) {
    return FT_PTR(FT_Bitmap*, bitmap)->num_grays;
}

int32_t stsv_ft_bitmap_pixel_mode(int64_t bitmap) {
    return FT_PTR(FT_Bitmap*, bitmap)->pixel_mode;
}

/* --- glyph metrics (26.6 fixed point, converted by FreeType.toInt in Java) --- */

int32_t stsv_ft_gm_width(int64_t metrics) {
    return (int32_t)FT_PTR(FT_Glyph_Metrics*, metrics)->width;
}

int32_t stsv_ft_gm_height(int64_t metrics) {
    return (int32_t)FT_PTR(FT_Glyph_Metrics*, metrics)->height;
}

int32_t stsv_ft_gm_hori_bearing_x(int64_t metrics) {
    return (int32_t)FT_PTR(FT_Glyph_Metrics*, metrics)->horiBearingX;
}

int32_t stsv_ft_gm_hori_bearing_y(int64_t metrics) {
    return (int32_t)FT_PTR(FT_Glyph_Metrics*, metrics)->horiBearingY;
}

int32_t stsv_ft_gm_hori_advance(int64_t metrics) {
    return (int32_t)FT_PTR(FT_Glyph_Metrics*, metrics)->horiAdvance;
}

int32_t stsv_ft_gm_vert_bearing_x(int64_t metrics) {
    return (int32_t)FT_PTR(FT_Glyph_Metrics*, metrics)->vertBearingX;
}

int32_t stsv_ft_gm_vert_bearing_y(int64_t metrics) {
    return (int32_t)FT_PTR(FT_Glyph_Metrics*, metrics)->vertBearingY;
}

int32_t stsv_ft_gm_vert_advance(int64_t metrics) {
    return (int32_t)FT_PTR(FT_Glyph_Metrics*, metrics)->vertAdvance;
}

/* --- stroker --- */

void stsv_ft_stroker_set(int64_t stroker, int32_t radius, int32_t lineCap, int32_t lineJoin, int32_t miterLimit) {
    FT_Stroker_Set(FT_PTR(FT_Stroker, stroker), radius, (FT_Stroker_LineCap)lineCap, (FT_Stroker_LineJoin)lineJoin, miterLimit);
}

void stsv_ft_stroker_done(int64_t stroker) {
    FT_Stroker_Done(FT_PTR(FT_Stroker, stroker));
}
