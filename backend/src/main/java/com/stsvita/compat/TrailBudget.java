package com.stsvita.compat;

import java.util.ArrayList;

public final class TrailBudget {
    private static final int FULL = 20;
    private static final int MIN = 8;
    private static final float TARGET = 2500f;
    private static int points = FULL;
    private static float span = FULL - 1;
    private static float weight = 1f;
    private static float live;

    public static void frame(ArrayList<?> topLevel) {
        float bound = topLevel == null ? 0f : topLevel.size() * ((float) FULL / MIN);
        if (live > bound) {
            live = bound;
        }
        int want = FULL;
        if (live > TARGET) {
            want = Math.max(MIN, Math.round(FULL * TARGET / live));
        }
        int n = points;
        if (want > n) {
            n++;
        } else {
            n = want;
        }
        points = n;
        span = n - 1;
        weight = (float) FULL / n;
    }

    public static int points() {
        return points;
    }

    public static float span() {
        return span;
    }

    public static float spawned() {
        live += weight;
        return weight;
    }

    public static void died(float w) {
        live -= w;
        if (live < 0f) {
            live = 0f;
        }
    }

    public static float alpha(float a, float w) {
        float v = a * w;
        return v > 1f ? 1f : v;
    }

    private TrailBudget() {}
}
