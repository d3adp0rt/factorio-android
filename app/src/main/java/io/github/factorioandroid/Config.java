package io.github.factorioandroid;

import android.content.Context;
import android.content.SharedPreferences;

/** Tiny typed wrapper over SharedPreferences used by the launcher. */
public final class Config {
    private static final String NAME = "factorio_cfg";

    public static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    /** "cpu" (llvmpipe) or "virgl" (Mesa virpipe -> virglrenderer -> Mali GLES). */
    public static final String CPU = "cpu";
    public static final String VIRGL = "virgl";

    public static String renderer(Context c) {
        String v = sp(c).getString("renderer", CPU);
        // values used before 0.2.0
        if ("software".equals(v))
            return CPU;
        if ("gpu".equals(v))
            return VIRGL;
        // zink/venus and gl4es were dropped in 0.3.0: Mesa 26 needs VK_EXT_robustness2 for zink
        // and GL4ES cannot compile Factorio's GL 3.3 shaders
        if ("zink".equals(v) || "gl4es".equals(v))
            return VIRGL;
        return v;
    }

    public static void setRenderer(Context c, String v) {
        sp(c).edit().putString("renderer", v).apply();
    }

    public static boolean isGpu(Context c) {
        return !CPU.equals(renderer(c));
    }

    /** Default graphics arguments: 4096 px atlases are far too heavy for llvmpipe. */
    public static final String DEFAULT_EXTRA_ARGS =
            "--max-texture-size=2048 --video-memory-usage=low";

    /** Extra command line arguments for the factorio binary. */
    public static String extraArgs(Context c) {
        return sp(c).getString("extra_args", DEFAULT_EXTRA_ARGS);
    }

    /**
     * X screen scale in percent (100 = native resolution). Higher value = smaller X screen,
     * which is the main performance knob for software rendering: 150 -> ~2.2x fewer pixels.
     */
    public static int displayScale(Context c) {
        return sp(c).getInt("display_scale", 150);
    }

    public static void setDisplayScale(Context c, int v) {
        sp(c).edit().putInt("display_scale", v).apply();
    }

    public static void setExtraArgs(Context c, String v) {
        sp(c).edit().putString("extra_args", v).apply();
    }

    public static boolean audio(Context c) {
        return sp(c).getBoolean("audio", true);
    }

    public static void setAudio(Context c, boolean v) {
        sp(c).edit().putBoolean("audio", v).apply();
    }

    /** Workaround for devices where proot's seccomp acceleration misbehaves. */
    /** Bring settings of already installed versions up to date. */
    private static void migrate(Context c) {
        SharedPreferences p = sp(c);
        int v = p.getInt("cfg_version", 1);
        if (v < 3) {
            SharedPreferences.Editor e = p.edit();
            String cur = p.getString("extra_args", "");
            if (cur.isEmpty() || cur.equals("--max-texture-size=2048"))
                e.putString("extra_args", DEFAULT_EXTRA_ARGS);
            e.putInt("cfg_version", 3);
            e.apply();
        }
        if (v < 5) {
            // 0.2.0 replaced the "software"/"gpu" pair with four explicit backends
            SharedPreferences.Editor e = p.edit();
            String r = p.getString("renderer", CPU);
            if ("software".equals(r))
                e.putString("renderer", CPU);
            else if ("gpu".equals(r))
                e.putString("renderer", VIRGL);
            e.remove("gpu_stage");
            e.putInt("cfg_version", 5);
            e.apply();
        }
    }

    /** "fill" = X screen matches the phone aspect, "fit" = fixed 16:9 letterboxed. */
    public static final String SCREEN_FILL = "fill";
    public static final String SCREEN_FIT = "fit";

    public static String screenMode(Context c) {
        return sp(c).getString("screen_mode", SCREEN_FILL);
    }

    public static void setScreenMode(Context c, String v) {
        sp(c).edit().putString("screen_mode", v).apply();
    }

    /** "system", "ru" or "en" - only the app's own UI, the game has its own setting. */
    public static String language(Context c) {
        return sp(c).getString("language", "system");
    }

    public static void setLanguage(Context c, String v) {
        sp(c).edit().putString("language", v).apply();
    }

    public static boolean noSeccomp(Context c) {
        return sp(c).getBoolean("no_seccomp", false);
    }

    public static void setNoSeccomp(Context c, boolean v) {
        sp(c).edit().putBoolean("no_seccomp", v).apply();
    }

    public static void initDefaults(Context c) {
        migrate(c);
        if (Controls.layout(c).isEmpty())
            Controls.reset(c);
        if (sp(c).getBoolean("defaults_done", false))
            return;
        sp(c).edit()
                .putString("renderer", CPU)
                .putString("extra_args", DEFAULT_EXTRA_ARGS)
                .putInt("display_scale", 150)
                .putBoolean("audio", true)
                .putBoolean("defaults_done", true)
                .apply();
    }
}
