package io.github.factorioandroid;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/** Applies the interface language chosen in the settings (launcher only, not the game). */
public final class I18n {
    private I18n() {
    }

    public static Context wrap(Context base) {
        String lang = Config.language(base);
        if (lang == null || lang.isEmpty() || "system".equals(lang))
            return base;
        Locale locale = new Locale(lang);
        Locale.setDefault(locale);
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.setLocale(locale);
        cfg.setLayoutDirection(locale);
        return base.createConfigurationContext(cfg);
    }
}
