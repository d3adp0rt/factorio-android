package io.github.factorioandroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.graphics.Color;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;

/** Entry point of the app: unpacks the bundled rootfs, starts the X server and the game. */
public class LauncherActivity extends Activity {
    private TextView status;
    private TextView gameInfo;
    private ProgressBar bar;
    private static final int REQ_GAME = 1001;
    private static final int REQ_SAVE = 1002;
    private static final int REQ_MOD = 1003;
    private Button playButton;
    private volatile boolean busy;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(I18n.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Config.initDefaults(this);
        applyLorieDefaults();
        Logs.add(this, "main", "FactorioAndroid " + BuildConfig.VERSION_NAME
                + " | Android " + android.os.Build.VERSION.RELEASE
                + " (API " + android.os.Build.VERSION.SDK_INT + ")"
                + " | " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + " | abi " + String.join(",", android.os.Build.SUPPORTED_ABIS)
                + " | setup=" + Setup.isReady(this));
        setContentView(R.layout.activity_launcher);

        status = findViewById(R.id.status);
        gameInfo = findViewById(R.id.game_info);
        bar = findViewById(R.id.progress);
        playButton = findViewById(R.id.play);

        playButton.setOnClickListener(v -> play());
        findViewById(R.id.settings).setOnClickListener(v -> showSettings());
        findViewById(R.id.log).setOnClickListener(v -> showLog());
        findViewById(R.id.version).setOnClickListener(v -> showAbout());
        updateUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUi();
    }

    private void updateUi() {
        if (busy)
            return;
        updateGameInfo();
        if (Game.isGameRunning()) {
            playButton.setText(R.string.resume);
            status.setText(R.string.running);
        } else if (Game.isXRunning()) {
            playButton.setText(R.string.start_game);
            status.setText(R.string.x_ready);
        } else if (Setup.isReady(this) && GameInstaller.installed(this)) {
            playButton.setText(R.string.play);
            status.setText(R.string.ready);
        } else if (Setup.isReady(this)) {
            playButton.setText(R.string.install_game);
            status.setText(R.string.need_game_archive);
        } else {
            playButton.setText(R.string.install);
            status.setText(R.string.not_installed);
        }
    }

    private void updateGameInfo() {
        if (!GameInstaller.installed(this)) {
            gameInfo.setVisibility(View.GONE);
            return;
        }
        String version = GameInstaller.version(this);
        boolean dlc = GameInstaller.hasExpansion(this);
        String text = (version.isEmpty() ? getString(R.string.game_installed) : "Factorio " + version)
                + "  ·  " + getString(dlc ? R.string.dlc_space_age : R.string.dlc_base_only);
        gameInfo.setText(text);
        gameInfo.setTextColor(dlc ? Color.parseColor("#e8a33d") : Color.parseColor("#8c8270"));
        gameInfo.setVisibility(View.VISIBLE);
    }

    private void progress(String text, float fraction) {
        ui.post(() -> {
            status.setText(text);
            bar.setIndeterminate(fraction < 0);
            if (fraction >= 0)
                bar.setProgress((int) (fraction * 1000));
        });
    }

    private void play() {
        if (busy)
            return;
        if (Setup.isReady(this) && !GameInstaller.installed(this)) {
            pickGameArchive();
            return;
        }
        if (Game.isGameRunning()) {
            openXScreen();
            return;
        }
        busy = true;
        playButton.setEnabled(false);
        bar.setVisibility(View.VISIBLE);
        startService(new Intent(this, GameService.class));

        new Thread(() -> {
            try {
                if (!Setup.isReady(LauncherActivity.this)) {
                    progress("Unpacking bundled Linux environment…", 0f);
                    Setup.run(LauncherActivity.this, (text, fraction) -> progress(text, fraction));
                }
                if (!GameInstaller.installed(LauncherActivity.this)) {
                    ui.post(() -> {
                        busy = false;
                        playButton.setEnabled(true);
                        bar.setVisibility(View.INVISIBLE);
                        updateUi();
                        pickGameArchive();
                    });
                    return;
                }
                progress("Starting X server…", -1f);
                Game.startX(LauncherActivity.this);
                if (!Game.waitForX(LauncherActivity.this, 25000))
                    throw new IOException("X server did not start, see the log");
                progress("Starting Factorio…", -1f);
                ui.post(() -> {
                    openXScreen();
                    ui.postDelayed(() -> {
                        new Thread(() -> {
                            try {
                                Game.startGame(LauncherActivity.this);
                            } catch (Exception e) {
                                ui.post(() -> fail("Cannot start the game: " + e));
                                return;
                            }
                            ui.post(() -> {
                                busy = false;
                                playButton.setEnabled(true);
                                bar.setVisibility(View.INVISIBLE);
                                updateUi();
                            });
                        }, "game-start").start();
                    }, 2500);
                    busy = false;
                    playButton.setEnabled(true);
                });
            } catch (final Exception e) {
                ui.post(() -> fail(e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }, "setup").start();
    }

    private void fail(String message) {
        busy = false;
        playButton.setEnabled(true);
        bar.setVisibility(View.INVISIBLE);
        status.setText(message);
        updateUi();
        new AlertDialog.Builder(this)
                .setTitle(R.string.error)
                .setMessage(message + "\n\n" + getString(R.string.open_log_hint))
                .setPositiveButton(R.string.show_log, (d, w) -> showLog())
                .setNegativeButton(android.R.string.ok, null)
                .show();
    }

    // ------------------------------------------------------------ game / save / mod import

    private void pickGameArchive() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/x-tar", "application/gzip", "application/x-xz",
                "application/zstd", "application/octet-stream"});
        try {
            startActivityForResult(i, REQ_GAME);
        } catch (Exception e) {
            fail("no file picker available: " + e);
        }
    }

    private void pickSave() {
        pickDocument(REQ_SAVE);
    }

    private void pickMod() {
        pickDocument(REQ_MOD);
    }

    private void pickDocument(int request) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, request);
        } catch (Exception e) {
            fail("no file picker available: " + e);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null)
            return;
        final Uri uri = data.getData();
        switch (requestCode) {
            case REQ_GAME:
                installGame(uri);
                break;
            case REQ_SAVE:
                importOne(uri, "saves", "save.zip", R.string.imported_save);
                break;
            case REQ_MOD:
                importOne(uri, "mods", "mod.zip", R.string.imported_mod);
                break;
            default:
                break;
        }
    }

    private void installGame(final Uri uri) {
        if (busy)
            return;
        busy = true;
        playButton.setEnabled(false);
        bar.setVisibility(View.VISIBLE);
        progress(getString(R.string.unpacking_game), 0f);
        new Thread(() -> {
            try {
                GameInstaller.install(LauncherActivity.this, uri,
                        (text, fraction) -> progress(text, fraction));
                ui.post(() -> {
                    busy = false;
                    playButton.setEnabled(true);
                    bar.setVisibility(View.INVISIBLE);
                    updateUi();
                    askDeleteArchive(uri);
                });
            } catch (final Exception e) {
                ui.post(() -> fail(getString(R.string.game_import_failed,
                        e.getMessage() == null ? e.toString() : e.getMessage())));
            }
        }, "game-import").start();
    }

    /** The archive is usually a 1.5 GB file the user no longer needs - offer to remove it. */
    private void askDeleteArchive(final Uri uri) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.installed)
                .setMessage(R.string.delete_archive_question)
                .setPositiveButton(R.string.delete_archive, (d, w) -> deleteArchive(uri))
                .setNegativeButton(R.string.keep_archive, null)
                .show();
    }

    private void deleteArchive(Uri uri) {
        String name = displayName(uri);
        try {
            if (DocumentsContract.deleteDocument(getContentResolver(), uri)) {
                Toast.makeText(this, R.string.archive_deleted, Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (Exception ignored) {
        }
        // most storage providers refuse deletion through SAF
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_archive)
                .setMessage(getString(R.string.delete_archive_manual, name))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor cur = getContentResolver().query(uri, null, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                int idx = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0)
                    return cur.getString(idx);
            }
        } catch (Exception ignored) {
        }
        return uri.toString();
    }

    private void importOne(final Uri uri, final String subdir, final String fallback, final int okMessage) {
        new Thread(() -> {
            try {
                GameInstaller.importFile(LauncherActivity.this, uri, subdir, fallback);
                ui.post(() -> Toast.makeText(this, okMessage, Toast.LENGTH_SHORT).show());
            } catch (final Exception e) {
                ui.post(() -> Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show());
            }
        }, "import-" + subdir).start();
    }

    private void openXScreen() {
        Intent i = new Intent();
        i.setClassName(this, "com.termux.x11.MainActivity");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    private void showLog() {
        String tailText = Logs.tail(this);
        final String text = tailText.isEmpty() ? getString(R.string.log_empty) : tailText;
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextIsSelectable(true);
        tv.setTextSize(9);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(this)
                .setTitle(R.string.log)
                .setView(tv)
                .setPositiveButton(R.string.copy, (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("factorio log", text));
                    Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton(R.string.clear, (d, w) -> Logs.clear(this))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(getString(R.string.about,
                        BuildConfig.VERSION_NAME, getApplicationId(), Config.renderer(this)))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private String getApplicationId() {
        return getPackageName();
    }

    // ------------------------------------------------------------ settings

    /** One row: title, optional widget, and an "i" button explaining what it does. */
    private void section(LinearLayout parent, int title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(getResources().getColor(android.R.color.holo_orange_light));
        tv.setTextSize(13);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (18 * getResources().getDisplayMetrics().density);
        tv.setLayoutParams(lp);
        parent.addView(tv);
    }

    private void infoRow(LinearLayout parent, int text, int help) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.addView(labeled(text), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(infoButton(help));
        parent.addView(row);
    }

    private android.view.View labeled(int text) {
        return labeled(getString(text));
    }

    private android.view.View labeled(CharSequence text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        return tv;
    }

    /** The little circled "i" that opens the help for one setting. */
    private android.view.View infoButton(final int help) {
        Button b = new Button(this);
        b.setText("ⓘ");
        b.setTextSize(15);
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        int p = (int) (4 * getResources().getDisplayMetrics().density);
        b.setPadding(p, p, p, p);
        b.setBackground(null);
        b.setTextColor(getResources().getColor(android.R.color.holo_orange_light));
        b.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.help)
                .setMessage(help)
                .setPositiveButton(android.R.string.ok, null)
                .show());
        return b;
    }

    private void showSettings() {
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);

        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(pad, pad, pad, pad);

        // ---------------------------------------------------------------- game
        section(ll, R.string.settings_section_game);

        Button reinstall = new Button(this);
        reinstall.setText(R.string.replace_game);
        reinstall.setOnClickListener(v -> pickGameArchive());
        ll.addView(reinstall);
        infoRow(ll, R.string.replace_game, R.string.help_replace_game);

        Button importSave = new Button(this);
        importSave.setText(R.string.import_save);
        importSave.setOnClickListener(v -> pickSave());
        ll.addView(importSave);
        infoRow(ll, R.string.import_save, R.string.help_import_save);

        Button importMod = new Button(this);
        importMod.setText(R.string.import_mod);
        importMod.setOnClickListener(v -> pickMod());
        ll.addView(importMod);
        infoRow(ll, R.string.import_mod, R.string.help_import_mod);

        // ---------------------------------------------------------------- graphics
        section(ll, R.string.settings_section_graphics);

        infoRow(ll, R.string.renderer, R.string.help_renderer);
        final String[] backends = {Config.CPU, Config.VIRGL};
        final int[] backendStrings = {R.string.renderer_cpu, R.string.renderer_virgl};
        RadioGroup rg = new RadioGroup(this);
        String current = Config.renderer(this);
        for (int i = 0; i < backends.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(backendStrings[i]);
            rb.setId(10 + i);
            rb.setTag(backends[i]);
            rg.addView(rb);
            if (backends[i].equals(current))
                rg.check(10 + i);
        }
        ll.addView(rg);

        infoRow(ll, R.string.resolution, R.string.help_resolution);
        RadioGroup resGroup = new RadioGroup(this);
        String[] labels = {getString(R.string.res_native), getString(R.string.res_medium),
                getString(R.string.res_small)};
        int[] scales = {100, 150, 200};
        int checked = 1;
        for (int i = 0; i < scales.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(labels[i]);
            rb.setId(100 + scales[i]);
            resGroup.addView(rb);
            if (Config.displayScale(this) == scales[i])
                checked = 100 + scales[i];
        }
        resGroup.check(checked);
        ll.addView(resGroup);

        infoRow(ll, R.string.extra_args, R.string.help_extra_args);
        EditText args = new EditText(this);
        args.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        args.setText(Config.extraArgs(this));
        args.setHint("--max-texture-size=2048 --video-memory-usage=low");
        ll.addView(args);

        // ---------------------------------------------------------------- controls
        section(ll, R.string.settings_section_controls);

        final CheckBox controls = new CheckBox(this);
        controls.setText(R.string.controls_enable);
        controls.setChecked(Controls.enabled(this));
        ll.addView(controls);
        infoRow(ll, R.string.controls_hint_title, R.string.help_controls);

        Button editLayout = new Button(this);
        editLayout.setText(R.string.controls_edit);
        editLayout.setOnClickListener(v -> {
            startActivity(new Intent(this, ControlsEditorActivity.class));
        });
        ll.addView(editLayout);
        infoRow(ll, R.string.controls_edit, R.string.help_controls_edit);

        Button resetControls = new Button(this);
        resetControls.setText(R.string.controls_reset);
        resetControls.setOnClickListener(v -> {
            Controls.reset(this);
            Toast.makeText(this, R.string.controls_reset_done, Toast.LENGTH_SHORT).show();
        });
        ll.addView(resetControls);
        infoRow(ll, R.string.controls_reset, R.string.help_controls_reset);

        final CheckBox gamepad = new CheckBox(this);
        gamepad.setText(R.string.gamepad_enable);
        gamepad.setChecked(Controls.gamepadEnabled(this));
        ll.addView(gamepad);
        infoRow(ll, R.string.gamepad_enable, R.string.help_gamepad);

        // ---------------------------------------------------------------- misc
        section(ll, R.string.settings_section_misc);

        infoRow(ll, R.string.language, R.string.help_language);
        RadioGroup langGroup = new RadioGroup(this);
        final String[] langs = {"system", "ru", "en"};
        String[] langLabels = {getString(R.string.lang_system), "Русский", "English"};
        String curLang = Config.language(this);
        for (int i = 0; i < langs.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(langLabels[i]);
            rb.setId(200 + i);
            rb.setTag(langs[i]);
            langGroup.addView(rb);
            if (langs[i].equals(curLang))
                langGroup.check(200 + i);
        }
        ll.addView(langGroup);

        final CheckBox audio = new CheckBox(this);
        audio.setText(R.string.enable_audio);
        audio.setChecked(Config.audio(this));
        ll.addView(audio);
        infoRow(ll, R.string.enable_audio, R.string.help_audio);

        final CheckBox noSeccomp = new CheckBox(this);
        noSeccomp.setText(R.string.no_seccomp);
        noSeccomp.setChecked(Config.noSeccomp(this));
        ll.addView(noSeccomp);
        infoRow(ll, R.string.no_seccomp, R.string.help_no_seccomp);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(ll);

        new AlertDialog.Builder(this)
                .setTitle(R.string.settings)
                .setView(sv)
                .setPositiveButton(android.R.string.ok, (dlg, w) -> {
                    android.view.View picked = rg.findViewById(rg.getCheckedRadioButtonId());
                    if (picked != null)
                        Config.setRenderer(this, (String) picked.getTag());
                    Config.setExtraArgs(this, args.getText().toString().trim());
                    Controls.setEnabled(this, controls.isChecked());
                    boolean padChanged = gamepad.isChecked() != Controls.gamepadEnabled(this);
                    Controls.setGamepadEnabled(this, gamepad.isChecked());
                    if (padChanged)
                        Controls.reset(this);   // switch to the matching preset
                    Config.setAudio(this, audio.isChecked());
                    Config.setNoSeccomp(this, noSeccomp.isChecked());
                    int id = resGroup.getCheckedRadioButtonId();
                    Config.setDisplayScale(this, id > 0 ? id - 100 : 150);
                    android.view.View lang = langGroup.findViewById(langGroup.getCheckedRadioButtonId());
                    String newLang = lang != null ? (String) lang.getTag() : "system";
                    boolean languageChanged = !newLang.equals(Config.language(this));
                    Config.setLanguage(this, newLang);
                    Toast.makeText(this, R.string.restart_needed, Toast.LENGTH_LONG).show();
                    if (languageChanged)
                        recreate();
                })
                .setNeutralButton(R.string.wipe, (dlg, w) -> confirmWipe())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmWipe() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.wipe)
                .setMessage(R.string.wipe_confirm)
                .setPositiveButton(android.R.string.ok, (d, w) -> new Thread(() -> {
                    Game.stopAll(LauncherActivity.this);
                    Setup.wipe(LauncherActivity.this);
                    ui.post(this::updateUi);
                }).start())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Sensible defaults for the embedded X server (Termux:X11 / Lorie preferences). */
    private void applyLorieDefaults() {
        android.content.SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(this);
        android.content.SharedPreferences.Editor e = p.edit();
        // our own setting wins: smaller X screen = the main speed lever for llvmpipe
        e.putString("displayResolutionMode", "scaled");
        e.putInt("displayScale", Config.displayScale(this));
        putIfAbsent(p, e, "adjustResolution", true);
        putIfAbsent(p, e, "displayStretch", true);
        putIfAbsent(p, e, "fullscreen", true);
        putIfAbsent(p, e, "forceOrientation", "landscape");
        putIfAbsent(p, e, "screenIdleTimeout", "never");
        putIfAbsent(p, e, "touchMode", "1");
        putIfAbsent(p, e, "showAdditionalKbd", true);
        putIfAbsent(p, e, "additionalKbdVisible", false);
        putIfAbsent(p, e, "clipboardEnable", true);
        e.apply();
    }

    private static void putIfAbsent(android.content.SharedPreferences p,
                                    android.content.SharedPreferences.Editor e,
                                    String key, Object value) {
        if (p.contains(key))
            return;
        if (value instanceof Boolean)
            e.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer)
            e.putInt(key, (Integer) value);
        else
            e.putString(key, String.valueOf(value));
    }
}
