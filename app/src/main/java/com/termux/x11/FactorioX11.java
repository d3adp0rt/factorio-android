package com.termux.x11;

import android.content.Context;

/**
 * Small bridge into the {@code :lorie} library (package-private members of CmdEntryPoint),
 * so the X server can be hosted in a dedicated process of this app without
 * {@code app_process} and without the Unsafe/ActivityThread context hack.
 */
public final class FactorioX11 {
    private FactorioX11() {
    }

    /** Loads libXlorie.so from the app's native library directory. */
    public static void loadLibrary() {
        System.loadLibrary("Xlorie");
    }

    /** Real application context used for broadcasting "a client connected" to the UI process. */
    public static void setContext(Context ctx) {
        CmdEntryPoint.ctx = ctx;
    }

    /** Starts the X server. Must be called on a thread that has a Looper. */
    public static boolean start(String[] args) {
        try {
            new CmdEntryPoint(args);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
