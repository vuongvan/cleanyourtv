package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Android side of {@link SettingsBackup}: makes a backup of this launcher's settings, lists the ones kept in
 * Config.dir/backups, and restores one. All blocking: call it off the main thread.
 */
final class BackupActions {
    private BackupActions() {
    }

    /** The folder backups are kept in, created when needed; null when the app has no storage. */
    static File folder(Context ctx) {
        File dir = Config.dir(ctx);
        return dir == null ? null : new File(dir, SettingsBackup.DIR_NAME);
    }

    /** A new backup, named for the time, with the oldest ones past {@link SettingsBackup#KEEP} removed. */
    static File create(Context ctx) throws ThemeImport.ImportException {
        File folder = folder(ctx);
        File dir = Config.dir(ctx);
        if (folder == null || dir == null) throw new ThemeImport.ImportException(ThemeImport.Problem.STORAGE);
        long now = System.currentTimeMillis();
        File out = new File(folder, "launcher-backup-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date(now)) + ".zip");
        SharedPreferences choices = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        Map<String, Object> prefs = SettingsBackup.pickPrefs(choices.getAll());
        try {
            SettingsBackup.writeZip(out, prefs, ctx.getFilesDir(), dir, now, ctx.getPackageName());
        } catch (java.io.IOException | RuntimeException e) {
            throw new ThemeImport.ImportException(ThemeImport.Problem.STORAGE);
        }
        prune(folder);
        return out;
    }

    /** Backups kept on this TV, newest first. */
    static List<File> list(Context ctx) {
        File folder = folder(ctx);
        List<File> out = new ArrayList<>();
        File[] files = folder == null ? null : folder.listFiles();
        if (files == null) return out;
        for (File f : files) if (f.isFile() && f.getName().endsWith(".zip")) out.add(f);
        Collections.sort(out, (a, b) -> b.getName().compareTo(a.getName()));   // the names carry the time
        return out;
    }

    private static void prune(File folder) {
        File[] files = folder.listFiles();
        if (files == null) return;
        Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
        int kept = 0;
        for (File f : files) {
            if (!f.getName().endsWith(".zip")) continue;
            if (++kept > SettingsBackup.KEEP) f.delete();
        }
    }

    /**
     * Puts a backup back: the files first, then the on-TV choices (those the backup has are set, the others removed),
     * then the theme signature is brought up to date so the restored choices are not reset, and Home is told to rebuild.
     * Nothing is changed unless the whole zip checked out.
     *
     * @return how many files were put back
     */
    static int restore(Context ctx, File zip) throws ThemeImport.ImportException {
        File dir = Config.dir(ctx);
        if (dir == null || !(dir.isDirectory() || dir.mkdirs())) throw new ThemeImport.ImportException(ThemeImport.Problem.STORAGE);
        File staging = new File(dir, ".restore");
        ThemeImport.deleteTree(staging);
        try {
            SettingsBackup.Parsed parsed = SettingsBackup.unpack(zip, staging);
            int placed = SettingsBackup.place(staging, parsed, dir, ctx.getFilesDir());
            SharedPreferences.Editor editor = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit();
            for (String key : SettingsBackup.backedUpKeys()) {
                Object v = parsed.prefs.get(key);
                if (v == null) editor.remove(key);
                else if (v instanceof Integer) editor.putInt(key, (Integer) v);
                else if (v instanceof Boolean) editor.putBoolean(key, (Boolean) v);
                else editor.putString(key, (String) v);
            }
            editor.commit();
            Config.acceptThemeFiles(ctx);
            ThemeIntake.notifyChanged(ctx);
            return placed;
        } finally {
            ThemeImport.deleteTree(staging);
        }
    }
}
