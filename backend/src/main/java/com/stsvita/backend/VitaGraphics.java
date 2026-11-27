package com.stsvita.backend;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.graphics.Cursor;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.glutils.GLVersion;
import com.stsvita.gen.BuildFlags;

/**
 * libGDX 1.9.5 Graphics for the Vita: one fixed 960x544 display, vitaGL
 * context created by native/src/main.c before the Java entry point runs.
 */
public class VitaGraphics implements Graphics {

    private static final float MAX_DELTA = 1f / 30f;

    public static float substepDelta;
    public static int substepCount;
    public static float benchFixedDelta;
    public static boolean benchNoSubstep;

    private final VitaApplication.Config config;
    private final VitaGL20 gl20 = BuildFlags.BENCH ? new BenchGL20() : new VitaGL20();
    private GLVersion glVersion;
    private long frameId = -1;
    private long lastFrameMicros;
    private float deltaTime;
    private float rawDeltaTime;
    private int fps;
    private int frames;
    private long fpsWindowStart;

    public VitaGraphics(VitaApplication.Config config) {
        this.config = config;
    }

    void init() {
        VitaNatives.gfxInit();
        String version = gl20.glGetString(GL20.GL_VERSION);
        String vendor = gl20.glGetString(GL20.GL_VENDOR);
        String renderer = gl20.glGetString(GL20.GL_RENDERER);
        glVersion = new GLVersion(Application.ApplicationType.Desktop,
                version != null ? version : "2.0",
                vendor != null ? vendor : "?",
                renderer != null ? renderer : "?");
        VitaNatives.log("GL: " + version + " / " + vendor + " / " + renderer);
        lastFrameMicros = fpsWindowStart = VitaNatives.micros();
    }

    void beginFrame() {
        long now = VitaNatives.micros();
        rawDeltaTime = (now - lastFrameMicros) / 1e6f;
        if (BuildFlags.BENCH && benchFixedDelta > 0f) {
            rawDeltaTime = benchFixedDelta;
        }
        deltaTime = Math.min(rawDeltaTime, MAX_DELTA);
        substepDelta = 0f;
        substepCount = Math.min(4, (int) Math.ceil(deltaTime * 60f - 0.25f));
        if (BuildFlags.BENCH && benchNoSubstep) {
            substepCount = 1;
        }
        lastFrameMicros = now;
        frameId++;
        frames++;
        if (now - fpsWindowStart >= 1_000_000L) {
            fps = frames;
            frames = 0;
            fpsWindowStart = now;
        }
    }

    void endFrame() {
        VitaNatives.swapBuffers();
        // Release the frame's pinned client-array copies (see Buffers): the
        // GL calls that referenced them have been submitted and flushed.
        Buffers.clearPins();
    }

    @Override
    public boolean isGL30Available() {
        return false;
    }

    @Override
    public GL20 getGL20() {
        return gl20;
    }

    @Override
    public GL30 getGL30() {
        return null;
    }

    @Override
    public int getWidth() {
        return config.width;
    }

    @Override
    public int getHeight() {
        return config.height;
    }

    @Override
    public int getBackBufferWidth() {
        return config.width;
    }

    @Override
    public int getBackBufferHeight() {
        return config.height;
    }

    @Override
    public long getFrameId() {
        return frameId;
    }

    @Override
    public float getDeltaTime() {
        return substepDelta > 0f ? substepDelta : deltaTime;
    }

    @Override
    public float getRawDeltaTime() {
        return rawDeltaTime;
    }

    @Override
    public int getFramesPerSecond() {
        return fps;
    }

    @Override
    public GraphicsType getType() {
        // No Vita member in the 1.9.5 enum; LWJGL matches how the game
        // expects a desktop-like environment to behave.
        return GraphicsType.LWJGL;
    }

    @Override
    public GLVersion getGLVersion() {
        return glVersion;
    }

    @Override
    public float getPpiX() {
        return 220f; // 5" 960x544 OLED/LCD
    }

    @Override
    public float getPpiY() {
        return 220f;
    }

    @Override
    public float getPpcX() {
        return getPpiX() / 2.54f;
    }

    @Override
    public float getPpcY() {
        return getPpiY() / 2.54f;
    }

    @Override
    public float getDensity() {
        return getPpiX() / 160f;
    }

    @Override
    public boolean supportsDisplayModeChange() {
        return false;
    }

    @Override
    public Monitor getPrimaryMonitor() {
        return new VitaMonitor();
    }

    @Override
    public Monitor getMonitor() {
        return getPrimaryMonitor();
    }

    @Override
    public Monitor[] getMonitors() {
        return new Monitor[]{getPrimaryMonitor()};
    }

    @Override
    public DisplayMode[] getDisplayModes() {
        return new DisplayMode[]{getDisplayMode()};
    }

    @Override
    public DisplayMode[] getDisplayModes(Monitor monitor) {
        return getDisplayModes();
    }

    @Override
    public DisplayMode getDisplayMode() {
        return new VitaDisplayMode(config.width, config.height);
    }

    @Override
    public DisplayMode getDisplayMode(Monitor monitor) {
        return getDisplayMode();
    }

    @Override
    public boolean setFullscreenMode(DisplayMode displayMode) {
        return displayMode.width == config.width && displayMode.height == config.height;
    }

    @Override
    public boolean setWindowedMode(int width, int height) {
        return width == config.width && height == config.height;
    }

    @Override
    public void setTitle(String title) {
    }

    @Override
    public void setUndecorated(boolean undecorated) {
    }

    @Override
    public void setResizable(boolean resizable) {
    }

    @Override
    public void setVSync(boolean vsync) {
        VitaNatives.setVsync(vsync);
    }

    @Override
    public BufferFormat getBufferFormat() {
        return new BufferFormat(8, 8, 8, 8, 24, 8, 0, false);
    }

    @Override
    public boolean supportsExtension(String extension) {
        return false;
    }

    @Override
    public void setContinuousRendering(boolean isContinuous) {
    }

    @Override
    public boolean isContinuousRendering() {
        return true;
    }

    @Override
    public void requestRendering() {
    }

    @Override
    public boolean isFullscreen() {
        return true;
    }

    @Override
    public Cursor newCursor(Pixmap pixmap, int xHotspot, int yHotspot) {
        return null;
    }

    @Override
    public void setCursor(Cursor cursor) {
    }

    @Override
    public void setSystemCursor(Cursor.SystemCursor systemCursor) {
    }

    private static final class VitaDisplayMode extends DisplayMode {
        VitaDisplayMode(int width, int height) {
            super(width, height, 60, 32);
        }
    }

    private static final class VitaMonitor extends Monitor {
        VitaMonitor() {
            super(0, 0, "PSVita");
        }
    }
}
