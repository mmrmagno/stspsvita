package com.stsvita.compat;

public final class RoomGc {

    public static void collect() {
        System.gc();
    }

    private RoomGc() {}
}
