package com.badlogic.gdx.graphics.g2d;

import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.stsvita.backend.Buffers;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.teavm.interop.Address;
import org.teavm.interop.Import;
import org.teavm.interop.c.Include;

/**
 * Shadow of libGDX's gdx2d JNI wrapper: an all-Java software pixmap over a
 * direct ByteBuffer, with PNG/JPG decoding through stb_image glue
 * (native/src/glue/misc_glue.c).
 *
 * Storage is always RGBA8888 regardless of the requested format (decoded
 * images are expanded by stb, and every consumer in the game uploads via
 * getGLFormat(), which we answer consistently). Blend/scale semantics follow
 * gdx2d.c: SRC_OVER alpha blending, nearest-neighbour scaling.
 */
@Include("stsv_misc.h")
public class Gdx2DPixmap implements Disposable {

    public static final int GDX2D_FORMAT_ALPHA = 1;
    public static final int GDX2D_FORMAT_LUMINANCE_ALPHA = 2;
    public static final int GDX2D_FORMAT_RGB888 = 3;
    public static final int GDX2D_FORMAT_RGBA8888 = 4;
    public static final int GDX2D_FORMAT_RGB565 = 5;
    public static final int GDX2D_FORMAT_RGBA4444 = 6;

    public static final int GDX2D_SCALE_NEAREST = 0;
    public static final int GDX2D_SCALE_LINEAR = 1;

    public static final int GDX2D_BLEND_NONE = 0;
    public static final int GDX2D_BLEND_SRC_OVER = 1;

    /* GL constants, to avoid a dependency on GL20 here. */
    private static final int GL_RGBA = 0x1908;
    private static final int GL_UNSIGNED_BYTE = 0x1401;
    private static final int GL_COMPRESSED_RGB_S3TC_DXT1_EXT = 0x83F0;
    private static final int GL_COMPRESSED_RGBA_S3TC_DXT5_EXT = 0x83F3;

    private static int blend = GDX2D_BLEND_SRC_OVER;
    private static int scale = GDX2D_SCALE_LINEAR;
    private static String failureReason = "";
    private static long liveBytes;
    private static long peakLiveBytes;
    private static long totalAllocatedBytes;
    private static long totalDisposedBytes;
    private static int livePixmaps;
    private static int peakLivePixmaps;
    private static int totalPixmaps;
    private static int disposedPixmaps;
    private static int largePixmaps;
    private static int compressedPixmaps;

    long basePtr;
    int width;
    int height;
    int format;
    int glInternalFormat = GL_RGBA;
    int glFormat = GL_RGBA;
    int glType = GL_UNSIGNED_BYTE;
    ByteBuffer pixelPtr;
    int pixelBytes;
    boolean disposed;
    long[] nativeData = new long[4];

    public Gdx2DPixmap(byte[] encodedData, int offset, int len, int requestedFormat)
            throws IOException {
        if (isDds(encodedData, offset, len)) {
            loadDds(encodedData, offset, len);
            return;
        }
        int[] dims = new int[2];
        // stb decodes into malloc'd memory; copy into a GC-managed direct
        // buffer and free the stb allocation immediately.
        Address decoded = imgLoad(Address.ofData(encodedData).add(offset), len,
                Address.ofData(dims));
        if (decoded.toInt() == 0) {
            // Decode failed (typically a transient OOM under Vita's tight
            // texture budget). Rather than abort the whole game, fall back to a
            // blank transparent pixmap at the PNG's true dimensions (from the
            // IHDR) so atlas region coordinates stay valid — the art is just
            // missing, not garbled, and startup can continue to the menu.
            failureReason = "stb_image failed to decode " + len + " bytes";
            int w = pngUint(encodedData, offset + 16);
            int h = pngUint(encodedData, offset + 20);
            if (w <= 0 || w > 8192 || h <= 0 || h > 8192) {
                w = 4;
                h = 4;
            }
            width = w;
            height = h;
            format = GDX2D_FORMAT_RGBA8888;
            allocatePixels(width * height * 4);
            return;
        }
        width = dims[0];
        height = dims[1];
        format = GDX2D_FORMAT_RGBA8888;
        allocatePixels(width * height * 4);
        memcpy(Buffers.addr(pixelPtr, 0), decoded, width * height * 4);
        imgFree(decoded);
    }

    public Gdx2DPixmap(InputStream in, int requestedFormat) throws IOException {
        this(readAll(in), requestedFormat);
    }

    private Gdx2DPixmap(byte[] encodedData, int requestedFormat) throws IOException {
        this(encodedData, 0, encodedData.length, requestedFormat);
    }

    public Gdx2DPixmap(int width, int height, int format) throws GdxRuntimeException {
        this.width = width;
        this.height = height;
        this.format = GDX2D_FORMAT_RGBA8888;
        allocatePixels(width * height * 4);
    }

    public Gdx2DPixmap(ByteBuffer pixelBuffer, long[] nativeData) {
        throw new UnsupportedOperationException(
                "native-wrapping ctor unused on this backend");
    }

    /** Big-endian uint32 at {@code p} (PNG IHDR width/height); 0 if out of range. */
    private static int pngUint(byte[] d, int p) {
        if (p < 0 || p + 4 > d.length) {
            return 0;
        }
        return ((d[p] & 0xff) << 24) | ((d[p + 1] & 0xff) << 16)
                | ((d[p + 2] & 0xff) << 8) | (d[p + 3] & 0xff);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    public static Gdx2DPixmap newPixmap(InputStream in, int requestedFormat) {
        try {
            return new Gdx2DPixmap(in, requestedFormat);
        } catch (IOException e) {
            return null;
        }
    }

    public static Gdx2DPixmap newPixmap(int width, int height, int format) {
        try {
            return new Gdx2DPixmap(width, height, format);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        if (pixelPtr != null) {
            pixelPtr = null;
            liveBytes -= pixelBytes;
            livePixmaps--;
            totalDisposedBytes += pixelBytes;
            disposedPixmaps++;
        }
    }

    private void allocatePixels(int bytes) {
        pixelBytes = bytes;
        pixelPtr = ByteBuffer.allocate(bytes).order(ByteOrder.BIG_ENDIAN);
        liveBytes += bytes;
        totalAllocatedBytes += bytes;
        livePixmaps++;
        totalPixmaps++;
        if (liveBytes > peakLiveBytes) {
            peakLiveBytes = liveBytes;
        }
        if (livePixmaps > peakLivePixmaps) {
            peakLivePixmaps = livePixmaps;
        }
        if (bytes >= 1024 * 1024) {
            largePixmaps++;
        }
    }

    private void allocateCompressed(int bytes) {
        pixelBytes = bytes;
        pixelPtr = ByteBuffer.allocate(bytes).order(ByteOrder.BIG_ENDIAN);
        liveBytes += bytes;
        totalAllocatedBytes += bytes;
        livePixmaps++;
        totalPixmaps++;
        compressedPixmaps++;
        if (liveBytes > peakLiveBytes) {
            peakLiveBytes = liveBytes;
        }
        if (livePixmaps > peakLivePixmaps) {
            peakLivePixmaps = livePixmaps;
        }
        if (bytes >= 1024 * 1024) {
            largePixmaps++;
        }
    }

    private void loadDds(byte[] data, int offset, int len) throws IOException {
        if (len < 128) {
            throw new IOException("DDS too short");
        }
        int headerSize = le32(data, offset + 4);
        int pfSize = le32(data, offset + 76);
        int fourcc = le32(data, offset + 84);
        if (headerSize != 124 || pfSize != 32) {
            throw new IOException("unsupported DDS header");
        }
        if (fourcc == fourcc("DXT1")) {
            glInternalFormat = GL_COMPRESSED_RGB_S3TC_DXT1_EXT;
        } else if (fourcc == fourcc("DXT5")) {
            glInternalFormat = GL_COMPRESSED_RGBA_S3TC_DXT5_EXT;
        } else {
            throw new IOException("unsupported DDS fourcc");
        }
        width = le32(data, offset + 16);
        height = le32(data, offset + 12);
        if (width <= 0 || height <= 0) {
            throw new IOException("invalid DDS dimensions");
        }
        format = GDX2D_FORMAT_RGBA8888;
        glFormat = glInternalFormat;
        glType = GL_UNSIGNED_BYTE;
        int bytes = len - 128;
        allocateCompressed(bytes);
        System.arraycopy(data, offset + 128, pixelPtr.array(), 0, bytes);
    }

    private static boolean isDds(byte[] data, int offset, int len) {
        return len >= 128
                && data[offset] == 'D'
                && data[offset + 1] == 'D'
                && data[offset + 2] == 'S'
                && data[offset + 3] == ' ';
    }

    private static int le32(byte[] data, int p) {
        return (data[p] & 0xff)
                | ((data[p + 1] & 0xff) << 8)
                | ((data[p + 2] & 0xff) << 16)
                | ((data[p + 3] & 0xff) << 24);
    }

    private static int fourcc(String value) {
        return value.charAt(0)
                | (value.charAt(1) << 8)
                | (value.charAt(2) << 16)
                | (value.charAt(3) << 24);
    }

    /* ---- pixel ops (gdx2d.c semantics, RGBA8888 only) ---- */

    public void clear(int color) {
        byte r = (byte) (color >>> 24);
        byte g = (byte) (color >>> 16);
        byte b = (byte) (color >>> 8);
        byte a = (byte) color;
        for (int i = 0, n = width * height * 4; i < n; i += 4) {
            pixelPtr.put(i, r);
            pixelPtr.put(i + 1, g);
            pixelPtr.put(i + 2, b);
            pixelPtr.put(i + 3, a);
        }
    }

    public void setPixel(int x, int y, int color) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return;
        }
        putBlended(idx(x, y), color);
    }

    public int getPixel(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return 0;
        }
        int i = idx(x, y);
        return (pixelPtr.get(i) & 0xff) << 24
                | (pixelPtr.get(i + 1) & 0xff) << 16
                | (pixelPtr.get(i + 2) & 0xff) << 8
                | (pixelPtr.get(i + 3) & 0xff);
    }

    public void drawLine(int x, int y, int x2, int y2, int color) {
        int dx = Math.abs(x2 - x);
        int dy = Math.abs(y2 - y);
        int sx = x < x2 ? 1 : -1;
        int sy = y < y2 ? 1 : -1;
        int err = dx - dy;
        while (true) {
            setPixel(x, y, color);
            if (x == x2 && y == y2) {
                break;
            }
            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                y += sy;
            }
        }
    }

    public void drawRect(int x, int y, int w, int h, int color) {
        drawLine(x, y, x + w - 1, y, color);
        drawLine(x, y + h - 1, x + w - 1, y + h - 1, color);
        drawLine(x, y, x, y + h - 1, color);
        drawLine(x + w - 1, y, x + w - 1, y + h - 1, color);
    }

    public void fillRect(int x, int y, int w, int h, int color) {
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(width, x + w);
        int y1 = Math.min(height, y + h);
        for (int py = y0; py < y1; py++) {
            for (int px = x0; px < x1; px++) {
                putBlended(idx(px, py), color);
            }
        }
    }

    public void drawCircle(int x, int y, int radius, int color) {
        int px = radius;
        int py = 0;
        int err = 1 - px;
        while (px >= py) {
            circlePoints(x, y, px, py, color, false);
            py++;
            if (err < 0) {
                err += 2 * py + 1;
            } else {
                px--;
                err += 2 * (py - px) + 1;
            }
        }
    }

    public void fillCircle(int x, int y, int radius, int color) {
        int px = radius;
        int py = 0;
        int err = 1 - px;
        while (px >= py) {
            circlePoints(x, y, px, py, color, true);
            py++;
            if (err < 0) {
                err += 2 * py + 1;
            } else {
                px--;
                err += 2 * (py - px) + 1;
            }
        }
    }

    private void circlePoints(int cx, int cy, int px, int py, int color, boolean fill) {
        if (fill) {
            hline(cx - px, cx + px, cy + py, color);
            hline(cx - px, cx + px, cy - py, color);
            hline(cx - py, cx + py, cy + px, color);
            hline(cx - py, cx + py, cy - px, color);
        } else {
            setPixel(cx + px, cy + py, color);
            setPixel(cx - px, cy + py, color);
            setPixel(cx + px, cy - py, color);
            setPixel(cx - px, cy - py, color);
            setPixel(cx + py, cy + px, color);
            setPixel(cx - py, cy + px, color);
            setPixel(cx + py, cy - px, color);
            setPixel(cx - py, cy - px, color);
        }
    }

    private void hline(int x0, int x1, int y, int color) {
        if (y < 0 || y >= height) {
            return;
        }
        x0 = Math.max(0, x0);
        x1 = Math.min(width - 1, x1);
        for (int x = x0; x <= x1; x++) {
            putBlended(idx(x, y), color);
        }
    }

    public void fillTriangle(int x1, int y1, int x2, int y2, int x3, int y3, int color) {
        int minY = Math.max(0, Math.min(y1, Math.min(y2, y3)));
        int maxY = Math.min(height - 1, Math.max(y1, Math.max(y2, y3)));
        for (int y = minY; y <= maxY; y++) {
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int[][] edges = {{x1, y1, x2, y2}, {x2, y2, x3, y3}, {x3, y3, x1, y1}};
            for (int[] e : edges) {
                if ((e[1] <= y && e[3] >= y) || (e[3] <= y && e[1] >= y)) {
                    if (e[1] == e[3]) {
                        minX = Math.min(minX, Math.min(e[0], e[2]));
                        maxX = Math.max(maxX, Math.max(e[0], e[2]));
                    } else {
                        int xi = e[0] + (y - e[1]) * (e[2] - e[0]) / (e[3] - e[1]);
                        minX = Math.min(minX, xi);
                        maxX = Math.max(maxX, xi);
                    }
                }
            }
            if (minX <= maxX) {
                hline(minX, maxX, y, color);
            }
        }
    }

    public void drawPixmap(Gdx2DPixmap src, int srcX, int srcY, int dstX, int dstY,
                           int w, int h) {
        drawPixmap(src, srcX, srcY, w, h, dstX, dstY, w, h);
    }

    public void drawPixmap(Gdx2DPixmap src, int srcX, int srcY, int srcW, int srcH,
                           int dstX, int dstY, int dstW, int dstH) {
        for (int y = 0; y < dstH; y++) {
            int sy = srcH == dstH ? srcY + y : srcY + y * srcH / dstH;
            if (sy < 0 || sy >= src.height) {
                continue;
            }
            int ty = dstY + y;
            if (ty < 0 || ty >= height) {
                continue;
            }
            for (int x = 0; x < dstW; x++) {
                int sx = srcW == dstW ? srcX + x : srcX + x * srcW / dstW;
                if (sx < 0 || sx >= src.width) {
                    continue;
                }
                int tx = dstX + x;
                if (tx < 0 || tx >= width) {
                    continue;
                }
                int si = src.idx(sx, sy);
                int color = (src.pixelPtr.get(si) & 0xff) << 24
                        | (src.pixelPtr.get(si + 1) & 0xff) << 16
                        | (src.pixelPtr.get(si + 2) & 0xff) << 8
                        | (src.pixelPtr.get(si + 3) & 0xff);
                putBlended(idx(tx, ty), color);
            }
        }
    }

    private int idx(int x, int y) {
        return (y * width + x) * 4;
    }

    private void putBlended(int i, int color) {
        int sr = color >>> 24;
        int sg = (color >>> 16) & 0xff;
        int sb = (color >>> 8) & 0xff;
        int sa = color & 0xff;
        if (blend == GDX2D_BLEND_SRC_OVER && sa != 255) {
            if (sa == 0) {
                return;
            }
            int dr = pixelPtr.get(i) & 0xff;
            int dg = pixelPtr.get(i + 1) & 0xff;
            int db = pixelPtr.get(i + 2) & 0xff;
            int da = pixelPtr.get(i + 3) & 0xff;
            sr = (sr * sa + dr * (255 - sa)) / 255;
            sg = (sg * sa + dg * (255 - sa)) / 255;
            sb = (sb * sa + db * (255 - sa)) / 255;
            sa = sa + da * (255 - sa) / 255;
        }
        pixelPtr.put(i, (byte) sr);
        pixelPtr.put(i + 1, (byte) sg);
        pixelPtr.put(i + 2, (byte) sb);
        pixelPtr.put(i + 3, (byte) sa);
    }

    /* ---- accessors ---- */

    public ByteBuffer getPixels() {
        return pixelPtr;
    }

    public int getHeight() {
        return height;
    }

    public int getWidth() {
        return width;
    }

    public int getFormat() {
        return format;
    }

    public int getGLInternalFormat() {
        return glInternalFormat;
    }

    public int getGLFormat() {
        return glFormat;
    }

    public int getGLType() {
        return glType;
    }

    public String getFormatString() {
        return "rgba8888";
    }

    public static int toGlFormat(int format) {
        return GL_RGBA;
    }

    public static int toGlType(int format) {
        return GL_UNSIGNED_BYTE;
    }

    public static void setBlend(int blend) {
        Gdx2DPixmap.blend = blend;
    }

    public static void setScale(int scale) {
        Gdx2DPixmap.scale = scale;
    }

    public static String getFailureReason() {
        return failureReason;
    }

    /* ---- native glue (misc_glue.c) ---- */

    @Import(name = "stsv_img_load")
    private static native Address imgLoad(Address data, int len, Address outDims);

    @Import(name = "stsv_img_free")
    private static native void imgFree(Address pixels);

    @Import(name = "stsv_memcpy")
    static native void memcpy(Address dst, Address src, int bytes);
}
