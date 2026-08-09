package com.stsvita.backend;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputProcessor;

/**
 * libGDX 1.9.5 Input for the Vita. Pointer-first: the front touch panel
 * (mouse in the host harness) drives the game's existing mouse path —
 * Slay the Spire is fully playable by pointer. SceCtrl buttons are exposed
 * for the controller layer (M3 follow-up: virtual Controller through the
 * Controllers stub so the game's gamepad UI mode works).
 *
 * Polled once per frame by VitaApplication.mainLoop before render().
 */
public class VitaInput implements Input {

    private InputProcessor processor;
    private int x, y;
    private int lastX, lastY;
    private int deltaX, deltaY;
    private boolean touched;
    private boolean wasTouched;
    private boolean justTouched;
    private int prevButtons;

    /* SceCtrl button bits (psp2/ctrl.h). */
    private static final int BTN_SELECT   = 0x000001;
    private static final int BTN_START    = 0x000008;
    private static final int BTN_UP       = 0x000010;
    private static final int BTN_RIGHT    = 0x000020;
    private static final int BTN_DOWN     = 0x000040;
    private static final int BTN_LEFT     = 0x000080;
    private static final int BTN_LTRIGGER = 0x000100;
    private static final int BTN_RTRIGGER = 0x000200;
    private static final int BTN_TRIANGLE = 0x001000;
    private static final int BTN_CIRCLE   = 0x002000;
    private static final int BTN_CROSS    = 0x004000;
    private static final int BTN_SQUARE   = 0x008000;

    /**
     * SceCtrl bit -> StS controller button index (CInputActionSet defaults,
     * Xbox layout): Cross=0 confirm, Circle=1 cancel, Square=2 view/top
     * panel, Triangle=3 proceed, L=4 page left, R=5 page right,
     * SELECT=6 map, START=7 settings. Dpad is the POV hat; sticks are axes.
     * Dispatched as real gdx-controllers events so StS runs its native
     * controller mode (cursor snapping, glyphs, remapping) — a plain
     * keyboard shim made X "move up" instead of "play card".
     */
    private static final int[][] BUTTON_INDICES = {
        {BTN_CROSS, 0},
        {BTN_CIRCLE, 1},
        {BTN_SQUARE, 2},
        {BTN_TRIANGLE, 3},
        {BTN_LTRIGGER, 4},
        {BTN_RTRIGGER, 5},
        {BTN_SELECT, 6},
        {BTN_START, 7},
    };

    private final VitaController controller = new VitaController();
    private final float[] prevAxes = new float[4];
    private com.badlogic.gdx.controllers.PovDirection prevPov =
            com.badlogic.gdx.controllers.PovDirection.center;

    public VitaInput() {
        com.badlogic.gdx.controllers.Controllers.stsvRegister(controller);
    }

    private void dispatchControllerEvents(int btns, int pressed, int released) {
        for (int[] mapping : BUTTON_INDICES) {
            int bit = mapping[0];
            int index = mapping[1];
            if ((pressed & bit) != 0) {
                controller.setButton(index, true);
                for (com.badlogic.gdx.controllers.ControllerListener l : allControllerListeners()) {
                    l.buttonDown(controller, index);
                }
            } else if ((released & bit) != 0) {
                controller.setButton(index, false);
                for (com.badlogic.gdx.controllers.ControllerListener l : allControllerListeners()) {
                    l.buttonUp(controller, index);
                }
            }
        }

        com.badlogic.gdx.controllers.PovDirection pov = povOf(btns);
        if (pov != prevPov) {
            prevPov = pov;
            controller.setPov(pov);
            for (com.badlogic.gdx.controllers.ControllerListener l : allControllerListeners()) {
                l.povMoved(controller, 0, pov);
            }
        }

        // StS axes: 0 = left stick Y (+down), 1 = left stick X (+right),
        // 2/3 = right stick Y/X. stsv_ctrl_axis: 0=LX 1=LY 2=RX 3=RY,
        // already oriented +down/+right.
        dispatchAxis(0, deadzone(VitaNatives.ctrlAxis(1)));
        dispatchAxis(1, deadzone(VitaNatives.ctrlAxis(0)));
        dispatchAxis(2, deadzone(VitaNatives.ctrlAxis(3)));
        dispatchAxis(3, deadzone(VitaNatives.ctrlAxis(2)));
    }

    private void dispatchAxis(int stsAxis, float value) {
        if (Math.abs(value - prevAxes[stsAxis]) < 0.01f) {
            return;
        }
        prevAxes[stsAxis] = value;
        controller.setAxis(stsAxis, value);
        for (com.badlogic.gdx.controllers.ControllerListener l : allControllerListeners()) {
            l.axisMoved(controller, stsAxis, value);
        }
    }

    private static float deadzone(float v) {
        return Math.abs(v) < 0.25f ? 0f : v;
    }

    private static com.badlogic.gdx.controllers.PovDirection povOf(int btns) {
        boolean up = (btns & BTN_UP) != 0;
        boolean down = (btns & BTN_DOWN) != 0;
        boolean left = (btns & BTN_LEFT) != 0;
        boolean right = (btns & BTN_RIGHT) != 0;
        if (up) {
            return left ? com.badlogic.gdx.controllers.PovDirection.northWest
                 : right ? com.badlogic.gdx.controllers.PovDirection.northEast
                 : com.badlogic.gdx.controllers.PovDirection.north;
        }
        if (down) {
            return left ? com.badlogic.gdx.controllers.PovDirection.southWest
                 : right ? com.badlogic.gdx.controllers.PovDirection.southEast
                 : com.badlogic.gdx.controllers.PovDirection.south;
        }
        if (left) {
            return com.badlogic.gdx.controllers.PovDirection.west;
        }
        if (right) {
            return com.badlogic.gdx.controllers.PovDirection.east;
        }
        return com.badlogic.gdx.controllers.PovDirection.center;
    }

    private java.util.ArrayList<com.badlogic.gdx.controllers.ControllerListener> allControllerListeners() {
        java.util.ArrayList<com.badlogic.gdx.controllers.ControllerListener> all =
                new java.util.ArrayList<>();
        for (com.badlogic.gdx.controllers.ControllerListener l
                : com.badlogic.gdx.controllers.Controllers.stsvGetListeners()) {
            all.add(l);
        }
        for (com.badlogic.gdx.controllers.ControllerListener l : controller.ownListeners()) {
            all.add(l);
        }
        return all;
    }

    /* sceImeDialog state: at most one dialog at a time. When a
     * TextInputListener is set the result goes to it (getTextInput path);
     * otherwise the text is injected via keyTyped (StS's custom name field,
     * opened with START). */
    private boolean imeOpen;
    private TextInputListener imeListener;

    /** Opens the on-screen keyboard; false if unavailable (host harness). */
    private boolean imeStart(String title, String initial) {
        if (imeOpen) {
            return false;
        }
        char[] t = toChars0(title);
        char[] i = toChars0(initial);
        int rc = VitaNatives.imeOpen(org.teavm.interop.Address.ofData(t),
                org.teavm.interop.Address.ofData(i), 64);
        imeOpen = rc == 0;
        return imeOpen;
    }

    private static char[] toChars0(String s) {
        if (s == null) {
            s = "";
        }
        char[] c = new char[s.length() + 1];
        s.getChars(0, s.length(), c, 0);
        return c;
    }

    private String imeFetchResult() {
        char[] out = new char[65];
        int len = VitaNatives.imeResult(org.teavm.interop.Address.ofData(out), out.length);
        return new String(out, 0, len);
    }

    private void imeUpdate() {
        if (!imeOpen) {
            return;
        }
        int status = VitaNatives.imePoll();
        if (status == 0) {
            return;
        }
        imeOpen = false;
        TextInputListener listener = imeListener;
        imeListener = null;
        if (status == 1) {
            String text = imeFetchResult();
            VitaNatives.log("[ime] entered \"" + text + "\"");
            if (listener != null) {
                listener.input(text);
            } else if (processor != null) {
                for (int i = 0; i < text.length(); i++) {
                    processor.keyTyped(text.charAt(i));
                }
            }
        } else {
            VitaNatives.log("[ime] canceled");
            if (listener != null) {
                listener.canceled();
            }
        }
    }

    public void update() {
        imeUpdate();

        wasTouched = touched;
        lastX = x;
        lastY = y;

        touched = VitaNatives.touchCount() > 0;
        if (touched) {
            x = VitaNatives.touchX(0);
            y = VitaNatives.touchY(0);
        }
        deltaX = x - lastX;
        deltaY = y - lastY;
        justTouched = touched && !wasTouched;

        int btns = VitaNatives.ctrlButtons();
        int pressed = btns & ~prevButtons;
        int released = prevButtons & ~btns;
        prevButtons = btns;

        dispatchControllerEvents(btns, pressed, released);

        if (processor == null) {
            return;
        }
        if (justTouched) {
            processor.touchDown(x, y, 0, Buttons.LEFT);
        } else if (wasTouched && !touched) {
            processor.touchUp(lastX, lastY, 0, Buttons.LEFT);
        } else if (touched && (deltaX != 0 || deltaY != 0)) {
            processor.touchDragged(x, y, 0);
        } else if (!touched && (deltaX != 0 || deltaY != 0)) {
            processor.mouseMoved(x, y);
        }

        // L+R+START opens the on-screen keyboard for StS's custom text fields
        // (the save-slot name screen reads keyTyped, not getTextInput); the
        // result is injected via keyTyped by imeUpdate(). Plain START is the
        // controller "settings" button now, so the chord keeps them apart.
        // Where the IME is unavailable (host harness), type a default name.
        if ((pressed & BTN_START) != 0 && (btns & BTN_LTRIGGER) != 0
                && (btns & BTN_RTRIGGER) != 0 && !imeOpen) {
            if (imeStart("Name", "Vita")) {
                VitaNatives.log("[input] L+R+START -> sceImeDialog open");
            } else {
                VitaNatives.log("[input] L+R+START -> IME unavailable, inject default name");
                String name = "Vita";
                for (int i = 0; i < name.length(); i++) {
                    processor.keyTyped(name.charAt(i));
                }
            }
        }
    }

    /** SceCtrl bitmask for the controller layer. */
    public int buttons() {
        return VitaNatives.ctrlButtons();
    }

    public float axis(int axis) {
        return VitaNatives.ctrlAxis(axis);
    }

    @Override
    public float getAccelerometerX() {
        return 0;
    }

    @Override
    public float getAccelerometerY() {
        return 0;
    }

    @Override
    public float getAccelerometerZ() {
        return 0;
    }

    @Override
    public float getGyroscopeX() {
        return 0;
    }

    @Override
    public float getGyroscopeY() {
        return 0;
    }

    @Override
    public float getGyroscopeZ() {
        return 0;
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getX(int pointer) {
        return pointer == 0 ? x : 0;
    }

    @Override
    public int getDeltaX() {
        return deltaX;
    }

    @Override
    public int getDeltaX(int pointer) {
        return pointer == 0 ? deltaX : 0;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public int getY(int pointer) {
        return pointer == 0 ? y : 0;
    }

    @Override
    public int getDeltaY() {
        return deltaY;
    }

    @Override
    public int getDeltaY(int pointer) {
        return pointer == 0 ? deltaY : 0;
    }

    @Override
    public boolean isTouched() {
        return touched;
    }

    @Override
    public boolean justTouched() {
        return justTouched;
    }

    @Override
    public boolean isTouched(int pointer) {
        return pointer == 0 && touched;
    }

    @Override
    public boolean isButtonPressed(int button) {
        return button == Buttons.LEFT && touched;
    }

    @Override
    public boolean isKeyPressed(int key) {
        return false;
    }

    @Override
    public boolean isKeyJustPressed(int key) {
        return false;
    }

    @Override
    public void getTextInput(TextInputListener listener, String title, String text, String hint) {
        if (listener == null) {
            return;
        }
        // sceImeDialog on-screen keyboard; delivered asynchronously by
        // imeUpdate() once the dialog closes. Where the IME is unavailable
        // (host harness), accept the caller's supplied default so text-entry
        // screens can proceed instead of being cancelled.
        if (imeStart(title, text)) {
            imeListener = listener;
            return;
        }
        String result = (text != null && text.trim().length() > 0) ? text : "Vita";
        listener.input(result);
    }

    @Override
    public void setOnscreenKeyboardVisible(boolean visible) {
    }

    @Override
    public void vibrate(int milliseconds) {
    }

    @Override
    public void vibrate(long[] pattern, int repeat) {
    }

    @Override
    public void cancelVibrate() {
    }

    @Override
    public float getAzimuth() {
        return 0;
    }

    @Override
    public float getPitch() {
        return 0;
    }

    @Override
    public float getRoll() {
        return 0;
    }

    @Override
    public void getRotationMatrix(float[] matrix) {
    }

    @Override
    public long getCurrentEventTime() {
        return System.nanoTime();
    }

    @Override
    public void setCatchBackKey(boolean catchBack) {
    }

    @Override
    public boolean isCatchBackKey() {
        return false;
    }

    @Override
    public void setCatchMenuKey(boolean catchMenu) {
    }

    @Override
    public boolean isCatchMenuKey() {
        return false;
    }

    /**
     * Fired when the game installs its text-typing InputProcessor
     * (TypeHelper — the save-name / seed fields). VitaLauncher registers a
     * game-aware handler that opens the on-screen keyboard and writes the
     * result into the game's text field; the backend itself must not
     * reference game classes.
     */
    public interface TextFieldListener {
        void opened();
    }

    public static TextFieldListener textFieldListener;

    @Override
    public void setInputProcessor(InputProcessor processor) {
        this.processor = processor;
        if (processor != null && textFieldListener != null
                && processor.getClass().getName().endsWith("TypeHelper")) {
            textFieldListener.opened();
        }
    }

    @Override
    public InputProcessor getInputProcessor() {
        return processor;
    }

    @Override
    public boolean isPeripheralAvailable(Peripheral peripheral) {
        return peripheral == Peripheral.MultitouchScreen;
    }

    @Override
    public int getRotation() {
        return 0;
    }

    @Override
    public Orientation getNativeOrientation() {
        return Orientation.Landscape;
    }

    @Override
    public void setCursorCatched(boolean catched) {
    }

    @Override
    public boolean isCursorCatched() {
        return false;
    }

    @Override
    public void setCursorPosition(int x, int y) {
        // StS controller mode drives the pointer through this (map node
        // selection, menu focus, targeting): the virtual cursor must really
        // move, because InputHelper reads getX()/getY() every frame and the
        // confirm action clicks at the cursor. Leaving it a no-op made the
        // pad feel broken (stale hover, targeting arrow at the last touch).
        this.x = x;
        this.y = y;
    }
}
