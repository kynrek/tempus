package com.eddyizm.tempus.util;

import android.app.Activity;
import android.content.res.Resources;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Settings > Colors: background and text colours for the main window, the tabs at the top and the
 * control panel (mini player), picked from a palette by name. "default" keeps the theme's colour.
 */
public final class AppColors {
    public static final String MAIN_BG = "color_main_bg";
    public static final String MAIN_TEXT = "color_main_text";
    public static final String TABS_BG = "color_tabs_bg";
    public static final String TABS_TEXT = "color_tabs_text";
    public static final String PANEL_BG = "color_panel_bg";
    public static final String PANEL_TEXT = "color_panel_text";

    private static final Map<String, Integer> BG = new HashMap<>();
    private static final Map<String, Integer> TEXT = new HashMap<>();

    static {
        BG.put("black", 0xFF000000);
        BG.put("charcoal", 0xFF1E2427);
        BG.put("slate", 0xFF263238);
        BG.put("navy", 0xFF0D1B2A);
        BG.put("forest", 0xFF10261C);
        BG.put("burgundy", 0xFF2A1015);
        BG.put("white", 0xFFFFFFFF);
        BG.put("light_gray", 0xFFECEFF1);
        BG.put("sand", 0xFFF3EBDD);
        TEXT.put("white", 0xFFFFFFFF);
        TEXT.put("light_gray", 0xFFCFD8DC);
        TEXT.put("black", 0xFF000000);
        TEXT.put("dark_gray", 0xFF37474F);
        TEXT.put("amber", 0xFFFFC107);
        TEXT.put("teal", 0xFF6FD3C0);
        TEXT.put("sky", 0xFF81D4FA);
    }

    private AppColors() {
    }

    private static String pick(String key, String fallback) {
        return Preferences.getColorChoice(key, fallback);
    }

    /** The chosen colour, or null for the theme's own. The control panel defaults to dark with white text. */
    @Nullable
    public static Integer color(String key) {
        boolean text = key.endsWith("_text");
        String fallback = PANEL_BG.equals(key) ? "charcoal" : PANEL_TEXT.equals(key) ? "white" : "default";
        Integer c = (text ? TEXT : BG).get(pick(key, fallback));
        return c;
    }

    /** Applies the main window's colours as theme overlays; call before setContentView. */
    public static void applyMainWindow(Activity activity) {
        Resources res = activity.getResources();
        String bg = pick(MAIN_BG, "default"), text = pick(MAIN_TEXT, "default");
        if (BG.containsKey(bg)) apply(activity, res.getIdentifier("ThemeOverlay.Tempus.Bg." + bg, "style", activity.getPackageName()));
        if (TEXT.containsKey(text)) apply(activity, res.getIdentifier("ThemeOverlay.Tempus.Text." + text, "style", activity.getPackageName()));
    }

    private static void apply(Activity activity, int style) {
        if (style != 0) activity.getTheme().applyStyle(style, true);
    }
}
