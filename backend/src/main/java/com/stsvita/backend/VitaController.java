package com.stsvita.backend;

import com.badlogic.gdx.controllers.Controller;
import com.badlogic.gdx.controllers.ControllerListener;
import com.badlogic.gdx.controllers.PovDirection;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;

/**
 * The Vita gamepad as a gdx-controllers Controller, so StS runs its native
 * controller mode (cursor snapping, button glyphs, remapping) instead of a
 * keyboard shim. VitaInput polls SceCtrl each frame, updates this state, and
 * dispatches buttonDown/buttonUp/axisMoved/povMoved to the listeners StS
 * registers via the Controllers stub. Button indices follow StS's
 * CInputActionSet defaults (Xbox layout): 0=confirm 1=cancel 2=view
 * 3=proceed 4=pageLeft 5=pageRight 6=map 7=settings; axes 0/1=left stick
 * Y/X, 2/3=right stick Y/X, 4=triggers (rear-panel taps); dpad is the POV hat.
 */
public class VitaController implements Controller {

    private final boolean[] buttons = new boolean[16];
    private final float[] axes = new float[5];
    private PovDirection pov = PovDirection.center;
    private final Array<ControllerListener> listeners = new Array<>();

    void setButton(int index, boolean down) {
        if (index >= 0 && index < buttons.length) {
            buttons[index] = down;
        }
    }

    void setAxis(int index, float value) {
        if (index >= 0 && index < axes.length) {
            axes[index] = value;
        }
    }

    void setPov(PovDirection direction) {
        pov = direction;
    }

    /** Listeners added on this controller (global ones live in Controllers). */
    Array<ControllerListener> ownListeners() {
        return listeners;
    }

    @Override
    public boolean getButton(int buttonCode) {
        return buttonCode >= 0 && buttonCode < buttons.length && buttons[buttonCode];
    }

    @Override
    public float getAxis(int axisCode) {
        return axisCode >= 0 && axisCode < axes.length ? axes[axisCode] : 0f;
    }

    @Override
    public PovDirection getPov(int povCode) {
        return povCode == 0 ? pov : PovDirection.center;
    }

    @Override
    public boolean getSliderX(int sliderCode) {
        return false;
    }

    @Override
    public boolean getSliderY(int sliderCode) {
        return false;
    }

    @Override
    public Vector3 getAccelerometer(int accelerometerCode) {
        return Vector3.Zero;
    }

    @Override
    public void setAccelerometerSensitivity(float sensitivity) {
    }

    @Override
    public String getName() {
        return "PS Vita";
    }

    @Override
    public void addListener(ControllerListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(ControllerListener listener) {
        listeners.removeValue(listener, true);
    }
}
