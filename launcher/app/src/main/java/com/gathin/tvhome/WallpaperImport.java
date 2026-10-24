package com.gathin.tvhome;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Turns a picture the owner picked from the TV's storage (Launcher settings, Wallpaper) into a wallpaper: it is
 * decoded at about the screen's size, turned upright by its EXIF tag and saved as a JPEG in the theme's
 * {@code wallpapers/} folder, where the on-TV wallpaper choice already finds pictures. A phone photo of 12 MP or
 * more would otherwise cost a 1 GB TV far more memory than the screen can show (see {@link WallpaperSize}).
 */
final class WallpaperImport {
    /** Every picture saved here starts with this, so the settings list can name it and old ones can be replaced. */
    static final String PREFIX = "user_wallpaper_";

    /** Pictures with more pixels than this are refused before any decoding is tried. */
    private static final long MAX_SOURCE_PIXELS = 200_000_000L;
    private static final int JPEG_QUALITY = 90;

    private WallpaperImport() {
    }

    static boolean isImported(String fileName) {
        return fileName != null && fileName.startsWith(PREFIX);
    }

    /** Somewhere a picture can be read from more than once (its size first, then its pixels, then its EXIF tag). */
    interface Source {
        InputStream open() throws IOException;
    }

    /**
     * Saves the picture at {@code uri} into {@code folder} and removes the ones imported before it. Blocking:
     * call it off the main thread.
     *
     * @return the new file's name, or null when the picture could not be read or saved
     */
    static String save(ContentResolver resolver, Uri uri, File folder, int screenW, int screenH) {
        return save(() -> {
            InputStream in = resolver.openInputStream(uri);
            if (in == null) throw new IOException("no stream for " + uri);
            return in;
        }, folder, null, screenW, screenH);
    }

    /**
     * Saves a picture into {@code folder} as a JPEG scaled to the screen. With a {@code baseName} (a picture sent from
     * a phone) it is kept under that name next to the others in the collection; without one it becomes the owner's
     * single picked wallpaper and replaces the one picked before it.
     *
     * @return the new file's name, or null when the picture could not be read or saved
     */
    static String save(Source source, File folder, String baseName, int screenW, int screenH) {
        Bitmap bitmap = null;
        Bitmap scaled = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = source.open()) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int w = bounds.outWidth, h = bounds.outHeight;
            if (w <= 0 || h <= 0 || (long) w * h > MAX_SOURCE_PIXELS) return null;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = WallpaperSize.sampleSize(w, h, screenW, screenH);
            try (InputStream in = source.open()) {
                bitmap = BitmapFactory.decodeStream(in, null, options);
            }
            if (bitmap == null) return null;

            int degrees = 0;
            try (InputStream in = source.open()) {
                degrees = rotationDegrees(new ExifInterface(in).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL));
            } catch (IOException | RuntimeException ignored) {
                // no readable EXIF: show the picture as it is
            }
            // Cover size for the screen after the turn: the shown picture is cropped to fill it.
            boolean turned = degrees == 90 || degrees == 270;
            int shownW = turned ? bitmap.getHeight() : bitmap.getWidth();
            int shownH = turned ? bitmap.getWidth() : bitmap.getHeight();
            int[] cover = WallpaperSize.coverScale(shownW, shownH, screenW, screenH);
            float factor = cover == null ? 1f : (float) cover[1] / cover[0];
            if (degrees != 0 || factor != 1f) {
                Matrix matrix = new Matrix();
                if (degrees != 0) matrix.postRotate(degrees);
                if (factor != 1f) matrix.postScale(factor, factor);
                scaled = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            }
            Bitmap out = scaled != null ? scaled : bitmap;

            if (!folder.isDirectory() && !folder.mkdirs()) return null;
            String name = baseName != null ? baseName + ".jpg" : PREFIX + System.currentTimeMillis() + ".jpg";
            File target = new File(folder, name);
            File temp = new File(folder, name + ".part");
            try (FileOutputStream fos = new FileOutputStream(temp)) {
                if (!out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)) return null;
            }
            if (!temp.renameTo(target)) {
                temp.delete();
                return null;
            }
            if (baseName == null) deleteOthers(folder, name);
            return name;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        } finally {
            if (scaled != null) scaled.recycle();
            if (bitmap != null) bitmap.recycle();
        }
    }

    /** Clockwise degrees that turn a picture with this EXIF orientation upright (mirrored ones are not undone). */
    static int rotationDegrees(int exifOrientation) {
        switch (exifOrientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                return 90;
            case ExifInterface.ORIENTATION_ROTATE_180:
                return 180;
            case ExifInterface.ORIENTATION_ROTATE_270:
                return 270;
            default:
                return 0;
        }
    }

    private static void deleteOthers(File folder, String keep) {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (f.getName().equals(keep)) continue;
            if (n.startsWith(PREFIX) && (n.endsWith(".jpg") || n.endsWith(".part"))) f.delete();
        }
    }
}
