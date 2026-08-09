package com.badlogic.gdx.controllers;

import com.badlogic.gdx.utils.Array;

/**
 * Shadow of gdx-controllers' Controllers: the real class discovers a
 * platform ControllerManager via Class.forName — a dead end under AOT.
 * M3 registers the Vita gamepad here as a virtual Controller so the game's
 * existing controller mode drives everything.
 */
public final class Controllers {

    private static final Array<Controller> controllers = new Array<>();
    private static final Array<ControllerListener> listeners = new Array<>();

    public static Array<Controller> getControllers() {
        return controllers;
    }

    public static void addListener(ControllerListener listener) {
        listeners.add(listener);
    }

    public static void removeListener(ControllerListener listener) {
        listeners.removeValue(listener, true);
    }

    public static void clearListeners() {
        listeners.clear();
    }

    /** Backend hook (not part of the gdx-controllers API): VitaInput
     * registers the physical gamepad and dispatches events through this. */
    public static Array<ControllerListener> stsvGetListeners() {
        return listeners;
    }

    public static void stsvRegister(Controller controller) {
        controllers.add(controller);
    }

    private Controllers() {}
}
