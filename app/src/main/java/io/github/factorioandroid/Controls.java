package io.github.factorioandroid;

import android.content.Context;

import com.termux.x11.controls.ControlsSpec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * On-screen gamepad for Factorio.
 *
 * <p>The layout itself lives in {@code res/raw/controls_default.json} and is handed over to the
 * X11 activity through {@link ControlsSpec} (a JSON document in a shared preferences file), so
 * the overlay in the {@code lorie} module stays generic: it only knows about buttons and
 * actions. A layout editor can later rewrite the same document without touching the viewer.
 *
 * <p>Supported actions: {@code key} (press, or press-and-hold with {@code "hold": true}),
 * {@code mouse} (left/right/middle click or hold), {@code wheel} and {@code toggle} (collapse
 * the overlay so the whole screen is visible). Positions and sizes are fractions of the screen.
 */
public final class Controls {
    private Controls() {
    }

    public static boolean enabled(Context c) {
        return c.getSharedPreferences(ControlsSpec.PREFS, Context.MODE_PRIVATE)
                .getBoolean(ControlsSpec.KEY_ENABLED, true);
    }

    public static void setEnabled(Context c, boolean enabled) {
        c.getSharedPreferences(ControlsSpec.PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(ControlsSpec.KEY_ENABLED, enabled)
                .apply();
    }

    public static String layout(Context c) {
        return c.getSharedPreferences(ControlsSpec.PREFS, Context.MODE_PRIVATE)
                .getString(ControlsSpec.KEY_LAYOUT, "");
    }

    public static void setLayout(Context c, String json) {
        c.getSharedPreferences(ControlsSpec.PREFS, Context.MODE_PRIVATE).edit()
                .putString(ControlsSpec.KEY_LAYOUT, json)
                .apply();
    }

    /**
     * The gamepad preset: an analogue stick on the left and pad buttons on the right, mapped
     * to the keys/mouse Factorio expects. Android gives apps no /dev/uinput, so the game
     * itself will never see a real pad - this is the emulation layer (see ControlsSpec).
     */
    public static boolean gamepadEnabled(Context c) {
        return c.getSharedPreferences(ControlsSpec.PREFS, Context.MODE_PRIVATE)
                .getBoolean("gamepad", false);
    }

    public static void setGamepadEnabled(Context c, boolean enabled) {
        c.getSharedPreferences(ControlsSpec.PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("gamepad", enabled)
                .apply();
    }

    /** Restores the bundled layout for the current mode (touch or gamepad). */
    public static void reset(Context c) {
        String json = readDefault(c);
        if (!json.isEmpty())
            setLayout(c, json);
    }

    private static String readDefault(Context c) {
        int res = gamepadEnabled(c) ? R.raw.controls_gamepad : R.raw.controls_default;
        try (InputStream is = c.getResources().openRawResource(res)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0)
                out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (IOException e) {
            Logs.add(c, "controls", "cannot read the default layout: " + e);
            return "";
        }
    }
}
