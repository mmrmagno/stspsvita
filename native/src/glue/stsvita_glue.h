/* Declarations for every function the Java backend @Imports.
 * Implementations live in the sibling *_glue.c files (Vita) or in
 * host_glue.c (desktop-C debugging harness). Keep in sync with
 * backend/src/main/java/com/stsvita/backend/VitaNatives.java. */

#ifndef STSVITA_GLUE_H
#define STSVITA_GLUE_H

#include <stdint.h>

void stsv_gfx_init(void);
void stsv_swap_buffers(void);
void stsv_set_vsync(int32_t on);
int32_t stsv_is_host_harness(void);

/* Wall-clock us (counts through suspend; misc_glue.c). */
int64_t stsv_wall_us(void);

/* Enable the bounded post-boot texture spill (gl_common_glue.c). */
void stsv_tex_spill_enable(void);

/* Read-only guard preview (gl_common_glue.c): would a compressed texture of
 * this byte size be admitted right now? VitaFileHandle asks before reading a
 * big.dds payload so doomed textures skip the SD read (host: always 1). */
int32_t stsv_tex_would_fit(int32_t need);

/* Per-frame diagnostics, ms*1000+calls, reset on read (freetype_glue /
 * gl_common_glue / misc_glue). Polled by the [hitch] logger. */
int64_t stsv_ft_frame_stats(void);
int64_t stsv_subtex_frame_stats(void);
int64_t stsv_img_frame_stats(void);     /* stb PNG/JPG decode */
int64_t stsv_dxt_frame_stats(void);     /* runtime stb_dxt compression */
int64_t stsv_upload_frame_stats(void);  /* glTexImage/glCompressedTexImage */

/* Names the next texture upload in the [DIAG dxt] SKIP/SPILL lines
 * (gl_common_glue.c); VitaFileHandle notes every image read. utf8 need not
 * be NUL-terminated; len bytes are copied. */
void stsv_tex_note_path(const void *utf8, int32_t len);

/* Crash-safe Act-transition boundary with native memory/pool context
 * (gl_common_glue.c). The label is UTF-8 and need not be NUL-terminated. */
void stsv_transition_diag(const void *utf8, int32_t len);

/* sceImeDialog on-screen keyboard (ime_glue.c; host stubs in host_glue.c).
 * title/initial are NUL-terminated UTF-16 (Java chars); poll() once per
 * frame: 0=idle/running 1=text entered 2=canceled. */
int32_t stsv_ime_open(void *title_u16, void *initial_u16, int32_t max_len);
int32_t stsv_ime_poll(void);
int32_t stsv_ime_result(void *out_u16, int32_t cap_chars);
int32_t stsv_ime_active(void);

int32_t stsv_ctrl_buttons(void);
float stsv_ctrl_axis(int32_t axis);
int32_t stsv_touch_count(void);
int32_t stsv_touch_x(int32_t finger);
int32_t stsv_touch_y(int32_t finger);

/* --- OpenAL audio (audio_glue.c) ------------------------------------------
 * Music handles are pointers packed into int64_t. These declarations are
 * load-bearing: without them the TeaVM TUs called the functions implicitly
 * declared (int return), which truncated the 64-bit host's heap pointers to
 * 32 bits and crashed the harness at the first setLooping. The 32-bit Vita
 * only worked by accident. VitaAudio.java @Includes this header. */
int32_t stsv_sound_load(const void *data, int32_t len);
void    stsv_sound_free(int32_t buffer);
int32_t stsv_sound_play(int32_t buffer, float volume, float pitch, float pan, int32_t loop);
void    stsv_sound_stop(int32_t source);
void    stsv_sound_pause(int32_t source);
void    stsv_sound_resume(int32_t source);
void    stsv_sound_set_volume(int32_t source, float volume);
void    stsv_sound_set_pitch(int32_t source, float pitch);
void    stsv_sound_set_looping(int32_t source, int32_t loop);
int64_t stsv_music_open(const void *data, int32_t len);
int64_t stsv_music_open_path(const void *path_utf8, int32_t len);
void    stsv_music_free(int64_t handle);
void    stsv_music_play(int64_t handle);
void    stsv_music_pause(int64_t handle);
void    stsv_music_stop(int64_t handle);
void    stsv_music_update(int64_t handle);
int32_t stsv_music_is_playing(int64_t handle);
void    stsv_music_set_volume(int64_t handle, float volume);
void    stsv_music_set_looping(int64_t handle, int32_t loop);

/* --- FreeType (freetype_glue.c) ------------------------------------------
 * Backing for the TeaVM-interop shadow of gdx-freetype 1.9.5's FreeType.java
 * (backend/stubs/.../g2d/freetype/FreeType.java). FT pointers travel as
 * int64_t; "boolean" results are int32_t 0/1 (the !FT_Error idiom). */

int32_t stsv_ft_last_error(void);

/* library */
int64_t stsv_ft_init(void);
void    stsv_ft_done_freetype(int64_t library);
int64_t stsv_ft_new_memory_face(int64_t library, void* data, int32_t dataSize, int32_t faceIndex);
int64_t stsv_ft_stroker_new(int64_t library);

/* face */
void    stsv_ft_done_face(int64_t face);
int32_t stsv_ft_face_flags(int64_t face);
int32_t stsv_ft_style_flags(int64_t face);
int32_t stsv_ft_num_glyphs(int64_t face);
int32_t stsv_ft_face_ascender(int64_t face);
int32_t stsv_ft_face_descender(int64_t face);
int32_t stsv_ft_face_height(int64_t face);
int32_t stsv_ft_max_advance_width(int64_t face);
int32_t stsv_ft_max_advance_height(int64_t face);
int32_t stsv_ft_underline_position(int64_t face);
int32_t stsv_ft_underline_thickness(int64_t face);
int32_t stsv_ft_select_size(int64_t face, int32_t strike_index);
int32_t stsv_ft_set_char_size(int64_t face, int32_t charWidth, int32_t charHeight, int32_t horzResolution, int32_t vertResolution);
int32_t stsv_ft_set_pixel_sizes(int64_t face, int32_t pixelWidth, int32_t pixelHeight);
int32_t stsv_ft_load_glyph(int64_t face, int32_t glyphIndex, int32_t loadFlags);
int32_t stsv_ft_load_char(int64_t face, int32_t charCode, int32_t loadFlags);
int64_t stsv_ft_face_glyph(int64_t face);
int64_t stsv_ft_face_size(int64_t face);
int32_t stsv_ft_has_kerning(int64_t face);
int32_t stsv_ft_get_kerning(int64_t face, int32_t leftGlyph, int32_t rightGlyph, int32_t kernMode);
int32_t stsv_ft_get_char_index(int64_t face, int32_t charCode);

/* size / size metrics */
int64_t stsv_ft_size_metrics(int64_t size);
int32_t stsv_ft_sm_xppem(int64_t metrics);
int32_t stsv_ft_sm_yppem(int64_t metrics);
int32_t stsv_ft_sm_xscale(int64_t metrics);
int32_t stsv_ft_sm_yscale(int64_t metrics);
int32_t stsv_ft_sm_ascender(int64_t metrics);
int32_t stsv_ft_sm_descender(int64_t metrics);
int32_t stsv_ft_sm_height(int64_t metrics);
int32_t stsv_ft_sm_max_advance(int64_t metrics);

/* glyph slot */
int64_t stsv_ft_slot_metrics(int64_t slot);
int32_t stsv_ft_slot_linear_hori_advance(int64_t slot);
int32_t stsv_ft_slot_linear_vert_advance(int64_t slot);
int32_t stsv_ft_slot_advance_x(int64_t slot);
int32_t stsv_ft_slot_advance_y(int64_t slot);
int32_t stsv_ft_slot_format(int64_t slot);
int64_t stsv_ft_slot_bitmap(int64_t slot);
int32_t stsv_ft_slot_bitmap_left(int64_t slot);
int32_t stsv_ft_slot_bitmap_top(int64_t slot);
int32_t stsv_ft_render_glyph(int64_t slot, int32_t renderMode);
int64_t stsv_ft_get_glyph(int64_t glyphSlot);

/* standalone glyph (FT_Glyph / FT_BitmapGlyph) */
void    stsv_ft_done_glyph(int64_t glyph);
int64_t stsv_ft_stroke_border(int64_t glyph, int64_t stroker, int32_t inside);
int64_t stsv_ft_glyph_to_bitmap(int64_t glyph, int32_t renderMode);
int64_t stsv_ft_glyph_bitmap(int64_t glyph);
int32_t stsv_ft_glyph_left(int64_t glyph);
int32_t stsv_ft_glyph_top(int64_t glyph);

/* bitmap */
int32_t stsv_ft_bitmap_rows(int64_t bitmap);
int32_t stsv_ft_bitmap_width(int64_t bitmap);
int32_t stsv_ft_bitmap_pitch(int64_t bitmap);
void    stsv_ft_bitmap_copy(int64_t bitmap, void* dst, int32_t size);
int32_t stsv_ft_bitmap_num_grays(int64_t bitmap);
int32_t stsv_ft_bitmap_pixel_mode(int64_t bitmap);

/* glyph metrics */
int32_t stsv_ft_gm_width(int64_t metrics);
int32_t stsv_ft_gm_height(int64_t metrics);
int32_t stsv_ft_gm_hori_bearing_x(int64_t metrics);
int32_t stsv_ft_gm_hori_bearing_y(int64_t metrics);
int32_t stsv_ft_gm_hori_advance(int64_t metrics);
int32_t stsv_ft_gm_vert_bearing_x(int64_t metrics);
int32_t stsv_ft_gm_vert_bearing_y(int64_t metrics);
int32_t stsv_ft_gm_vert_advance(int64_t metrics);

/* stroker */
void    stsv_ft_stroker_set(int64_t stroker, int32_t radius, int32_t lineCap, int32_t lineJoin, int32_t miterLimit);
void    stsv_ft_stroker_done(int64_t stroker);

#endif
