package com.stsvita.backend;

import com.badlogic.gdx.Files;
import com.badlogic.gdx.files.FileHandle;

/**
 * File access on the Vita. Newlib routes stdio through sceIo, so standard
 * java.io over TeaVM's C runtime reaches Vita paths directly:
 *
 *   internal/classpath -> app0:/assets/   (read-only VPK payload)
 *   local/external     -> ux0:data/stsvita/  (saves, preferences, logs -
 *                          same file formats as desktop, so PC saves can be
 *                          copied straight in)
 */
public class VitaFiles implements Files {

    public static final String DATA = "ux0:data/stsvita/";

    /**
     * Where the read-only asset payload lives. The full VPK bundles it at
     * {@code app0:/assets}; the slim (code-only) dev VPK ships none, so assets
     * are pre-installed under {@code ux0:} and read from there. Detected once
     * at startup so the same eboot serves both VPK flavours (and the desktop
     * harness, where app0:/assets is a symlink).
     */
    public static final String ASSETS = assetRoot();

    @Override
    public FileHandle getFileHandle(String path, FileType type) {
        // Handles are typed Absolute after resolution: FileHandle's
        // Classpath branches call Class.getResource, which doesn't exist
        // under AOT - plain file IO on the resolved path always works.
        return new VitaFileHandle(resolve(path, type), FileType.Absolute);
    }

    @Override
    public FileHandle classpath(String path) {
        return getFileHandle(path, FileType.Classpath);
    }

    @Override
    public FileHandle internal(String path) {
        return getFileHandle(path, FileType.Internal);
    }

    @Override
    public FileHandle external(String path) {
        return getFileHandle(path, FileType.External);
    }

    @Override
    public FileHandle absolute(String path) {
        return getFileHandle(path, FileType.Absolute);
    }

    @Override
    public FileHandle local(String path) {
        return getFileHandle(path, FileType.Local);
    }

    @Override
    public String getExternalStoragePath() {
        return DATA;
    }

    @Override
    public boolean isExternalStorageAvailable() {
        return true;
    }

    @Override
    public String getLocalStoragePath() {
        return DATA;
    }

    @Override
    public boolean isLocalStorageAvailable() {
        return true;
    }

    private static String resolve(String path, FileType type) {
        if (type == FileType.Classpath || type == FileType.Internal) {
            return ASSETS + path;
        }
        if (type == FileType.Local || type == FileType.External) {
            return DATA + path;
        }
        return path;
    }

    private static String assetRoot() {
        if (VitaNatives.isHostHarness() != 0) {
            return "app0:/assets/";
        }
        return new java.io.File("app0:/assets").exists()
                ? "app0:/assets/"
                : DATA + "assets/";
    }
}
