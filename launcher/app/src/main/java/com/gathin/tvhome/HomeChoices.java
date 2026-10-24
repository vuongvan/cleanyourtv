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
    static final String HERO_TITLE_KEY = "hero_title_delta_sp";
    /**
     * "Replace the stock launcher": HomeKeyService sends the TV back to this launcher whenever a stock launcher's home
     * screen comes up. It is how the TV behaves rather than how Home looks, so "Follow theme" does not clear it.
     */
    static final String STOCK_REDIRECT_KEY = "stock_redirect";

    /** The dock can be lowered by 0 to this many dp (a slider in steps of {@link #DOCK_DOWN_STEP_DP}). */
    static final int DOCK_DOWN_MAX_DP = 240;
    static final int DOCK_DOWN_STEP_DP = 4;

    /**
     * The app name above the dock is made larger or smaller than the theme's size by up to this many sp, in steps of
     * {@link #HERO_TITLE_STEP_SP}. The slider rests in the middle (no change) and moves both ways.
     */
    static final int HERO_TITLE_RANGE_SP = 24;
    static final int HERO_TITLE_STEP_SP = 2;
    /** The smallest the name may become, whatever the theme's size and the slider say. */
    static final int HERO_TITLE_MIN_SP = 12;

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

    /** True when the owner turned on "replace the stock launcher" (Launcher settings); off by default. */
    static boolean stockRedirect(Context ctx) {
        return prefs(ctx).getBoolean(STOCK_REDIRECT_KEY, false);
    }

    /** How far the dock is lowered, in dp; 0 is the stock place. */
    static int dockDownDp(Context ctx) {
        return clampDown(prefs(ctx).getInt(DOCK_DOWN_KEY, 0));
    }

    static int clampDown(int dp) {
        return Math.max(0, Math.min(DOCK_DOWN_MAX_DP, dp));
    }

    /** Change to the app name's size, in sp: negative makes it smaller, 0 keeps the theme's size. */
    static int heroTitleDeltaSp(Context ctx) {
        return clampHeroDelta(prefs(ctx).getInt(HERO_TITLE_KEY, 0));
    }

    static int clampHeroDelta(int deltaSp) {
        return Math.max(-HERO_TITLE_RANGE_SP, Math.min(HERO_TITLE_RANGE_SP, deltaSp));
    }

    /** The slider position (0 to 2 * range / step) of a change: the middle is no change. */
    static int heroTitleProgress(int deltaSp) {
        return (clampHeroDelta(deltaSp) + HERO_TITLE_RANGE_SP) / HERO_TITLE_STEP_SP;
    }

    static int heroTitleDeltaFromProgress(int progress) {
        return clampHeroDelta(progress * HERO_TITLE_STEP_SP - HERO_TITLE_RANGE_SP);
    }

    /** The size to use for a theme size and a change, both in sp. */
    static int heroTitleSp(int themeSp, int deltaSp) {
        return Math.max(HERO_TITLE_MIN_SP, themeSp + clampHeroDelta(deltaSp));
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
