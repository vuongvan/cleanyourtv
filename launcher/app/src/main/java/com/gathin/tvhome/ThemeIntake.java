package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Where a theme file arrives from outside the app (the file picker in Launcher settings, or a phone through
 * ThemeServer): it is checked, put into Config.dir through {@link ThemeImport} or {@link WallpaperImport}, and Home is
 * told to rebuild. All blocking: call it off the main thread.
 */
final class ThemeIntake {
    /** What came in: a theme (config.json or a zip), or one more picture for the wallpaper collection. */
    static final class Result {
        final ThemeImport.Kind kind;
        final int files;
        /** True when it was a settings backup that was restored, not a theme. */
        final boolean restored;

        Result(ThemeImport.Kind kind, int files) {
            this(kind, files, false);
        }

        Result(ThemeImport.Kind kind, int files, boolean restored) {
            this.kind = kind;
            this.files = files;
            this.restored = restored;
        }
    }

    private ThemeIntake() {
    }

    /** Puts {@code source}, which the sender called {@code name}, into the theme. */
    static Result ingest(Context ctx, File source, String name) throws ThemeImport.ImportException {
        File dir = Config.dir(ctx);
        if (dir == null || !(dir.isDirectory() || dir.mkdirs())) throw new ThemeImport.ImportException(ThemeImport.Problem.STORAGE);
        if (source.length() == 0) throw new ThemeImport.ImportException(ThemeImport.Problem.EMPTY);
        ThemeImport.Kind kind = ThemeImport.kind(name);
        try {
            Result result;
            if (kind == ThemeImport.Kind.IMAGE) {
                if (source.length() > ThemeImport.MAX_PHOTO_BYTES) throw new ThemeImport.ImportException(ThemeImport.Problem.TOO_BIG);
                android.util.DisplayMetrics m = ctx.getResources().getDisplayMetrics();
                String saved = WallpaperImport.save(() -> new FileInputStream(source),
                        new File(dir, ThemeFiles.WALLPAPERS),
                        ThemeImport.imageBaseName(name, System.currentTimeMillis()),
                        Math.max(m.widthPixels, m.heightPixels), Math.min(m.widthPixels, m.heightPixels));
                if (saved == null) throw new ThemeImport.ImportException(ThemeImport.Problem.UNREADABLE);
                result = new Result(kind, 1);
            } else if (kind == ThemeImport.Kind.ZIP && SettingsBackup.isBackup(source)) {
                // A settings backup sent like a theme (file picker, phone page): restore it
                result = new Result(ThemeImport.Kind.ZIP, BackupActions.restore(ctx, source), true);
            } else {
                result = new Result(kind, ThemeImport.importFile(source, name, dir).files);
            }
            notifyChanged(ctx);
            return result;
        } catch (ThemeImport.ImportException e) {
            throw e;
        } catch (IOException e) {
            throw new ThemeImport.ImportException(ThemeImport.Problem.UNREADABLE);
        }
    }

    /** Home rebuilds on its next onResume when Launcher settings' change count has moved. */
    static void notifyChanged(Context ctx) {
        SharedPreferences choices = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY, choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    /** The name the file picker shows for {@code uri}, or the last part of the uri when it gives none. */
    static String displayName(ContentResolver resolver, Uri uri) {
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (RuntimeException ignored) {
            // a provider that cannot be asked: fall through to the path
        }
        String last = uri.getLastPathSegment();
        return last == null ? "" : last;
    }

    /** Copies what {@code uri} holds into a temporary file in {@code cacheDir}, refusing more than {@code max} bytes. */
    static File copyToTemp(ContentResolver resolver, Uri uri, File cacheDir, long max) throws ThemeImport.ImportException {
        File temp = new File(cacheDir, "theme-in-" + System.nanoTime() + ".tmp");
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) throw new ThemeImport.ImportException(ThemeImport.Problem.UNREADABLE);
            copy(in, temp, max);
            return temp;
        } catch (ThemeImport.ImportException e) {
            temp.delete();
            throw e;
        } catch (IOException | RuntimeException e) {
            temp.delete();
            throw new ThemeImport.ImportException(ThemeImport.Problem.UNREADABLE);
        }
    }

    /** Writes {@code in} to {@code target}, throwing TOO_BIG (and leaving no file) past {@code max} bytes. */
    static void copy(InputStream in, File target, long max) throws IOException {
        long total = 0;
        try (OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > max) {
                    out.close();
                    target.delete();
                    throw new ThemeImport.ImportException(ThemeImport.Problem.TOO_BIG);
                }
                out.write(buffer, 0, n);
            }
        }
    }

    /** The owner-facing reason for a refused import. */
    static String message(Resources res, ThemeImport.Problem problem) {
        switch (problem) {
            case NOT_JSON:
                return res.getString(R.string.theme_err_not_json);
            case TOO_BIG:
                return res.getString(R.string.theme_err_too_big);
            case EMPTY:
                return res.getString(R.string.theme_err_empty);
            case UNSUPPORTED:
                return res.getString(R.string.theme_err_unsupported);
            case STORAGE:
                return res.getString(R.string.theme_err_storage);
            case NOT_A_BACKUP:
                return res.getString(R.string.backup_err_not_a_backup);
            default:
                return res.getString(R.string.theme_err_unreadable);
        }
    }

    /** What to tell the owner after a successful {@link #ingest}. */
    static String summary(Resources res, Result result) {
        if (result.restored) return res.getString(R.string.backup_restored, result.files);
        return result.kind == ThemeImport.Kind.IMAGE
                ? res.getString(R.string.theme_import_image_ok)
                : res.getString(R.string.theme_import_ok, result.files);
    }
}
