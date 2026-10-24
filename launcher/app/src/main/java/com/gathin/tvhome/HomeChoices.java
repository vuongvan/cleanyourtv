package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Choices made in Launcher settings that belong to the owner rather than to a theme: the lunar date line, showing
 * only the dock, how far down the dock sits and the size of the app name above it. They live in the same preferences file as the other on-TV
 * choices (Config.SETTINGS_NAME) but a theme push never clears them; only "Follow theme" does.
 */
final class HomeChoices {
    static final String LUNAR_KEY = "lunar_date";
    static final String DOCK_ONLY_KEY = "dock_only";
    static final String DOCK_DOWN_KEY = "dock_down_dp";
    static final String HERO_TITLE_KEY = "hero_title_step";

    /** The dock can be lowered by 0 to this many dp (a slider in steps of {@link #DOCK_DOWN_STEP_DP}). */
    static final int DOCK_DOWN_MAX_DP = 240;
    static final int DOCK_DOWN_STEP_DP = 4;

    /** Slider steps for the app name above the dock: 0 keeps the theme's size, step 1 is 24 sp, +2 sp each. */
    static final int HERO_TITLE_STEPS = 36;

    private HomeChoices() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    /** The lunar date under the clock; on unless the owner turned it off. */
    static boolean lunarDate(Context ctx) {
        return prefs(ctx).getBoolean(LUNAR_KEY, true);
    }

    /** Only the dock row is shown: the rows under it are hidden and cannot be reached. Off by default. */
    static boolean dockOnly(Context ctx) {
        return prefs(ctx).getBoolean(DOCK_ONLY_KEY, false);
    }

    /** How far the dock is lowered, in dp; 0 is the stock place. */
    static int dockDownDp(Context ctx) {
        return clampDown(prefs(ctx).getInt(DOCK_DOWN_KEY, 0));
    }

    static int clampDown(int dp) {
        return Math.max(0, Math.min(DOCK_DOWN_MAX_DP, dp));
    }

    /** Slider step (0 to HERO_TITLE_STEPS) of the app name's size. */
    static int heroTitleStep(Context ctx) {
        return clampHeroStep(prefs(ctx).getInt(HERO_TITLE_KEY, 0));
    }

    static int clampHeroStep(int step) {
        return Math.max(0, Math.min(HERO_TITLE_STEPS, step));
    }

    /** The size in sp of a slider step; 0 means the theme's own size. */
    static int heroTitleSp(int step) {
        int s = clampHeroStep(step);
        return s == 0 ? 0 : 24 + 2 * (s - 1);
    }

    /** Forget these choices (Launcher settings, "Follow theme"). */
    static SharedPreferences.Editor reset(SharedPreferences.Editor editor) {
        return editor.remove(LUNAR_KEY).remove(DOCK_ONLY_KEY).remove(DOCK_DOWN_KEY).remove(HERO_TITLE_KEY);
    }

    /** True when any of them has been changed from its default. */
    static boolean anySet(SharedPreferences p) {
        return p.contains(LUNAR_KEY) || p.contains(DOCK_ONLY_KEY) || p.contains(DOCK_DOWN_KEY) || p.contains(HERO_TITLE_KEY);
    }
}
