package com.termux.x11.controls;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.MotionEvent;
import android.view.View;

import com.termux.x11.input.InputStub;

/**
 * Transparent overlay with the on-screen gamepad.
 *
 * <p>Touches that start on a button are translated into key/mouse events; every other pointer
 * is handed over to the regular touchpad underneath ({@link #setForwardTarget}). Android binds
 * a whole gesture to one child, so both cases are handled here: the view always consumes the
 * gesture and re-dispatches the pointers it does not want as a separate gesture.
 */
public class TouchControlsView extends View {
    private static final int OWNER_FORWARD = 0;
    private static final int OWNER_BUTTON = 1;

    private final SparseIntArray owners = new SparseIntArray();
    private final SparseArray<ButtonSpec> pressed = new SparseArray<>();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();

    private ControlsSpec spec = new ControlsSpec();
    private InputStub input;
    private View forwardTarget;
    private boolean interactive = true;
    /** The touchpad underneath currently owns a forwarded gesture. */
    private boolean padActive;
    /** When true only the "toggle" button is shown, so the player can see the whole screen. */
    private boolean collapsed = false;
    /** Editor mode: dragging a button moves it instead of firing its action. */
    private boolean editMode;
    private ButtonSpec selected;
    private float dragDx, dragDy;
    private final SparseIntArray stickMask = new SparseIntArray();
    private final SparseArray<float[]> stickKnob = new SparseArray<>();

    public interface LayoutListener {
        void onLayoutChanged();
    }

    private LayoutListener layoutListener;

    public TouchControlsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        fill.setColor(Color.argb(64, 255, 255, 255));
        stroke.setColor(Color.argb(140, 255, 255, 255));
        stroke.setStyle(Paint.Style.STROKE);
        text.setColor(Color.argb(230, 255, 255, 255));
        text.setTextAlign(Paint.Align.CENTER);
        reload(false);
    }

    /** Everything the buttons inject goes here (usually the {@code LorieView}). */
    public void setInput(InputStub input) {
        this.input = input;
    }

    /** Where touches outside the buttons are re-dispatched (usually the touchpad's parent). */
    public void setForwardTarget(View target) {
        this.forwardTarget = target;
    }

    /** Hides the whole overlay (e.g. while the X server is not connected). */
    public void setInteractive(boolean interactive) {
        if (this.interactive == interactive)
            return;
        this.interactive = interactive;
        if (!interactive)
            releaseAll();
        setVisibility(interactive ? VISIBLE : GONE);
        invalidate();
    }

    public void setEditMode(boolean edit) {
        editMode = edit;
        if (!edit)
            selected = null;
        releaseAll();
        invalidate();
    }

    public void setLayoutListener(LayoutListener l) {
        layoutListener = l;
    }

    public java.util.List<ButtonSpec> buttons() {
        return spec.buttons;
    }

    public ButtonSpec selected() {
        return selected;
    }

    public void addButton(ButtonSpec b) {
        spec.measure(getWidth(), getHeight());
        spec.buttons.add(b);
        selected = b;
        invalidate();
        if (layoutListener != null)
            layoutListener.onLayoutChanged();
    }

    public void removeSelected() {
        if (selected == null)
            return;
        spec.buttons.remove(selected);
        selected = null;
        invalidate();
        if (layoutListener != null)
            layoutListener.onLayoutChanged();
    }

    public String toJson() {
        return spec.toJson();
    }

    /** Loads a layout document (used by the editor). */
    public void loadLayout(String json) {
        if (json == null || json.isEmpty())
            return;
        if (spec.loadFrom(json)) {
            spec.measure(getWidth(), getHeight());
            invalidate();
        }
    }

    /** Re-reads the layout from the shared preferences. No-op when nothing changed. */
    public void reload(boolean force) {
        ControlsSpec fresh = ControlsSpec.load(getContext());
        if (!force && fresh.enabled == spec.enabled && fresh.raw.equals(spec.raw))
            return;
        releaseAll();
        spec = fresh;
        spec.measure(getWidth(), getHeight());
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        spec.measure(w, h);
    }

    // ------------------------------------------------------------------ touch handling

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (editMode)
            return handleEditTouch(ev);
        if (!interactive || !spec.enabled || spec.buttons.isEmpty())
            return forwardAll(ev);

        final int action = ev.getActionMasked();
        final int index = ev.getActionIndex();
        final int id = ev.getPointerId(index);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                ButtonSpec b = hit(ev.getX(index), ev.getY(index));
                owners.put(id, b != null ? OWNER_BUTTON : OWNER_FORWARD);
                if (b != null)
                    handleDown(b, id);
                forward(ev, action, id);
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < ev.getPointerCount(); i++) {
                    ButtonSpec b = pressed.get(ev.getPointerId(i));
                    if (b != null && ControlsSpec.ACTION_STICK.equals(b.action))
                        updateStick(ev.getPointerId(i), b, ev.getX(i), ev.getY(i), false);
                }
                forward(ev, action, -1);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                // the lifting pointer must stay inside the forwarded event, so forward first
                forward(ev, action, id);
                releasePointer(id);
                owners.delete(id);
                if (action == MotionEvent.ACTION_UP) {
                    owners.clear();
                    padActive = false;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                forward(ev, action, -1);
                owners.clear();
                padActive = false;
                break;
            default:
                return forwardAll(ev);
        }
        return true;
    }

    /** Forwards every pointer of the event to the touchpad, unchanged. */
    private boolean forwardAll(MotionEvent ev) {
        if (forwardTarget == null)
            return false;
        return forwardTarget.dispatchTouchEvent(ev);
    }

    /**
     * Re-dispatches the pointers that did not start on a button. The touchpad sees a coherent
     * gesture of its own: it starts with ACTION_DOWN for its first pointer and ends with
     * ACTION_UP, no matter how many fingers the pad is consuming at the same time.
     */
    private void forward(MotionEvent ev, int action, int actionId) {
        if (forwardTarget == null)
            return;

        if (action == MotionEvent.ACTION_CANCEL) {
            if (!padActive)
                return;
            int n = 0;
            int[] idx = new int[ev.getPointerCount()];
            for (int i = 0; i < ev.getPointerCount(); i++)
                idx[n++] = i;
            dispatchSubset(ev, action, idx, n, 0);
            return;
        }

        int count = ev.getPointerCount();
        int[] idx = new int[count];
        int n = 0;
        int actionPos = -1;
        for (int i = 0; i < count; i++) {
            int pid = ev.getPointerId(i);
            if (owners.get(pid, OWNER_FORWARD) != OWNER_FORWARD)
                continue;
            if (pid == actionId)
                actionPos = n;
            idx[n++] = i;
        }
        if (n == 0)
            return;

        if (actionPos < 0) {
            // the pointer that changed belongs to the pad layer: the touchpad just sees motion
            if (padActive)
                dispatchSubset(ev, MotionEvent.ACTION_MOVE, idx, n, 0);
            return;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
                dispatchSubset(ev, action, idx, n, actionPos);
                padActive = action != MotionEvent.ACTION_UP
                        && !(action == MotionEvent.ACTION_POINTER_UP && n <= 1);
                break;
            default:
                if (padActive)
                    dispatchSubset(ev, MotionEvent.ACTION_MOVE, idx, n, 0);
                break;
        }
    }

    private void dispatchSubset(MotionEvent src, int action, int[] idx, int n, int actionPos) {
        if (forwardTarget == null || n <= 0)
            return;
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[n];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[n];
        for (int i = 0; i < n; i++) {
            props[i] = new MotionEvent.PointerProperties();
            coords[i] = new MotionEvent.PointerCoords();
            src.getPointerProperties(idx[i], props[i]);
            src.getPointerCoords(idx[i], coords[i]);
        }
        int a = action;
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            if (n == 1)
                a = action == MotionEvent.ACTION_POINTER_DOWN ? MotionEvent.ACTION_DOWN
                        : MotionEvent.ACTION_UP;
            else
                a = action | (actionPos << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        }
        MotionEvent sub = MotionEvent.obtain(src.getDownTime(), src.getEventTime(), a, n, props, coords,
                src.getMetaState(), src.getButtonState(), src.getXPrecision(), src.getYPrecision(),
                src.getDeviceId(), src.getEdgeFlags(), src.getSource(), src.getFlags());
        try {
            forwardTarget.dispatchTouchEvent(sub);
        } finally {
            sub.recycle();
        }
    }

    private boolean handleEditTouch(MotionEvent ev) {
        int action = ev.getActionMasked();
        int index = ev.getActionIndex();
        float x = ev.getX(index);
        float y = ev.getY(index);
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                ButtonSpec b = hit(x, y);
                selected = b;
                if (b != null) {
                    dragDx = x - b.rect.centerX();
                    dragDy = y - b.rect.centerY();
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (selected == null)
                    return true;
                int w = getWidth() == 0 ? 1 : getWidth();
                int h = getHeight() == 0 ? 1 : getHeight();
                float fx = (x - dragDx) / w - selected.fw / 2f;
                float fy = (y - dragDy) / h - selected.fh / 2f;
                selected.fx = Math.max(0f, Math.min(1f - selected.fw, fx));
                selected.fy = Math.max(0f, Math.min(1f - selected.fh, fy));
                selected.measure(w, h);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (selected != null && layoutListener != null)
                    layoutListener.onLayoutChanged();
                return true;
            default:
                return true;
        }
    }

    /** Direction mask (1 up, 2 down, 4 left, 8 right) for a point inside a stick. */
    private static int stickDirection(ButtonSpec b, float x, float y) {
        float cx = b.rect.centerX();
        float cy = b.rect.centerY();
        float rx = Math.max(1f, b.rect.width() / 2f);
        float ry = Math.max(1f, b.rect.height() / 2f);
        float dx = (x - cx) / rx;
        float dy = (y - cy) / ry;
        final float dead = 0.28f;
        int mask = 0;
        if (dy < -dead) mask |= 1;
        if (dy > dead) mask |= 2;
        if (dx < -dead) mask |= 4;
        if (dx > dead) mask |= 8;
        return mask;
    }

    private void updateStick(int pointerId, ButtonSpec b, float x, float y, boolean release) {
        int now = release ? 0 : stickDirection(b, x, y);
        int prev = stickMask.get(pointerId, 0);
        if (input != null) {
            for (int i = 0; i < 4; i++) {
                int bit = 1 << i;
                boolean was = (prev & bit) != 0;
                boolean is = (now & bit) != 0;
                if (was != is && b.stickCodes[i] != 0)
                    input.sendKeyEvent(0, b.stickCodes[i], is);
            }
        }
        if (release) {
            stickMask.delete(pointerId);
            stickKnob.remove(pointerId);
        } else {
            stickMask.put(pointerId, now);
            stickKnob.put(pointerId, new float[]{x, y});
        }
        invalidate();
    }

    private ButtonSpec hit(float x, float y) {
        for (int i = spec.buttons.size() - 1; i >= 0; i--) {
            ButtonSpec b = spec.buttons.get(i);
            if (collapsed && !ControlsSpec.ACTION_TOGGLE.equals(b.action))
                continue;
            if (b.contains(x, y))
                return b;
        }
        return null;
    }

    private void handleDown(ButtonSpec b, int pointerId) {
        if (ControlsSpec.ACTION_TOGGLE.equals(b.action)) {
            collapsed = !collapsed;
            invalidate();
            return;
        }
        pressed.put(pointerId, b);
        invalidate();
        if (ControlsSpec.ACTION_STICK.equals(b.action)) {
            updateStick(pointerId, b, b.rect.centerX(), b.rect.centerY(), false);
            return;
        }
        if (input == null)
            return;
        switch (b.action) {
            case ControlsSpec.ACTION_KEY:
                input.sendKeyEvent(0, b.keyCode, true);
                if (!b.hold)
                    input.sendKeyEvent(0, b.keyCode, false);
                break;
            case ControlsSpec.ACTION_MOUSE:
                input.sendMouseEvent(0, 0, b.mouseButton, true, true);
                if (!b.hold)
                    input.sendMouseEvent(0, 0, b.mouseButton, false, true);
                break;
            case ControlsSpec.ACTION_WHEEL:
                input.sendMouseWheelEvent(0, b.wheel);
                break;
            default:
                break;
        }
    }

    private void releasePointer(int pointerId) {
        ButtonSpec b = pressed.get(pointerId);
        owners.delete(pointerId);
        if (b == null)
            return;
        pressed.remove(pointerId);
        invalidate();
        if (ControlsSpec.ACTION_STICK.equals(b.action)) {
            updateStick(pointerId, b, 0, 0, true);
            return;
        }
        if (input == null)
            return;
        if (ControlsSpec.ACTION_KEY.equals(b.action) && b.hold)
            input.sendKeyEvent(0, b.keyCode, false);
        else if (ControlsSpec.ACTION_MOUSE.equals(b.action) && b.hold)
            input.sendMouseEvent(0, 0, b.mouseButton, false, true);
    }

    private void releaseAll() {
        int[] ids = new int[pressed.size()];
        for (int i = 0; i < pressed.size(); i++)
            ids[i] = pressed.keyAt(i);
        for (int id : ids)
            releasePointer(id);
        pressed.clear();
        owners.clear();
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        releaseAll();
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        if (!spec.enabled)
            return;
        float density = getResources().getDisplayMetrics().density;
        stroke.setStrokeWidth(1.5f * density);
        float radius = 12 * density;
        for (ButtonSpec b : spec.buttons) {
            if (collapsed && !ControlsSpec.ACTION_TOGGLE.equals(b.action))
                continue;
            if (ControlsSpec.ACTION_STICK.equals(b.action)) {
                drawStick(canvas, b, radius, density);
                continue;
            }
            boolean isPressed = isPressed(b);
            if (editMode && b == selected) {
                stroke.setColor(Color.argb(255, 255, 200, 80));
                fill.setColor(Color.argb(90, 255, 200, 80));
                tmp.set(b.rect);
                canvas.drawRoundRect(tmp, radius, radius, fill);
                canvas.drawRoundRect(tmp, radius, radius, stroke);
                stroke.setColor(Color.argb(120, 255, 255, 255));
            }
            fill.setColor(isPressed ? Color.argb(150, 255, 255, 255) : Color.argb(56, 255, 255, 255));
            stroke.setColor(isPressed ? Color.argb(230, 255, 255, 255) : Color.argb(120, 255, 255, 255));
            tmp.set(b.rect);
            canvas.drawRoundRect(tmp, radius, radius, fill);
            canvas.drawRoundRect(tmp, radius, radius, stroke);

            if (!b.label.isEmpty()) {
                float size = Math.min(tmp.height() * 0.42f, tmp.width() * 0.36f);
                size = Math.min(size, 22 * density);
                size = Math.max(size, 9 * density);
                text.setTextSize(size);
                text.setColor(isPressed ? Color.WHITE : Color.argb(230, 255, 255, 255));
                Paint.FontMetrics fm = text.getFontMetrics();
                float baseline = tmp.centerY() - (fm.ascent + fm.descent) / 2f;
                canvas.drawText(b.label, tmp.centerX(), baseline, text);
            }
        }
    }

    private void drawStick(Canvas canvas, ButtonSpec b, float radius, float density) {
        float r = Math.min(b.rect.width(), b.rect.height()) / 2f;
        float cx = b.rect.centerX();
        float cy = b.rect.centerY();
        fill.setColor(Color.argb(40, 255, 255, 255));
        stroke.setColor(editMode && b == selected ? Color.argb(255, 255, 200, 80)
                : Color.argb(120, 255, 255, 255));
        canvas.drawCircle(cx, cy, r, fill);
        canvas.drawCircle(cx, cy, r, stroke);
        // knob follows the finger of the pointer that holds this stick
        float kx = cx, ky = cy;
        for (int i = 0; i < pressed.size(); i++) {
            int pid = pressed.keyAt(i);
            if (pressed.valueAt(i) != b)
                continue;
            float[] pos = stickKnob.get(pid);
            if (pos != null) {
                float mx = Math.max(-r + r * 0.35f, Math.min(r - r * 0.35f, pos[0] - cx));
                float my = Math.max(-r + r * 0.35f, Math.min(r - r * 0.35f, pos[1] - cy));
                kx = cx + mx;
                ky = cy + my;
            }
        }
        fill.setColor(Color.argb(150, 255, 255, 255));
        canvas.drawCircle(kx, ky, r * 0.34f, fill);
        stroke.setColor(Color.argb(200, 255, 255, 255));
        canvas.drawCircle(kx, ky, r * 0.34f, stroke);
    }

    private boolean isPressed(ButtonSpec b) {
        for (int i = 0; i < pressed.size(); i++) {
            if (pressed.valueAt(i) == b)
                return true;
        }
        return false;
    }
}
