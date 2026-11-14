package com.termux.x11.controls;

import android.graphics.RectF;
import android.view.KeyEvent;

import com.termux.x11.input.InputStub;

import org.json.JSONObject;

/** One on-screen button: where it is and what it does. */
public final class ButtonSpec {
    public String id = "";
    public String label = "";
    public String action = ControlsSpec.ACTION_KEY;

    /** Fractional geometry (0..1 of the view size); the centre is (x + w/2, y + h/2). */
    public float fx, fy, fw, fh;
    /** Pixel geometry, filled in by {@link #measure(int, int)}. */
    public final RectF rect = new RectF();

    /** Resolved key code for {@link ControlsSpec#ACTION_KEY}. */
    public int keyCode = 0;
    /** InputStub.BUTTON_* for {@link ControlsSpec#ACTION_MOUSE}. */
    public int mouseButton = InputStub.BUTTON_LEFT;
    /** true = press and hold while the finger is down, false = single click. */
    public boolean hold = false;
    /** Wheel delta for {@link ControlsSpec#ACTION_WHEEL}. */
    public float wheel = 0;
    /** Four directions of {@link ControlsSpec#ACTION_STICK}, as key names. */
    public String[] stickKeys = {"", "", "", ""};
    /** Resolved stick key codes in the same order: up, down, left, right. */
    public final int[] stickCodes = new int[4];

    static ButtonSpec parse(JSONObject o) {
        ButtonSpec b = new ButtonSpec();
        b.id = o.optString("id", "");
        b.label = o.optString("label", "");
        b.action = o.optString("action", ControlsSpec.ACTION_KEY);
        b.fx = (float) o.optDouble("x", 0);
        b.fy = (float) o.optDouble("y", 0);
        b.fw = (float) o.optDouble("w", 0.08);
        b.fh = (float) o.optDouble("h", 0.12);
        b.hold = o.optBoolean("hold", false);
        b.wheel = (float) o.optDouble("delta", 0);

        switch (b.action) {
            case ControlsSpec.ACTION_KEY:
                b.keyCode = keyCode(o.optString("key", ""));
                if (b.keyCode == 0)
                    return null;
                if (b.label.isEmpty())
                    b.label = o.optString("key", "");
                break;
            case ControlsSpec.ACTION_MOUSE:
                b.mouseButton = mouseButton(o.optString("button", "left"));
                if (b.label.isEmpty())
                    b.label = o.optString("button", "left");
                break;
            case ControlsSpec.ACTION_WHEEL:
                if (b.wheel == 0)
                    b.wheel = o.optBoolean("up", true) ? -1 : 1;
                break;
            case ControlsSpec.ACTION_TOGGLE:
                if (b.label.isEmpty())
                    b.label = "≡";
                break;
            case ControlsSpec.ACTION_STICK: {
                String[] names = {"up", "down", "left", "right"};
                String[] def = {"W", "S", "A", "D"};
                org.json.JSONObject keys = o.optJSONObject("keys");
                for (int i = 0; i < 4; i++) {
                    String name = keys != null ? keys.optString(names[i], def[i]) : def[i];
                    b.stickKeys[i] = name;
                    b.stickCodes[i] = keyCode(name);
                }
                break;
            }
            default:
                return null;
        }
        if (b.fw <= 0 || b.fh <= 0)
            return null;
        return b;
    }

    private static int mouseButton(String name) {
        switch (name) {
            case "right":
                return InputStub.BUTTON_RIGHT;
            case "middle":
                return InputStub.BUTTON_MIDDLE;
            case "left":
            default:
                return InputStub.BUTTON_LEFT;
        }
    }

    /**
     * Resolves a key name: a single letter/digit, one of a few named keys, or a raw
     * {@code KEYCODE_xxx} constant name.
     */
    public static int keyCode(String name) {
        if (name == null || name.isEmpty())
            return 0;
        if (name.length() == 1) {
            char c = Character.toUpperCase(name.charAt(0));
            if (c >= 'A' && c <= 'Z')
                return KeyEvent.KEYCODE_A + (c - 'A');
            if (c >= '0' && c <= '9')
                return KeyEvent.KEYCODE_0 + (c - '0');
        }
        switch (name.toUpperCase()) {
            case "ESC":
            case "ESCAPE":
                return KeyEvent.KEYCODE_ESCAPE;
            case "TAB":
                return KeyEvent.KEYCODE_TAB;
            case "SPACE":
                return KeyEvent.KEYCODE_SPACE;
            case "ENTER":
                return KeyEvent.KEYCODE_ENTER;
            case "SHIFT":
                return KeyEvent.KEYCODE_SHIFT_LEFT;
            case "CTRL":
                return KeyEvent.KEYCODE_CTRL_LEFT;
            case "ALT":
                return KeyEvent.KEYCODE_ALT_LEFT;
            case "BACKSPACE":
                return KeyEvent.KEYCODE_DEL;
            case "UP":
                return KeyEvent.KEYCODE_DPAD_UP;
            case "DOWN":
                return KeyEvent.KEYCODE_DPAD_DOWN;
            case "LEFT":
                return KeyEvent.KEYCODE_DPAD_LEFT;
            case "RIGHT":
                return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "PLUS":
                return KeyEvent.KEYCODE_PLUS;
            case "MINUS":
                return KeyEvent.KEYCODE_MINUS;
            case "COMMA":
                return KeyEvent.KEYCODE_COMMA;
            case "PERIOD":
                return KeyEvent.KEYCODE_PERIOD;
            default:
                break;
        }
        if (name.startsWith("KEYCODE_")) {
            try {
                return KeyEvent.class.getField(name).getInt(null);
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    /** Serialises back to the layout JSON (used by the layout editor). */
    public org.json.JSONObject toJson() {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("id", id);
            o.put("label", label);
            o.put("action", action);
            o.put("x", round(fx));
            o.put("y", round(fy));
            o.put("w", round(fw));
            o.put("h", round(fh));
            switch (action) {
                case ControlsSpec.ACTION_KEY:
                    o.put("key", keyName(keyCode));
                    break;
                case ControlsSpec.ACTION_MOUSE:
                    o.put("button", mouseButton == InputStub.BUTTON_RIGHT ? "right"
                            : mouseButton == InputStub.BUTTON_MIDDLE ? "middle" : "left");
                    break;
                case ControlsSpec.ACTION_WHEEL:
                    o.put("delta", wheel);
                    break;
                case ControlsSpec.ACTION_STICK: {
                    org.json.JSONObject keys = new org.json.JSONObject();
                    String[] names = {"up", "down", "left", "right"};
                    for (int i = 0; i < 4; i++)
                        keys.put(names[i], stickKeys[i]);
                    o.put("keys", keys);
                    break;
                }
                default:
                    break;
            }
            if (hold)
                o.put("hold", true);
            return o;
        } catch (Exception e) {
            return new org.json.JSONObject();
        }
    }

    private static double round(float v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /** Best effort reverse mapping, only used when the editor saves. */
    public static String keyName(int code) {
        if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z)
            return String.valueOf((char) ('A' + (code - KeyEvent.KEYCODE_A)));
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9)
            return String.valueOf((char) ('0' + (code - KeyEvent.KEYCODE_0)));
        switch (code) {
            case KeyEvent.KEYCODE_ESCAPE: return "ESC";
            case KeyEvent.KEYCODE_TAB: return "TAB";
            case KeyEvent.KEYCODE_SPACE: return "SPACE";
            case KeyEvent.KEYCODE_ENTER: return "ENTER";
            case KeyEvent.KEYCODE_SHIFT_LEFT: return "SHIFT";
            case KeyEvent.KEYCODE_CTRL_LEFT: return "CTRL";
            case KeyEvent.KEYCODE_ALT_LEFT: return "ALT";
            case KeyEvent.KEYCODE_DEL: return "BACKSPACE";
            case KeyEvent.KEYCODE_DPAD_UP: return "UP";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "DOWN";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "LEFT";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "RIGHT";
            default: return "";
        }
    }

    void measure(int width, int height) {
        rect.set(fx * width, fy * height, (fx + fw) * width, (fy + fh) * height);
    }

    boolean contains(float x, float y) {
        return rect.contains(x, y);
    }
}
