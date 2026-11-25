package com.codedisaster.steamworks;

/**
 * steamworks4j stub: Steam is never available on the Vita. The game already
 * handles this - SteamIntegration checks init()/isSteamRunning() and falls
 * back to its non-Steam distributor paths.
 */
public final class SteamAPI {

    public static void loadLibraries() throws SteamException {
    }

    public static boolean init() {
        return false;
    }

    public static boolean init(String libraryPath) {
        return false;
    }

    public static boolean restartAppIfNecessary(int appId) {
        return false;
    }

    public static boolean isSteamRunning() {
        return false;
    }

    public static void runCallbacks() {
    }

    public static void shutdown() {
    }

    public static void printDebugInfo(java.io.PrintStream stream) {
    }

    private SteamAPI() {}
}
