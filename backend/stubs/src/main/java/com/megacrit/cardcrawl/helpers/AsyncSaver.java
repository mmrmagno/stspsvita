package com.megacrit.cardcrawl.helpers;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * Shadow of the game's async save helper — the only game-package class we
 * replace. Justification: the original is a generic write-string-to-file
 * utility (no game logic) whose worker thread blocks on
 * java.util.concurrent.LinkedBlockingQueue, which TeaVM's classlib lacks.
 * Saves on Vita are small; synchronous writes are fine (and safer across
 * sleep/power events). Public surface matches the original exactly:
 * save(String, String) and shutdownSaveThread().
 */
public final class AsyncSaver {

    public static void save(String filepath, String data) {
        long t0 = System.nanoTime();
        java.io.File parent = new java.io.File(filepath).getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (Writer w = new OutputStreamWriter(
                new FileOutputStream(filepath), StandardCharsets.UTF_8)) {
            w.write(data);
        } catch (IOException e) {
            System.out.println("ERROR AsyncSaver> failed to save " + filepath + ": " + e);
        }
        // Freeze diagnostic: saves run synchronously under
        // cooperative fibers, so a slow flush stalls the frame loop.
        long ms = (System.nanoTime() - t0) / 1000000L;
        System.out.println("[save] write " + filepath + " " + data.length()
                + " chars in " + ms + "ms");
    }

    public static void shutdownSaveThread() {
    }

    private AsyncSaver() {}
}
