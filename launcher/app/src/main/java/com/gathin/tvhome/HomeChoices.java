package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Choices made in Launcher settings that belong to the owner rather than to a theme: the lunar date line, showing
 * only the dock, and how far down the dock sits. They live in the same preferences file as the other on-TV
 * choices (Config.SETTINGS_NAME) but a theme push never clears them; only "Follow theme" does.
 */
final class HomeChoices {
    static final String LUNAR_KEY = "lunar_date";
    static final String DOCK_ONLY_KEY = "dock_only";
    static final String DOCK_BOTTOM_KEY = "dock_bottom";

    /**
     * Room kept under the dock band, in dp, for each position the owner can pick: 0 is the stock layout
     * (AppAdapter.BOTTOM_ROOM_DP) and the last one puts the band against the bottom edge of the screen.
     */
    static final int[] DOCK_BOTTOM_ROOM_DP = {30, 18, 8, 0};

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

    /** 0 (stock) to {@code DOCK_BOTTOM_ROOM_DP.length - 1} (against the bottom edge). */
    static int dockBottom(Context ctx) {
        return clampBottom(prefs(ctx).getInt(DOCK_BOTTOM_KEY, 0));
    }

    static int clampBottom(int level) {
        return Math.max(0, Math.min(DOCK_BOTTOM_ROOM_DP.length - 1, level));
    }

    /** The room under the dock for this position, in dp. */
    static float bottomRoomDp(int level) {
        return DOCK_BOTTOM_ROOM_DP[clampBottom(level)];
    }

    /** Forget the three choices (Launcher settings, "Follow theme"). */
    static SharedPreferences.Editor reset(SharedPreferences.Editor editor) {
        return editor.remove(LUNAR_KEY).remove(DOCK_ONLY_KEY).remove(DOCK_BOTTOM_KEY);
    }

    /** True when any of the three has been changed from its default. */
    static boolean anySet(SharedPreferences p) {
        return p.contains(LUNAR_KEY) || p.contains(DOCK_ONLY_KEY) || p.contains(DOCK_BOTTOM_KEY);
    }
}
