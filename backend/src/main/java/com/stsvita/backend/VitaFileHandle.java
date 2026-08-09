package com.stsvita.backend;

import com.badlogic.gdx.Files;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.GdxRuntimeException;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * FileHandle whose path is already resolved to a Vita mount
 * (app0:/assets/... or ux0:data/stsvita/...). Core file operations are direct
 * java.io calls so libGDX's FileType switch helpers stay off the Vita path.
 */
public class VitaFileHandle extends FileHandle {

    // Per-frame IO attribution: the [io] >=100ms line missed
    // ~36s of the 50s save-slot stall (long tail of sub-100ms reads/stats).
    // Every read and every stat-ish call accumulates here; the [hitch] logger
    // drains once per frame. Packed like the native accumulators:
    // ms*1000 + count (count capped 999).
    private static long ioUs;
    private static int ioCount;
    private static long statUs;
    private static int statCount;
    private static final Map<String, byte[]> doomedDdsHeaders = new HashMap<>();

    // Read-back cache for image payloads. StS loads several
    // textures with new-Texture-per-view + dispose-on-close semantics
    // (SingleRelicViewPopup's relicFrame*.png, single-card portraits, monster
    // atlases re-read after every "Disposed Texture"). On desktop that's a
    // sub-ms page-cache hit; on the Vita each is a 100-250ms SD read on the
    // render thread — the measured cause of relic-view/deck-browse frame
    // drops and room-load variance. Images on device never change, so serve
    // repeats from RAM: LRU by access order, byte budget sized well inside
    // the 112MB Java heap (~16-26MB live), per-file cap so one-time boot
    // atlases (2-4MB) don't churn the hot set. Callers treat the returned
    // array as read-only decode input; the cache shares the array.
    private static final int IMAGE_CACHE_BUDGET = 20 * 1024 * 1024;
    private static final int IMAGE_CACHE_FILE_MAX = 1024 * 1024 + 128;
    private static int imageCacheBytes;
    private static final java.util.LinkedHashMap<String, byte[]> imageCache =
            new java.util.LinkedHashMap<String, byte[]>(64, 0.75f, true);

    public static long drainIoStats() {
        long packed = (ioUs / 1000L) * 1000L + Math.min(ioCount, 999);
        ioUs = 0;
        ioCount = 0;
        return packed;
    }

    public static long drainStatStats() {
        long packed = (statUs / 1000L) * 1000L + Math.min(statCount, 999);
        statUs = 0;
        statCount = 0;
        return packed;
    }

    public VitaFileHandle(String resolvedPath, Files.FileType type) {
        super(redirect(resolvedPath), type);
    }

    /**
     * The game's controller-glyph loader only knows the xbox360/xboxOne art
     * directories (ImageMaster.loadControllerImages has no PS branch), but
     * the PS4 glyph set ships in the jar with the same file names (plus
     * share.png). Detour those loads so the Vita shows PlayStation buttons.
     */
    private static String redirect(String p) {
        int i = p.indexOf("ui/controller/xbox");
        if (i < 0) {
            return p;
        }
        int slash = p.indexOf('/', i + "ui/controller/".length());
        if (slash < 0) {
            return p;
        }
        return p.substring(0, i) + "ui/controller/ps4" + p.substring(slash);
    }

    @Override
    public File file() {
        return new File(path());
    }

    @Override
    public InputStream read() {
        InputStream dds = openDdsSibling();
        if (dds != null) {
            return dds;
        }
        try {
            return new FileInputStream(path());
        } catch (FileNotFoundException e) {
            throw new GdxRuntimeException("File not found: " + this, e);
        }
    }

    @Override
    public BufferedInputStream read(int bufferSize) {
        return new BufferedInputStream(read(), bufferSize);
    }

    @Override
    public byte[] readBytes() {
        String p = path();
        boolean image = p.endsWith(".png") || p.endsWith(".jpg") || p.endsWith(".dds");
        if (image) {
            // Loads are synchronous (read -> decode -> upload on one thread),
            // so this names the texture in the [DIAG dxt] SKIP/SPILL lines.
            VitaNatives.texNotePath(p);
            byte[] headerOnly = skipDoomedDds();
            if (headerOnly != null) {
                return headerOnly;
            }
            byte[] cached = imageCache.get(p);
            if (cached != null) {
                return cached;
            }
        }
        // Freeze diagnostic: synchronous multi-MB SD reads
        // (music, event art) stall the frame loop under cooperative fibers.
        long t0 = System.nanoTime();
        byte[] bytes = readAll(read(), (int) length());
        long us = (System.nanoTime() - t0) / 1000L;
        ioUs += us;
        ioCount++;
        if (us >= 100000L) {
            System.out.println("[io] read " + path() + " " + bytes.length
                    + "B in " + (us / 1000L) + "ms");
        }
        if (image && bytes.length <= IMAGE_CACHE_FILE_MAX) {
            imageCacheBytes += bytes.length;
            imageCache.put(p, bytes);
            java.util.Iterator<Map.Entry<String, byte[]>> it =
                    imageCache.entrySet().iterator();
            while (imageCacheBytes > IMAGE_CACHE_BUDGET && it.hasNext()) {
                imageCacheBytes -= it.next().getValue().length;
                it.remove();
            }
        }
        return bytes;
    }

    /**
     * Don't load what the guard will drop: when no vitaGL
     * pool (nor the bounded spill) can admit a texture of this size, the
     * upload path blanks it to 4x4 AFTER the multi-second SD read — pure
     * waste (17MB of char-select shoulders alone). Ask the guard first; if
     * doomed, return just the 128-byte DDS header. Its dimensions keep the
     * game's layout/UV math intact, and stsv_tex_image_compressed treats the
     * size mismatch as the usual 4x4 blank. Host always fits (no-op there).
     */
    private byte[] skipDoomedDds() {
        File f = replacementFile();
        if (f == null) {
            if (!path().endsWith(".dds")) {
                return null;
            }
            f = file();
        } else if (!f.getName().endsWith(".dds")) {
            return null;
        }
        long payload = f.length() - 128L;
        if (payload < 128L * 1024L) {
            return null; // small reads are cheap; let the guard decide later
        }
        if (VitaNatives.texWouldFit((int) payload) != 0) {
            return null;
        }
        String cacheKey = f.getPath();
        byte[] cached = doomedDdsHeaders.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        try {
            InputStream in = new FileInputStream(f);
            try {
                byte[] header = new byte[128];
                int total = 0;
                while (total < 128) {
                    int n = in.read(header, total, 128 - total);
                    if (n < 0) {
                        break;
                    }
                    total += n;
                }
                if (total < 128) {
                    return null;
                }
                doomedDdsHeaders.put(cacheKey, header);
                System.out.println("[io] skip-drop " + path() + " " + payload + "B");
                return header;
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public int readBytes(byte[] bytes, int offset, int size) {
        InputStream in = read();
        try {
            int total = 0;
            while (total < size) {
                int n = in.read(bytes, offset + total, size - total);
                if (n < 0) {
                    break;
                }
                total += n;
            }
            return total;
        } catch (IOException e) {
            throw new GdxRuntimeException("Error reading file: " + this, e);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public OutputStream write(boolean append) {
        File f = file();
        File parent = f.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try {
            return new FileOutputStream(f, append);
        } catch (IOException e) {
            throw new GdxRuntimeException("Error writing file: " + this, e);
        }
    }

    @Override
    public OutputStream write(boolean append, int bufferSize) {
        return new BufferedOutputStream(write(append), bufferSize);
    }

    @Override
    public FileHandle[] list() {
        return wrap(file().listFiles());
    }

    @Override
    public FileHandle[] list(FileFilter filter) {
        return wrap(file().listFiles(filter));
    }

    @Override
    public FileHandle[] list(FilenameFilter filter) {
        return wrap(file().listFiles(filter));
    }

    @Override
    public FileHandle[] list(final String suffix) {
        File[] files = file().listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(suffix);
            }
        });
        return wrap(files);
    }

    @Override
    public boolean isDirectory() {
        return file().isDirectory();
    }

    @Override
    public FileHandle child(String name) {
        String p = path();
        return new VitaFileHandle(p.length() == 0 ? name : p + "/" + name, type());
    }

    @Override
    public FileHandle sibling(String name) {
        return parent().child(name);
    }

    @Override
    public FileHandle parent() {
        String p = path().replace('\\', '/');
        int idx = p.lastIndexOf('/');
        if (idx <= 0) {
            return new VitaFileHandle("", type());
        }
        return new VitaFileHandle(p.substring(0, idx), type());
    }

    @Override
    public boolean exists() {
        long t0 = System.nanoTime();
        boolean result = file().exists() || replacementFile() != null;
        statUs += (System.nanoTime() - t0) / 1000L;
        statCount++;
        return result;
    }

    @Override
    public void mkdirs() {
        file().mkdirs();
    }

    @Override
    public boolean delete() {
        return file().delete();
    }

    @Override
    public boolean deleteDirectory() {
        return deleteDirectory(file());
    }

    @Override
    public void emptyDirectory() {
        emptyDirectory(false);
    }

    @Override
    public void emptyDirectory(boolean preserveTree) {
        emptyDirectory(file(), preserveTree);
    }

    @Override
    public long length() {
        long t0 = System.nanoTime();
        File replacement = replacementFile();
        long result = replacement != null ? replacement.length() : file().length();
        statUs += (System.nanoTime() - t0) / 1000L;
        statCount++;
        return result;
    }

    @Override
    public long lastModified() {
        return file().lastModified();
    }

    private InputStream openDdsSibling() {
        File replacement = replacementFile();
        if (replacement == null) {
            return null;
        }
        try {
            return new FileInputStream(replacement);
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    private FileHandle[] wrap(File[] files) {
        if (files == null) {
            return new FileHandle[0];
        }
        FileHandle[] handles = new FileHandle[files.length];
        for (int i = 0; i < files.length; i++) {
            handles[i] = new VitaFileHandle(files[i].getPath(), type());
        }
        return handles;
    }

    private boolean deleteDirectory(File dir) {
        emptyDirectory(dir, false);
        return dir.delete();
    }

    private void emptyDirectory(File dir, boolean preserveTree) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File child : files) {
            if (child.isDirectory()) {
                if (preserveTree) {
                    emptyDirectory(child, true);
                } else {
                    deleteDirectory(child);
                }
            } else {
                child.delete();
            }
        }
    }

    private byte[] readAll(InputStream in, int estimatedSize) {
        try {
            // Exact-size fast path: the estimate is the stat'd file length, so
            // read straight into the result array. The old BAOS route (chunk
            // buffer + growth doublings + toByteArray copy) allocated 2-3x the
            // file size in garbage — its 256KB/4MB allocations were the main
            // young-GC triggers in the device log ([gc] req=).
            if (estimatedSize > 0) {
                byte[] out = new byte[estimatedSize];
                int total = 0;
                while (total < estimatedSize) {
                    int n = in.read(out, total, estimatedSize - total);
                    if (n < 0) {
                        break;
                    }
                    total += n;
                }
                int probe = total == estimatedSize ? in.read() : -2;
                if (probe == -1) {
                    in.close();
                    return out;
                }
                // stat lied (file changed underneath): fall through and keep
                // whatever was read so far, then stream the rest.
                ByteArrayOutputStream rest = new ByteArrayOutputStream(Math.max(1024, total));
                rest.write(out, 0, total);
                if (probe >= 0) {
                    rest.write(probe);
                }
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    rest.write(buf, 0, n);
                }
                in.close();
                return rest.toByteArray();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
            // 256KB chunks: SD sequential reads measured ~3.3MB/s with 16KB
            // chunks (sceIo call overhead); bigger blocks cut per-call cost.
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            in.close();
            return out.toByteArray();
        } catch (IOException e) {
            throw new GdxRuntimeException("Error reading file: " + this, e);
        }
    }

    private File replacementFile() {
        String p = path();
        // JPGs use <name>.jpg.dds, PNGs <stem>.dds: the scene atlases ship
        // <stem>.png AND <stem>.jpg as different pages, so a shared stem let
        // the jpg's dds shadow the png's (mosaic backgrounds, 2026-07-12).
        if (p.endsWith(".jpg")) {
            File dds = new File(p + ".dds");
            if (dds.isFile()) {
                return dds;
            }
        } else if (p.endsWith(".png")) {
            File dds = new File(p.substring(0, p.length() - 4) + ".dds");
            if (dds.isFile()) {
                return dds;
            }
        }
        if (isMissingCjkFont(p)) {
            int font = p.indexOf("/font/");
            String root = p.substring(0, font + "/font/".length());
            String[] candidates = p.contains("Bold")
                    ? new String[] {"Kreon-Bold.ttf", "NotoSans-Regular.ttf"}
                    : new String[] {"NotoSans-Regular.ttf", "Kreon-Regular.ttf"};
            for (String candidate : candidates) {
                File fallback = new File(root + candidate);
                if (fallback.isFile()) {
                    return fallback;
                }
            }
        }
        return null;
    }

    private boolean isMissingCjkFont(String p) {
        if (!p.endsWith(".ttf") && !p.endsWith(".otf")) {
            return false;
        }
        if (!(p.indexOf("/font/zhs/") >= 0
                || p.indexOf("/font/zht/") >= 0
                || p.indexOf("/font/jpn/") >= 0
                || p.indexOf("/font/kor/") >= 0)) {
            return false;
        }
        return !new File(p).isFile();
    }
}
