/*******************************************************************************
 * Copyright 2011 See AUTHORS file.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.badlogic.gdx.graphics.g2d.freetype;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import org.teavm.interop.Address;
import org.teavm.interop.Import;
import org.teavm.interop.c.Include;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Pixmap.Blending;
import com.badlogic.gdx.graphics.Pixmap.Format;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.LongMap;

import com.stsvita.backend.Buffers;

/**
 * TeaVM-interop port of gdx-freetype 1.9.5's jnigen FreeType wrapper.
 * Shadows the copy bundled in the game jar (classpath shadowing): the
 * public/package API is byte-for-byte identical to 1.9.5 so the jar's
 * {@code FreeTypeFontGenerator} links against it unchanged. Every former
 * JNI native is an {@code @Import} of a {@code stsv_ft_*} C function in
 * native/src/glue/freetype_glue.c, whose bodies follow the embedded jnigen
 * C verbatim (including the historical getYscale-returns-x_scale quirk).
 * FT pointers travel as {@code long}, exactly like the jnigen original.
 */
@Include("stsvita_glue.h")
public class FreeType {

	/** @return returns the last error code FreeType reported */
	static int getLastErrorCode () {
		return ftLastError();
	}

	@Import(name = "stsv_ft_last_error")
	static native int ftLastError ();

	private static class Pointer {
		long address;

		Pointer (long address) {
			this.address = address;
		}
	}

	public static class Library extends Pointer implements Disposable {
		LongMap<ByteBuffer> fontData = new LongMap<ByteBuffer>();

		Library (long address) {
			super(address);
		}

		@Override
		public void dispose () {
			ftDoneFreeType(address);
			for (ByteBuffer buffer : fontData.values()) {
				BufferUtils.disposeUnsafeByteBuffer(buffer);
			}
		}

		public Face newFace (FileHandle font, int faceIndex) {
			byte[] data = font.readBytes();
			return newMemoryFace(data, data.length, faceIndex);
		}

		public Face newMemoryFace (byte[] data, int dataSize, int faceIndex) {
			ByteBuffer buffer = BufferUtils.newUnsafeByteBuffer(data.length);
			BufferUtils.copy(data, 0, buffer, data.length);
			return newMemoryFace(buffer, faceIndex);
		}

		public Face newMemoryFace (ByteBuffer buffer, int faceIndex) {
			// The Vita C glue copies the font bytes into stable malloc memory
			// before calling FT_New_Memory_Face, because TeaVM array-backed
			// buffers can move during GC. That native copy is released in
			// stsv_ft_done_face, so the Java staging buffer can be released as
			// soon as the face has been created.
			long face = ftNewMemoryFace(address, Buffers.addr(buffer), buffer.remaining(), faceIndex);
			if (face == 0) {
				disposeFontBuffer(buffer);
				throw new GdxRuntimeException("Couldn't load font, FreeType error code: " + getLastErrorCode());
			} else {
				disposeFontBuffer(buffer);
				return new Face(face, this);
			}
		}

		private static void disposeFontBuffer (ByteBuffer buffer) {
			try {
				BufferUtils.disposeUnsafeByteBuffer(buffer);
			} catch (IllegalArgumentException ignored) {
				// Externally supplied ByteBuffers are not owned by BufferUtils.
			}
		}

		public Stroker createStroker () {
			long stroker = ftStrokerNew(address);
			if (stroker == 0) throw new GdxRuntimeException("Couldn't create FreeType stroker, FreeType error code: " + getLastErrorCode());
			return new Stroker(stroker);
		}
	}

	@Import(name = "stsv_ft_done_freetype")
	static native void ftDoneFreeType (long library);

	@Import(name = "stsv_ft_new_memory_face")
	static native long ftNewMemoryFace (long library, Address data, int dataSize, int faceIndex);

	@Import(name = "stsv_ft_stroker_new")
	static native long ftStrokerNew (long library);

	public static class Face extends Pointer implements Disposable {
		Library library;

		public Face (long address, Library library) {
			super(address);
			this.library = library;
		}

		@Override
		public void dispose () {
			ftDoneFace(address);
			ByteBuffer buffer = library.fontData.get(address);
			if (buffer != null) {
				library.fontData.remove(address);
				BufferUtils.disposeUnsafeByteBuffer(buffer);
			}
		}

		public int getFaceFlags () {
			return ftFaceFlags(address);
		}

		public int getStyleFlags () {
			return ftStyleFlags(address);
		}

		public int getNumGlyphs () {
			return ftNumGlyphs(address);
		}

		public int getAscender () {
			return ftFaceAscender(address);
		}

		public int getDescender () {
			return ftFaceDescender(address);
		}

		public int getHeight () {
			return ftFaceHeight(address);
		}

		public int getMaxAdvanceWidth () {
			return ftMaxAdvanceWidth(address);
		}

		public int getMaxAdvanceHeight () {
			return ftMaxAdvanceHeight(address);
		}

		public int getUnderlinePosition () {
			return ftUnderlinePosition(address);
		}

		public int getUnderlineThickness () {
			return ftUnderlineThickness(address);
		}

		public boolean selectSize (int strikeIndex) {
			return ftSelectSize(address, strikeIndex) != 0;
		}

		public boolean setCharSize (int charWidth, int charHeight, int horzResolution, int vertResolution) {
			return ftSetCharSize(address, charWidth, charHeight, horzResolution, vertResolution) != 0;
		}

		public boolean setPixelSizes (int pixelWidth, int pixelHeight) {
			return ftSetPixelSizes(address, pixelWidth, pixelHeight) != 0;
		}

		public boolean loadGlyph (int glyphIndex, int loadFlags) {
			return ftLoadGlyph(address, glyphIndex, loadFlags) != 0;
		}

		public boolean loadChar (int charCode, int loadFlags) {
			return ftLoadChar(address, charCode, loadFlags) != 0;
		}

		public GlyphSlot getGlyph () {
			return new GlyphSlot(ftFaceGlyph(address));
		}

		public Size getSize () {
			return new Size(ftFaceSize(address));
		}

		public boolean hasKerning () {
			return ftHasKerning(address) != 0;
		}

		public int getKerning (int leftGlyph, int rightGlyph, int kernMode) {
			return ftGetKerning(address, leftGlyph, rightGlyph, kernMode);
		}

		public int getCharIndex (int charCode) {
			return ftGetCharIndex(address, charCode);
		}
	}

	@Import(name = "stsv_ft_done_face")
	static native void ftDoneFace (long face);

	@Import(name = "stsv_ft_face_flags")
	static native int ftFaceFlags (long face);

	@Import(name = "stsv_ft_style_flags")
	static native int ftStyleFlags (long face);

	@Import(name = "stsv_ft_num_glyphs")
	static native int ftNumGlyphs (long face);

	@Import(name = "stsv_ft_face_ascender")
	static native int ftFaceAscender (long face);

	@Import(name = "stsv_ft_face_descender")
	static native int ftFaceDescender (long face);

	@Import(name = "stsv_ft_face_height")
	static native int ftFaceHeight (long face);

	@Import(name = "stsv_ft_max_advance_width")
	static native int ftMaxAdvanceWidth (long face);

	@Import(name = "stsv_ft_max_advance_height")
	static native int ftMaxAdvanceHeight (long face);

	@Import(name = "stsv_ft_underline_position")
	static native int ftUnderlinePosition (long face);

	@Import(name = "stsv_ft_underline_thickness")
	static native int ftUnderlineThickness (long face);

	@Import(name = "stsv_ft_select_size")
	static native int ftSelectSize (long face, int strikeIndex);

	@Import(name = "stsv_ft_set_char_size")
	static native int ftSetCharSize (long face, int charWidth, int charHeight, int horzResolution, int vertResolution);

	@Import(name = "stsv_ft_set_pixel_sizes")
	static native int ftSetPixelSizes (long face, int pixelWidth, int pixelHeight);

	@Import(name = "stsv_ft_load_glyph")
	static native int ftLoadGlyph (long face, int glyphIndex, int loadFlags);

	@Import(name = "stsv_ft_load_char")
	static native int ftLoadChar (long face, int charCode, int loadFlags);

	@Import(name = "stsv_ft_face_glyph")
	static native long ftFaceGlyph (long face);

	@Import(name = "stsv_ft_face_size")
	static native long ftFaceSize (long face);

	@Import(name = "stsv_ft_has_kerning")
	static native int ftHasKerning (long face);

	@Import(name = "stsv_ft_get_kerning")
	static native int ftGetKerning (long face, int leftGlyph, int rightGlyph, int kernMode);

	@Import(name = "stsv_ft_get_char_index")
	static native int ftGetCharIndex (long face, int charCode);

	public static class Size extends Pointer {
		Size (long address) {
			super(address);
		}

		public SizeMetrics getMetrics () {
			return new SizeMetrics(ftSizeMetrics(address));
		}
	}

	@Import(name = "stsv_ft_size_metrics")
	static native long ftSizeMetrics (long address);

	public static class SizeMetrics extends Pointer {
		SizeMetrics (long address) {
			super(address);
		}

		public int getXppem () {
			return ftSmXppem(address);
		}

		public int getYppem () {
			return ftSmYppem(address);
		}

		public int getXScale () {
			return ftSmXscale(address);
		}

		public int getYscale () {
			// NOTE: the jnigen original reads x_scale here too - preserved.
			return ftSmYscale(address);
		}

		public int getAscender () {
			return ftSmAscender(address);
		}

		public int getDescender () {
			return ftSmDescender(address);
		}

		public int getHeight () {
			return ftSmHeight(address);
		}

		public int getMaxAdvance () {
			return ftSmMaxAdvance(address);
		}
	}

	@Import(name = "stsv_ft_sm_xppem")
	static native int ftSmXppem (long metrics);

	@Import(name = "stsv_ft_sm_yppem")
	static native int ftSmYppem (long metrics);

	@Import(name = "stsv_ft_sm_xscale")
	static native int ftSmXscale (long metrics);

	@Import(name = "stsv_ft_sm_yscale")
	static native int ftSmYscale (long metrics);

	@Import(name = "stsv_ft_sm_ascender")
	static native int ftSmAscender (long metrics);

	@Import(name = "stsv_ft_sm_descender")
	static native int ftSmDescender (long metrics);

	@Import(name = "stsv_ft_sm_height")
	static native int ftSmHeight (long metrics);

	@Import(name = "stsv_ft_sm_max_advance")
	static native int ftSmMaxAdvance (long metrics);

	public static class GlyphSlot extends Pointer {
		GlyphSlot (long address) {
			super(address);
		}

		public GlyphMetrics getMetrics () {
			return new GlyphMetrics(ftSlotMetrics(address));
		}

		public int getLinearHoriAdvance () {
			return ftSlotLinearHoriAdvance(address);
		}

		public int getLinearVertAdvance () {
			return ftSlotLinearVertAdvance(address);
		}

		public int getAdvanceX () {
			return ftSlotAdvanceX(address);
		}

		public int getAdvanceY () {
			return ftSlotAdvanceY(address);
		}

		public int getFormat () {
			return ftSlotFormat(address);
		}

		public Bitmap getBitmap () {
			return new Bitmap(ftSlotBitmap(address));
		}

		public int getBitmapLeft () {
			return ftSlotBitmapLeft(address);
		}

		public int getBitmapTop () {
			return ftSlotBitmapTop(address);
		}

		public boolean renderGlyph (int renderMode) {
			return ftRenderGlyph(address, renderMode) != 0;
		}

		public Glyph getGlyph () {
			long glyph = ftGetGlyph(address);
			if (glyph == 0) throw new GdxRuntimeException("Couldn't get glyph, FreeType error code: " + getLastErrorCode());
			return new Glyph(glyph);
		}
	}

	@Import(name = "stsv_ft_slot_metrics")
	static native long ftSlotMetrics (long slot);

	@Import(name = "stsv_ft_slot_linear_hori_advance")
	static native int ftSlotLinearHoriAdvance (long slot);

	@Import(name = "stsv_ft_slot_linear_vert_advance")
	static native int ftSlotLinearVertAdvance (long slot);

	@Import(name = "stsv_ft_slot_advance_x")
	static native int ftSlotAdvanceX (long slot);

	@Import(name = "stsv_ft_slot_advance_y")
	static native int ftSlotAdvanceY (long slot);

	@Import(name = "stsv_ft_slot_format")
	static native int ftSlotFormat (long slot);

	@Import(name = "stsv_ft_slot_bitmap")
	static native long ftSlotBitmap (long slot);

	@Import(name = "stsv_ft_slot_bitmap_left")
	static native int ftSlotBitmapLeft (long slot);

	@Import(name = "stsv_ft_slot_bitmap_top")
	static native int ftSlotBitmapTop (long slot);

	@Import(name = "stsv_ft_render_glyph")
	static native int ftRenderGlyph (long slot, int renderMode);

	@Import(name = "stsv_ft_get_glyph")
	static native long ftGetGlyph (long glyphSlot);

	public static class Glyph extends Pointer implements Disposable {
		private boolean rendered;

		Glyph (long address) {
			super(address);
		}

		@Override
		public void dispose () {
			ftDoneGlyph(address);
		}

		public void strokeBorder (Stroker stroker, boolean inside) {
			address = ftStrokeBorder(address, stroker.address, inside ? 1 : 0);
		}

		public void toBitmap (int renderMode) {
			long bitmap = ftGlyphToBitmap(address, renderMode);
			if (bitmap == 0) throw new GdxRuntimeException("Couldn't render glyph, FreeType error code: " + getLastErrorCode());
			address = bitmap;
			rendered = true;
		}

		public Bitmap getBitmap () {
			if (!rendered) {
				throw new GdxRuntimeException("Glyph is not yet rendered");
			}
			return new Bitmap(ftGlyphBitmap(address));
		}

		public int getLeft () {
			if (!rendered) {
				throw new GdxRuntimeException("Glyph is not yet rendered");
			}
			return ftGlyphLeft(address);
		}

		public int getTop () {
			if (!rendered) {
				throw new GdxRuntimeException("Glyph is not yet rendered");
			}
			return ftGlyphTop(address);
		}
	}

	@Import(name = "stsv_ft_done_glyph")
	static native void ftDoneGlyph (long glyph);

	@Import(name = "stsv_ft_stroke_border")
	static native long ftStrokeBorder (long glyph, long stroker, int inside);

	@Import(name = "stsv_ft_glyph_to_bitmap")
	static native long ftGlyphToBitmap (long glyph, int renderMode);

	@Import(name = "stsv_ft_glyph_bitmap")
	static native long ftGlyphBitmap (long glyph);

	@Import(name = "stsv_ft_glyph_left")
	static native int ftGlyphLeft (long glyph);

	@Import(name = "stsv_ft_glyph_top")
	static native int ftGlyphTop (long glyph);

	public static class Bitmap extends Pointer {
		Bitmap (long address) {
			super(address);
		}

		public int getRows () {
			return ftBitmapRows(address);
		}

		public int getWidth () {
			return ftBitmapWidth(address);
		}

		public int getPitch () {
			return ftBitmapPitch(address);
		}

		public ByteBuffer getBuffer () {
			int rows = getRows();
			if (rows == 0)
				// Issue #768 - FreeType sets FT_Bitmap::buffer to NULL when the
				// bitmap is empty (e.g. for ' '), so return a dummy buffer.
				return BufferUtils.newByteBuffer(1);
			// The JNI original wrapped the native memory with NewDirectByteBuffer;
			// here we copy it into a direct Java buffer instead. All gdx callers
			// (getPixmap, FreeTypeFontGenerator's bitmapped path) only read it.
			int size = rows * Math.abs(getPitch());
			ByteBuffer buffer = BufferUtils.newByteBuffer(size);
			ftBitmapCopy(address, Buffers.addr(buffer), size);
			return buffer;
		}

		public Pixmap getPixmap (Format format, Color color, float gamma) {
			int width = getWidth(), rows = getRows();
			ByteBuffer src = getBuffer();
			Pixmap pixmap;
			int pixelMode = getPixelMode();
			int rowBytes = Math.abs(getPitch()); // We currently ignore negative pitch.
			// STSVITA: the Alpha (1-byte/pixel) fast path is disabled. This
			// backend's Gdx2DPixmap is RGBA8888-only, so a Pixmap requested as
			// Format.Alpha is still 4 bytes/pixel; copying FreeType's 1-byte
			// coverage into it put the data in the wrong channels and drew a
			// colored box behind every glyph. The RGBA path below expands
			// coverage into a proper white+alpha glyph at the same memory cost.
			if (false) {
				pixmap = new Pixmap(width, rows, Format.Alpha);
				BufferUtils.copy(src, pixmap.getPixels(), pixmap.getPixels().capacity());
			} else {
				pixmap = new Pixmap(width, rows, Format.RGBA8888);
				int rgba = Color.rgba8888(color);
				byte[] srcRow = new byte[rowBytes];
				int[] dstRow = new int[width];
				IntBuffer dst = pixmap.getPixels().asIntBuffer();
				if (pixelMode == FT_PIXEL_MODE_MONO) {
					// Use the specified color for each set bit.
					for (int y = 0; y < rows; y++) {
						src.get(srcRow);
						for (int i = 0, x = 0; x < width; i++, x += 8) {
							byte b = srcRow[i];
							for (int ii = 0, n = Math.min(8, width - x); ii < n; ii++) {
								if ((b & (1 << (7 - ii))) != 0)
									dstRow[x + ii] = rgba;
								else
									dstRow[x + ii] = 0;
							}
						}
						dst.put(dstRow);
					}
				} else {
					// Use the specified color for RGB, blend the FreeType bitmap with alpha.
					int rgb = rgba & 0xffffff00;
					int a = rgba & 0xff;
					for (int y = 0; y < rows; y++) {
						src.get(srcRow);
						for (int x = 0; x < width; x++) {
							float alpha = (srcRow[x] & 0xff) / 255f;
							alpha = (float)Math.pow(alpha, gamma); // Inverse gamma.
							dstRow[x] = rgb | (int)(a * alpha);
						}
						dst.put(dstRow);
					}
				}
			}

			Pixmap converted = pixmap;
			if (format != pixmap.getFormat()) {
				converted = new Pixmap(pixmap.getWidth(), pixmap.getHeight(), format);
				Blending blending = Pixmap.getBlending();
				Pixmap.setBlending(Blending.None);
				converted.drawPixmap(pixmap, 0, 0);
				Pixmap.setBlending(blending);
				pixmap.dispose();
			}
			return converted;
		}

		public int getNumGray () {
			return ftBitmapNumGray(address);
		}

		public int getPixelMode () {
			return ftBitmapPixelMode(address);
		}
	}

	@Import(name = "stsv_ft_bitmap_rows")
	static native int ftBitmapRows (long bitmap);

	@Import(name = "stsv_ft_bitmap_width")
	static native int ftBitmapWidth (long bitmap);

	@Import(name = "stsv_ft_bitmap_pitch")
	static native int ftBitmapPitch (long bitmap);

	@Import(name = "stsv_ft_bitmap_copy")
	static native void ftBitmapCopy (long bitmap, Address dst, int size);

	@Import(name = "stsv_ft_bitmap_num_grays")
	static native int ftBitmapNumGray (long bitmap);

	@Import(name = "stsv_ft_bitmap_pixel_mode")
	static native int ftBitmapPixelMode (long bitmap);

	public static class GlyphMetrics extends Pointer {
		GlyphMetrics (long address) {
			super(address);
		}

		public int getWidth () {
			return ftGmWidth(address);
		}

		public int getHeight () {
			return ftGmHeight(address);
		}

		public int getHoriBearingX () {
			return ftGmHoriBearingX(address);
		}

		public int getHoriBearingY () {
			return ftGmHoriBearingY(address);
		}

		public int getHoriAdvance () {
			return ftGmHoriAdvance(address);
		}

		public int getVertBearingX () {
			return ftGmVertBearingX(address);
		}

		public int getVertBearingY () {
			return ftGmVertBearingY(address);
		}

		public int getVertAdvance () {
			return ftGmVertAdvance(address);
		}
	}

	@Import(name = "stsv_ft_gm_width")
	static native int ftGmWidth (long metrics);

	@Import(name = "stsv_ft_gm_height")
	static native int ftGmHeight (long metrics);

	@Import(name = "stsv_ft_gm_hori_bearing_x")
	static native int ftGmHoriBearingX (long metrics);

	@Import(name = "stsv_ft_gm_hori_bearing_y")
	static native int ftGmHoriBearingY (long metrics);

	@Import(name = "stsv_ft_gm_hori_advance")
	static native int ftGmHoriAdvance (long metrics);

	@Import(name = "stsv_ft_gm_vert_bearing_x")
	static native int ftGmVertBearingX (long metrics);

	@Import(name = "stsv_ft_gm_vert_bearing_y")
	static native int ftGmVertBearingY (long metrics);

	@Import(name = "stsv_ft_gm_vert_advance")
	static native int ftGmVertAdvance (long metrics);

	public static class Stroker extends Pointer implements Disposable {
		Stroker (long address) {
			super(address);
		}

		public void set (int radius, int lineCap, int lineJoin, int miterLimit) {
			ftStrokerSet(address, radius, lineCap, lineJoin, miterLimit);
		}

		@Override
		public void dispose () {
			ftStrokerDone(address);
		}
	}

	@Import(name = "stsv_ft_stroker_set")
	static native void ftStrokerSet (long stroker, int radius, int lineCap, int lineJoin, int miterLimit);

	@Import(name = "stsv_ft_stroker_done")
	static native void ftStrokerDone (long stroker);

	public static int FT_PIXEL_MODE_NONE = 0;
	public static int FT_PIXEL_MODE_MONO = 1;
	public static int FT_PIXEL_MODE_GRAY = 2;
	public static int FT_PIXEL_MODE_GRAY2 = 3;
	public static int FT_PIXEL_MODE_GRAY4 = 4;
	public static int FT_PIXEL_MODE_LCD = 5;
	public static int FT_PIXEL_MODE_LCD_V = 6;

	private static int encode (char a, char b, char c, char d) {
		return (a << 24) | (b << 16) | (c << 8) | d;
	}

	public static int FT_ENCODING_NONE = 0;
	public static int FT_ENCODING_MS_SYMBOL = encode('s', 'y', 'm', 'b');
	public static int FT_ENCODING_UNICODE = encode('u', 'n', 'i', 'c');
	public static int FT_ENCODING_SJIS = encode('s', 'j', 'i', 's');
	public static int FT_ENCODING_GB2312 = encode('g', 'b', ' ', ' ');
	public static int FT_ENCODING_BIG5 = encode('b', 'i', 'g', '5');
	public static int FT_ENCODING_WANSUNG = encode('w', 'a', 'n', 's');
	public static int FT_ENCODING_JOHAB = encode('j', 'o', 'h', 'a');
	public static int FT_ENCODING_ADOBE_STANDARD = encode('A', 'D', 'O', 'B');
	public static int FT_ENCODING_ADOBE_EXPERT = encode('A', 'D', 'B', 'E');
	public static int FT_ENCODING_ADOBE_CUSTOM = encode('A', 'D', 'B', 'C');
	public static int FT_ENCODING_ADOBE_LATIN_1 = encode('l', 'a', 't', '1');
	public static int FT_ENCODING_OLD_LATIN_2 = encode('l', 'a', 't', '2');
	public static int FT_ENCODING_APPLE_ROMAN = encode('a', 'r', 'm', 'n');

	public static int FT_FACE_FLAG_SCALABLE          = ( 1 <<  0 );
	public static int FT_FACE_FLAG_FIXED_SIZES       = ( 1 <<  1 );
	public static int FT_FACE_FLAG_FIXED_WIDTH       = ( 1 <<  2 );
	public static int FT_FACE_FLAG_SFNT              = ( 1 <<  3 );
	public static int FT_FACE_FLAG_HORIZONTAL        = ( 1 <<  4 );
	public static int FT_FACE_FLAG_VERTICAL          = ( 1 <<  5 );
	public static int FT_FACE_FLAG_KERNING           = ( 1 <<  6 );
	public static int FT_FACE_FLAG_FAST_GLYPHS       = ( 1 <<  7 );
	public static int FT_FACE_FLAG_MULTIPLE_MASTERS  = ( 1 <<  8 );
	public static int FT_FACE_FLAG_GLYPH_NAMES       = ( 1 <<  9 );
	public static int FT_FACE_FLAG_EXTERNAL_STREAM   = ( 1 << 10 );
	public static int FT_FACE_FLAG_HINTER            = ( 1 << 11 );
	public static int FT_FACE_FLAG_CID_KEYED         = ( 1 << 12 );
	public static int FT_FACE_FLAG_TRICKY            = ( 1 << 13 );

	public static int FT_STYLE_FLAG_ITALIC = ( 1 << 0 );
	public static int FT_STYLE_FLAG_BOLD   = ( 1 << 1 );

	public static int FT_LOAD_DEFAULT                      = 0x0;
	public static int FT_LOAD_NO_SCALE                     = 0x1;
	public static int FT_LOAD_NO_HINTING                   = 0x2;
	public static int FT_LOAD_RENDER                       = 0x4;
	public static int FT_LOAD_NO_BITMAP                    = 0x8;
	public static int FT_LOAD_VERTICAL_LAYOUT              = 0x10;
	public static int FT_LOAD_FORCE_AUTOHINT               = 0x20;
	public static int FT_LOAD_CROP_BITMAP                  = 0x40;
	public static int FT_LOAD_PEDANTIC                     = 0x80;
	public static int FT_LOAD_IGNORE_GLOBAL_ADVANCE_WIDTH  = 0x200;
	public static int FT_LOAD_NO_RECURSE                   = 0x400;
	public static int FT_LOAD_IGNORE_TRANSFORM             = 0x800;
	public static int FT_LOAD_MONOCHROME                   = 0x1000;
	public static int FT_LOAD_LINEAR_DESIGN                = 0x2000;
	public static int FT_LOAD_NO_AUTOHINT                  = 0x8000;

	public static int FT_LOAD_TARGET_NORMAL                = 0x0;
	public static int FT_LOAD_TARGET_LIGHT                 = 0x10000;
	public static int FT_LOAD_TARGET_MONO                  = 0x20000;
	public static int FT_LOAD_TARGET_LCD                   = 0x30000;
	public static int FT_LOAD_TARGET_LCD_V                 = 0x40000;

	public static int FT_RENDER_MODE_NORMAL = 0;
	public static int FT_RENDER_MODE_LIGHT = 1;
	public static int FT_RENDER_MODE_MONO = 2;
	public static int FT_RENDER_MODE_LCD = 3;
	public static int FT_RENDER_MODE_LCD_V = 4;
	public static int FT_RENDER_MODE_MAX = 5;

	public static int FT_KERNING_DEFAULT = 0;
	public static int FT_KERNING_UNFITTED = 1;
	public static int FT_KERNING_UNSCALED = 2;

	public static int FT_STROKER_LINECAP_BUTT = 0;
	public static int FT_STROKER_LINECAP_ROUND = 1;
	public static int FT_STROKER_LINECAP_SQUARE = 2;

	public static int FT_STROKER_LINEJOIN_ROUND          = 0;
	public static int FT_STROKER_LINEJOIN_BEVEL          = 1;
	public static int FT_STROKER_LINEJOIN_MITER_VARIABLE = 2;
	public static int FT_STROKER_LINEJOIN_MITER          = FT_STROKER_LINEJOIN_MITER_VARIABLE;
	public static int FT_STROKER_LINEJOIN_MITER_FIXED    = 3;

	public static Library initFreeType () {
		// No SharedLibraryLoader here: FreeType is statically linked into the
		// TeaVM-generated binary.
		long address = initFreeTypeJni();
		if (address == 0) throw new GdxRuntimeException("Couldn't initialize FreeType library, FreeType error code: " + getLastErrorCode());
		else return new Library(address);
	}

	@Import(name = "stsv_ft_init")
	private static native long initFreeTypeJni ();

	public static int toInt (int value) {
		return ((value + 63) & -64) >> 6;
	}
}
