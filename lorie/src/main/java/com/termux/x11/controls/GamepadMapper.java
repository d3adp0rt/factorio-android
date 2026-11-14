package com.termux.x11.controls;

import android.view.KeyEvent;
import android.view.MotionEvent;

import com.termux.x11.input.InputStub;

/**
 * Forwards a real gamepad connected to the phone into the X server.
 *
 * <p>Android does not let apps create an evdev/uinput joystick, so the guest can never see a
 * real gamepad device - but the *phone's* controller is delivered to this app, and this class
 * translates it into the keyboard and mouse events Factorio already understands:
 *
 * <pre>
 *   left stick  -> W/A/S/D        right stick -> mouse movement
 *   A -> E (interact)             B -> Esc
 *   X -> Q (pipette)              Y -> R (rotate)
 *   L1 -> Shift                   R1 -> Tab
 *   L2 -> left mouse button       R2 -> right mouse button
 *   Start -> Esc                  Select -> M (map)
 * </pre>
 *
 * Only state changes are sent, so a 60 Hz stick does not flood the X connection.
 */
public final class GamepadMapper {
    private static final float STICK_DEADZONE = 0.35f;
    private static final float MOUSE_SCALE = 14f;

    private final InputStub input;
    private int directionMask;
    private boolean l2, r2;

    public GamepadMapper(InputStub input) {
        this.input = input;
    }

    public boolean handleKey(KeyEvent ev) {
        int code = ev.getKeyCode();
        int key = xKeyFor(code);
        if (key != 0) {
            input.sendKeyEvent(0, key, ev.getAction() == KeyEvent.ACTION_DOWN);
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_L2 || code == KeyEvent.KEYCODE_BUTTON_R2) {
            boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
            if (code == KeyEvent.KEYCODE_BUTTON_L2)
                l2 = down;
            else
                r2 = down;
            input.sendMouseEvent(0, 0, code == KeyEvent.KEYCODE_BUTTON_L2
                    ? InputStub.BUTTON_LEFT : InputStub.BUTTON_RIGHT, down, true);
            return true;
        }
        return false;
    }

    private static int xKeyFor(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A: return KeyEvent.KEYCODE_E;
            case KeyEvent.KEYCODE_BUTTON_B: return KeyEvent.KEYCODE_ESCAPE;
            case KeyEvent.KEYCODE_BUTTON_X: return KeyEvent.KEYCODE_Q;
            case KeyEvent.KEYCODE_BUTTON_Y: return KeyEvent.KEYCODE_R;
            case KeyEvent.KEYCODE_BUTTON_L1: return KeyEvent.KEYCODE_SHIFT_LEFT;
            case KeyEvent.KEYCODE_BUTTON_R1: return KeyEvent.KEYCODE_TAB;
            case KeyEvent.KEYCODE_BUTTON_START: return KeyEvent.KEYCODE_ESCAPE;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return KeyEvent.KEYCODE_M;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return KeyEvent.KEYCODE_SPACE;
            default: return 0;
        }
    }

    public boolean handleMotion(MotionEvent ev) {
        int sources = ev.getSource();
        if ((sources & android.view.InputDevice.SOURCE_JOYSTICK) == 0
                && (sources & android.view.InputDevice.SOURCE_DPAD) == 0)
            return false;

        float x = axis(ev, MotionEvent.AXIS_X);
        float y = axis(ev, MotionEvent.AXIS_Y);
        float hatX = axis(ev, MotionEvent.AXIS_HAT_X);
        float hatY = axis(ev, MotionEvent.AXIS_HAT_Y);
        if (hatX != 0 || hatY != 0) {
            x = hatX;
            y = hatY;
        }
        int mask = 0;
        if (y < -STICK_DEADZONE) mask |= 1;
        if (y > STICK_DEADZONE) mask |= 2;
        if (x < -STICK_DEADZONE) mask |= 4;
        if (x > STICK_DEADZONE) mask |= 8;
        setDirection(mask);

        float rx = axis(ev, MotionEvent.AXIS_RX);
        float ry = axis(ev, MotionEvent.AXIS_RY);
        if (rx != 0 || ry != 0) {
            input.sendMouseEvent(rx * MOUSE_SCALE, ry * MOUSE_SCALE,
                    InputStub.BUTTON_UNDEFINED, false, true);
        }
        return true;
    }

    private void setDirection(int mask) {
        if (mask == directionMask)
            return;
        int[] keys = {KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_S,
                KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_D};
        for (int i = 0; i < 4; i++) {
            boolean was = (directionMask & (1 << i)) != 0;
            boolean now = (mask & (1 << i)) != 0;
            if (was != now)
                input.sendKeyEvent(0, keys[i], now);
        }
        directionMask = mask;
    }

    private static float axis(MotionEvent ev, int axis) {
        return ev.getAxisValue(axis);
    }
}
