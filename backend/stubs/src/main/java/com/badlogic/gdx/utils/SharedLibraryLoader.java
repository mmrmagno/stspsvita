package com.badlogic.gdx.utils;

import java.io.File;
import java.io.IOException;

/**
 * Shadow of libGDX's native-library extractor/loader: there are no .so/.dll
 * natives in an AOT build - everything native is linked into the executable.
 * Platform flags describe the Vita. (Clean-room replacement for an
 * Apache-2.0 libGDX class; the real one reaches System.loadLibrary and
 * zip-extraction paths TeaVM can't and shouldn't compile.)
 */
public class SharedLibraryLoader {

    public static boolean isWindows = false;
    public static boolean isLinux = true;
    public static boolean isMac = false;
    public static boolean isIos = false;
    public static boolean isAndroid = false;
    public static boolean isARM = true;
    public static boolean is64Bit = false;

    public SharedLibraryLoader() {
    }

    public SharedLibraryLoader(String nativesJar) {
    }

    public void load(String libraryName) {
    }

    public File extractFile(String sourcePath, String dirName) throws IOException {
        throw new IOException("no native libraries to extract in an AOT build");
    }

    public void extractFileTo(String sourcePath, File dir) throws IOException {
    }

    public String crc(java.io.InputStream input) {
        return "0";
    }
}
