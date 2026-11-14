package io.github.factorioandroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.x11.controls.ButtonSpec;
import com.termux.x11.controls.ControlsSpec;
import com.termux.x11.controls.TouchControlsView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Drag & drop editor for the on-screen gamepad.
 *
 * <p>Reuses the runtime overlay ({@link TouchControlsView}) in edit mode, so what you see here
 * is exactly what you get in game: drag a button to move it, tap it to select, then add,
 * delete or change its action. The result is written back as the same JSON document the
 * overlay reads, which is also what makes hand-editing or sharing layouts possible later.
 */
public class ControlsEditorActivity extends Activity {
    private TouchControlsView preview;
    private ButtonSpec selected;
    private boolean dirty;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(I18n.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#101010"));

        TextView hint = new TextView(this);
        hint.setText(R.string.editor_hint);
        hint.setTextColor(Color.LTGRAY);
        hint.setTextSize(12);
        int pad = (int) (10 * getResources().getDisplayMetrics().density);
        hint.setPadding(pad, pad, pad, pad);
        root.addView(hint);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        Button add = new Button(this);
        add.setText(R.string.editor_add);
        add.setOnClickListener(v -> addButtonDialog());
        bar.addView(add);

        Button action = new Button(this);
        action.setText(R.string.editor_action);
        action.setOnClickListener(v -> changeActionDialog());
        bar.addView(action);

        Button del = new Button(this);
        del.setText(R.string.editor_delete);
        del.setOnClickListener(v -> {
            if (preview.selected() == null) {
                Toast.makeText(this, R.string.editor_select_first, Toast.LENGTH_SHORT).show();
                return;
            }
            preview.removeSelected();
            selected = null;
            dirty = true;
        });
        bar.addView(del);

        Button save = new Button(this);
        save.setText(R.string.editor_save);
        save.setOnClickListener(v -> save(true));
        bar.addView(save);

        Button cancel = new Button(this);
        cancel.setText(android.R.string.cancel);
        cancel.setOnClickListener(v -> finish());
        bar.addView(cancel);

        root.addView(bar);

        preview = new TouchControlsView(this, null);
        preview.setInput(null);
        preview.setEditMode(true);
        preview.setLayoutListener(() -> dirty = true);
        root.addView(preview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        // load the current layout into the preview
        new Thread(() -> {
            if (Controls.layout(this).isEmpty())
                Controls.reset(this);
            final String json = Controls.layout(this);
            runOnUiThread(() -> {
                preview.loadLayout(json);
                preview.setInteractive(true);
            });
        }, "editor-load").start();
    }

    @Override
    public void onBackPressed() {
        if (dirty)
            save(false);
        else
            finish();
    }

    private void save(boolean finish) {
        Controls.setLayout(this, preview.toJson());
        Toast.makeText(this, R.string.editor_saved, Toast.LENGTH_SHORT).show();
        dirty = false;
        if (finish)
            finish();
    }

    // ---------------------------------------------------------------- add / edit

    private void addButtonDialog() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        ll.setPadding(pad, pad, pad, pad);

        RadioGroup kind = new RadioGroup(this);
        final RadioButton key = new RadioButton(this);
        key.setText(R.string.editor_kind_key);
        key.setId(1);
        final RadioButton mouse = new RadioButton(this);
        mouse.setText(R.string.editor_kind_mouse);
        mouse.setId(2);
        final RadioButton wheel = new RadioButton(this);
        wheel.setText(R.string.editor_kind_wheel);
        wheel.setId(3);
        kind.addView(key);
        kind.addView(mouse);
        kind.addView(wheel);
        kind.check(1);
        ll.addView(kind);

        final EditText value = new EditText(this);
        value.setHint("E, TAB, SHIFT, F5");
        value.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        ll.addView(value);

        new AlertDialog.Builder(this)
                .setTitle(R.string.editor_add)
                .setView(ll)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String v = value.getText().toString().trim();
                    ButtonSpec b = new ButtonSpec();
                    b.id = "custom-" + System.currentTimeMillis();
                    b.fx = 0.4f;
                    b.fy = 0.4f;
                    b.fw = 0.08f;
                    b.fh = 0.13f;
                    int id = kind.getCheckedRadioButtonId();
                    if (id == 2) {
                        b.action = ControlsSpec.ACTION_MOUSE;
                        b.mouseButton = "right".equalsIgnoreCase(v) ? com.termux.x11.input.InputStub.BUTTON_RIGHT
                                : "middle".equalsIgnoreCase(v) ? com.termux.x11.input.InputStub.BUTTON_MIDDLE
                                : com.termux.x11.input.InputStub.BUTTON_LEFT;
                        b.label = v.isEmpty() ? "LMB" : v.toUpperCase();
                    } else if (id == 3) {
                        b.action = ControlsSpec.ACTION_WHEEL;
                        b.wheel = "-".equals(v) ? -1 : 1;
                        b.label = b.wheel < 0 ? "▲" : "▼";
                    } else {
                        int code = ButtonSpec.keyCode(v);
                        if (code == 0) {
                            Toast.makeText(this, R.string.editor_bad_key, Toast.LENGTH_LONG).show();
                            return;
                        }
                        b.action = ControlsSpec.ACTION_KEY;
                        b.keyCode = code;
                        b.label = v.toUpperCase();
                    }
                    preview.addButton(b);
                    dirty = true;
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void changeActionDialog() {
        ButtonSpec b = preview.selected();
        if (b == null) {
            Toast.makeText(this, R.string.editor_select_first, Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText label = new EditText(this);
        label.setText(b.label);
        label.setHint(R.string.editor_label);

        final EditText key = new EditText(this);
        key.setText(ButtonSpec.keyName(b.keyCode));
        key.setHint(R.string.editor_key_hint);

        final android.widget.CheckBox hold = new android.widget.CheckBox(this);
        hold.setText(R.string.editor_hold);
        hold.setChecked(b.hold);

        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        ll.setPadding(pad, pad, pad, pad);
        TextView l1 = new TextView(this);
        l1.setText(R.string.editor_label);
        ll.addView(l1);
        ll.addView(label);
        TextView l2 = new TextView(this);
        l2.setText(R.string.editor_key_hint);
        ll.addView(l2);
        ll.addView(key);
        ll.addView(hold);

        new AlertDialog.Builder(this)
                .setTitle(R.string.editor_action)
                .setView(ll)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String raw = key.getText().toString().trim();
                    if (!raw.isEmpty()) {
                        int code = ButtonSpec.keyCode(raw);
                        if (code == 0) {
                            Toast.makeText(this, R.string.editor_bad_key, Toast.LENGTH_LONG).show();
                            return;
                        }
                        b.keyCode = code;
                        b.action = ControlsSpec.ACTION_KEY;
                    }
                    if (!label.getText().toString().trim().isEmpty())
                        b.label = label.getText().toString().trim();
                    b.hold = hold.isChecked();
                    preview.invalidate();
                    dirty = true;
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
