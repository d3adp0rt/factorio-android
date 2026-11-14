package com.termux.x11.controls;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Declarative description of the on-screen controls: a flat list of buttons, each with a
 * position (fractions of the screen, so one layout fits every resolution) and an action.
 *
 * <p>The layout is written by the app (see {@code io.github.factorioandroid.Controls}) into a
 * dedicated SharedPreferences file, this class only reads it. Keeping it JSON means a future
 * layout editor only has to edit the same document.
 *
 * <pre>
 * { "version": 1,
 *   "buttons": [
 *     {"id":"w","label":"W","x":0.085,"y":0.60,"w":0.07,"h":0.13,"action":"key","key":"W"},
 *     {"id":"lmb","label":"LMB","x":0.90,"y":0.72,"w":0.085,"h":0.20,"action":"mouse","button":"left"},
 *     {"id":"lmb2","label":"LMB","x":..,"action":"mouse","button":"left","hold":true},
 *     {"id":"wheelup","label":"▲","x":..,"action":"wheel","delta":-1},
 *     {"id":"hide","label":"≡","x":..,"action":"toggle"}
 *   ] }
 * </pre>
 */
public final class ControlsSpec {
    /** SharedPreferences file shared with the app. */
    public static final String PREFS = "factorio_controls";
    public static final String KEY_LAYOUT = "layout";
    public static final String KEY_ENABLED = "enabled";

    public static final String ACTION_KEY = "key";
    public static final String ACTION_MOUSE = "mouse";
    public static final String ACTION_WHEEL = "wheel";
    public static final String ACTION_TOGGLE = "toggle";
    /** Analogue-looking stick: four directions mapped to keys (up, down, left, right). */
    public static final String ACTION_STICK = "stick";

    public final List<ButtonSpec> buttons = new ArrayList<>();
    public boolean enabled = true;
    /** The JSON the spec was parsed from, empty when nothing was configured. */
    public String raw = "";

    public static ControlsSpec load(Context c) {
        ControlsSpec spec = new ControlsSpec();
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        spec.enabled = p.getBoolean(KEY_ENABLED, true);
        spec.raw = p.getString(KEY_LAYOUT, "");
        if (!spec.raw.isEmpty()) {
            try {
                spec.parse(new JSONObject(spec.raw));
            } catch (Exception e) {
                // a broken layout must not break the display: fall back to "no buttons"
                android.util.Log.w("ControlsSpec", "bad layout json", e);
                spec.buttons.clear();
            }
        }
        return spec;
    }

    public static void save(Context c, boolean enabled, String json) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, enabled)
                .putString(KEY_LAYOUT, json)
                .apply();
    }

    private void parse(JSONObject o) throws Exception {
        JSONArray arr = o.optJSONArray("buttons");
        if (arr == null)
            return;
        for (int i = 0; i < arr.length(); i++) {
            ButtonSpec b = ButtonSpec.parse(arr.getJSONObject(i));
            if (b != null)
                buttons.add(b);
        }
    }

    /** Serialises the whole layout (used by the layout editor). */
    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("version", 1);
            JSONArray arr = new JSONArray();
            for (ButtonSpec b : buttons)
                arr.put(b.toJson());
            o.put("buttons", arr);
            return o.toString();
        } catch (Exception e) {
            return raw;
        }
    }

    /** Replaces the button list from a JSON document; keeps the old list on parse errors. */
    public boolean loadFrom(String json) {
        ControlsSpec parsed = new ControlsSpec();
        try {
            parsed.parse(new JSONObject(json));
        } catch (Exception e) {
            return false;
        }
        buttons.clear();
        buttons.addAll(parsed.buttons);
        raw = json;
        return true;
    }

    /** Recomputes pixel geometry for the given view size. */
    public void measure(int width, int height) {
        for (ButtonSpec b : buttons)
            b.measure(width, height);
    }
}
