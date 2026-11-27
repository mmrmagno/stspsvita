package com.stsvita.compat;

import com.badlogic.gdx.Gdx;
import com.stsvita.backend.VitaGraphics;

public final class Substep {

    private static int remaining;

    public static boolean begin() {
        int n = VitaGraphics.substepCount;
        if (n <= 1 || VitaGraphics.substepDelta > 0f) {
            return false;
        }
        VitaGraphics.substepDelta = Gdx.graphics.getDeltaTime() / n;
        remaining = n;
        return true;
    }

    public static boolean again(boolean done) {
        if (!done && --remaining > 0) {
            return true;
        }
        remaining = 0;
        VitaGraphics.substepDelta = 0f;
        return false;
    }

    private Substep() {}
}
