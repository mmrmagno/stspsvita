package com.stsvita.backend;

import org.teavm.interop.Import;
import org.teavm.interop.c.Include;

/**
 * The Java<->C boundary. Every function here is implemented in
 * native/src/glue/ and imported by TeaVM's C backend — no JNI involved.
 * The @Include makes every calling TU declare the imports via
 * stsvita_glue.h (add native/src/glue to the include path).
 *
 * Only primitives (and, later, org.teavm.interop.Address for buffers) cross
 * this boundary; String/Buffer marshalling helpers live on the Java side.
 * On a desktop JVM these natives are never invoked (backend code paths that
 * reach them are Vita-only).
 */
@Include("stsvita_glue.h")
public final class VitaNatives {

    /* --- lifecycle / timing (glue/main.c) --- */

    /** Creates the GL context (host); no-op on Vita (main.c owns vglInit). */
    @Import(name = "stsv_gfx_init")
    public static native void gfxInit();

    @Import(name = "stsv_swap_buffers")
    public static native void swapBuffers();

    @Import(name = "stsv_set_vsync")
    public static native void setVsync(boolean on);

    /** Non-zero in the Linux host harness, zero on Vita hardware. */
    @Import(name = "stsv_is_host_harness")
    public static native int isHostHarness();

    /** Wall-clock us — keeps counting through suspend, unlike micros(). */
    @Import(name = "stsv_wall_us")
    public static native long wallMicros();

    /** Enables the bounded post-boot texture spill (gl_common_glue.c). */
    @Import(name = "stsv_tex_spill_enable")
    public static native void texSpillEnable();

    /** Per-frame FreeType rasterization stats: ms*1000+calls, resets. */
    @Import(name = "stsv_ft_frame_stats")
    public static native long ftFrameStats();

    /** Per-frame glyph sub-upload stats: ms*1000+calls, resets. */
    @Import(name = "stsv_subtex_frame_stats")
    public static native long subtexFrameStats();

    /** Per-frame stb PNG/JPG decode stats: ms*1000+calls, resets. */
    @Import(name = "stsv_img_frame_stats")
    public static native long imgFrameStats();

    /** Per-frame runtime DXT-compression stats: ms*1000+calls, resets. */
    @Import(name = "stsv_dxt_frame_stats")
    public static native long dxtFrameStats();

    /** Per-frame glTexImage upload stats: ms*1000+calls, resets. */
    @Import(name = "stsv_upload_frame_stats")
    public static native long uploadFrameStats();

    /** Read-only guard preview: would a texture of this byte size be admitted
     * right now? Lets VitaFileHandle skip the SD read of a doomed.dds. */
    @Import(name = "stsv_tex_would_fit")
    public static native int texWouldFit(int need);

    @Import(name = "stsv_tex_note_path")
    private static native void nTexNotePath(org.teavm.interop.Address utf8, int len);

    /** Names the next texture upload in the [DIAG dxt] SKIP/SPILL lines. */
    public static void texNotePath(String path) {
        byte[] utf8 = path.getBytes();
        nTexNotePath(org.teavm.interop.Address.ofData(utf8), utf8.length);
    }

    @Import(name = "stsv_transition_diag")
    private static native void nTransitionDiag(org.teavm.interop.Address utf8, int len);

    /** Crash-safe Act-transition marker with native pool/heap context. */
    public static void transitionDiag(String label) {
        // Preserve the ordinary INFO context before the native stderr marker.
        System.out.flush();
        byte[] utf8 = label.getBytes();
        nTransitionDiag(org.teavm.interop.Address.ofData(utf8), utf8.length);
    }

    /* --- on-screen keyboard (glue/ime_glue.c; host stubs report failure) --- */

    /** title/initial: NUL-terminated UTF-16 (Java chars). 0 on success. */
    @Import(name = "stsv_ime_open")
    public static native int imeOpen(org.teavm.interop.Address titleU16,
                                     org.teavm.interop.Address initialU16, int maxLen);

    /** Poll once per frame: 0=idle/running, 1=text entered, 2=canceled. */
    @Import(name = "stsv_ime_poll")
    public static native int imePoll();

    /** Copies the UTF-16 result into out; returns its length in chars. */
    @Import(name = "stsv_ime_result")
    public static native int imeResult(org.teavm.interop.Address outU16, int capChars);

    /* --- input (glue/input_glue.c) --- */

    /** Packed SceCtrl button bitmask for this frame. */
    @Import(name = "stsv_ctrl_buttons")
    public static native int ctrlButtons();

    /** Stick axes as [-1,1]; axis: 0=LX 1=LY 2=RX 3=RY. */
    @Import(name = "stsv_ctrl_axis")
    public static native float ctrlAxis(int axis);

    /** Front-touch state: returns touch count; coords fetched per finger. */
    @Import(name = "stsv_touch_count")
    public static native int touchCount();

    @Import(name = "stsv_touch_x")
    public static native int touchX(int finger);

    @Import(name = "stsv_touch_y")
    public static native int touchY(int finger);

    /* --- convenience (implemented in Java, routed once, replaced when a
           direct sceClibPrintf hook is worth it) --- */

    public static void log(String line) {
        // Native stdout batches ordinary game INFO lines to avoid synchronous
        // ux0 writes in listener.render(). A diagnostic is an evidence
        // boundary: flush the preceding game context, then write it through
        // unbuffered stderr so it survives an abort/core-producing crash.
        System.out.flush();
        System.err.println(line);
    }

    public static void exitProcess(int code) {
        // No System.exit on the C target: returning from main unwinds to
        // native main(), which tears down vitaGL and exits the process.
        log("exiting with code " + code);
    }

    public static long micros() {
        return System.nanoTime() / 1000L;
    }

    private VitaNatives() {}
}
