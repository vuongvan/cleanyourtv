package com.gathin.tvhome;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Puts a theme into Config.dir without ADB: a {@code config.json}, or a {@code .zip} holding the same files the theme
 * tools push (config.json, wallpaper.jpg, wallpapers/*, wallpapers/captions.txt). Only the names ThemeFiles accepts
 * are ever written, so an entry such as "../x" cannot leave the theme folder. A zip is unpacked into a staging
 * folder and checked first, so a bad file changes nothing. No Android types except org.json, so it is tested on the JVM.
 */
final class ThemeImport {
    enum Problem { NOT_JSON, TOO_BIG, EMPTY, UNREADABLE, UNSUPPORTED, STORAGE, NOT_A_BACKUP }

    /** Why an import was refused; {@link #problem} says which, for the message shown to the owner. */
    static final class ImportException extends IOException {
        final Problem problem;

        ImportException(Problem problem) {
            super(problem.name());
            this.problem = problem;
        }
    }

    /** What an import wrote: how many files, and whether config.json was one of them. */
    static final class Outcome {
        final int files;
        final boolean config;

        Outcome(int files, boolean config) {
            this.files = files;
            this.config = config;
        }
    }

    enum Kind { CONFIG, ZIP, IMAGE, UNKNOWN }

    static final long MAX_CONFIG_BYTES = 64 * 1024;          // Config.read ignores a larger config.json
    static final long MAX_TEXT_BYTES = 256 * 1024;           // captions.txt
    static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;   // WallpaperRotation ignores a larger picture
    static final long MAX_ZIP_BYTES = 60L * 1024 * 1024;
    static final long MAX_ZIP_TOTAL_BYTES = 100L * 1024 * 1024;   // all entries together, once unpacked
    static final long MAX_PHOTO_BYTES = 30L * 1024 * 1024;   // a picture sent on its own is scaled down on arrival
    static final int MAX_ZIP_ENTRIES = 400;
    static final String STAGING = ".import";

    private ThemeImport() {
    }

    static Kind kind(String name) {
        if (name == null) return Kind.UNKNOWN;
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".json")) return Kind.CONFIG;
        if (n.endsWith(".zip")) return Kind.ZIP;
        if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")) return Kind.IMAGE;
        return Kind.UNKNOWN;
    }

    /**
     * A file name a picture sent from a phone can be kept under in wallpapers/ (the extension is dropped: the picture
     * is saved again as a JPEG). Only letters, digits, "_" and "-" survive, so ThemeFiles.collectionImage accepts it.
     */
    static String imageBaseName(String name, long fallbackStamp) {
        String base = name == null ? "" : name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        base = base.replaceAll("[^A-Za-z0-9_-]+", "_").replaceAll("^_+|_+$", "");
        if (base.length() > 48) base = base.substring(0, 48);
        return base.isEmpty() ? "photo_" + fallbackStamp : base;
    }

    /** The largest file of this kind worth accepting. */
    static long maxBytes(Kind kind) {
        switch (kind) {
            case CONFIG:
                return MAX_CONFIG_BYTES;
            case ZIP:
                return MAX_ZIP_BYTES;
            case IMAGE:
                return MAX_PHOTO_BYTES;
            default:
                return 0;
        }
    }

    /** Imports a config.json or a theme zip that is already a file on disk. */
    static Outcome importFile(File source, String name, File dir) throws IOException {
        switch (kind(name)) {
            case CONFIG:
                return importConfig(source, dir);
            case ZIP:
                return importZip(source, dir);
            default:
                throw new ImportException(Problem.UNSUPPORTED);
        }
    }

    static Outcome importConfig(File source, File dir) throws IOException {
        if (source.length() > MAX_CONFIG_BYTES) throw new ImportException(Problem.TOO_BIG);
        byte[] bytes = readAll(source, MAX_CONFIG_BYTES);
        validateConfig(bytes);
        File staged = new File(dir, Config.FILE_NAME + ".part");
        try (OutputStream out = new FileOutputStream(staged)) {
            out.write(bytes);
        } catch (IOException e) {
            staged.delete();
            throw new ImportException(Problem.STORAGE);
        }
        if (!staged.renameTo(new File(dir, Config.FILE_NAME))) {
            staged.delete();
            throw new ImportException(Problem.STORAGE);
        }
        return new Outcome(1, true);
    }

    static Outcome importZip(File zip, File dir) throws IOException {
        if (zip.length() > MAX_ZIP_BYTES) throw new ImportException(Problem.TOO_BIG);
        File staging = new File(dir, STAGING);
        deleteTree(staging);
        if (!staging.mkdirs()) throw new ImportException(Problem.STORAGE);
        List<String> staged = new ArrayList<>();
        try {
            unpack(zip, staging, staged);
            if (staged.isEmpty()) throw new ImportException(Problem.EMPTY);
            if (staged.contains(Config.FILE_NAME)) {
                validateConfig(readAll(new File(staging, Config.FILE_NAME), MAX_CONFIG_BYTES));
            }
            return place(staging, dir, staged);
        } finally {
            deleteTree(staging);
        }
    }

    /** Unpacks the entries ThemeFiles accepts into {@code staging}, noting their relative paths in {@code staged}. */
    private static void unpack(File zip, File staging, List<String> staged) throws IOException {
        long total = 0;
        int entries = 0;
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES) throw new ImportException(Problem.TOO_BIG);
                if (entry.isDirectory()) continue;
                String path = themePath(entry.getName());
                if (path == null) continue;   // not a theme file: skipped, never written
                long limit = limitOf(path);
                File target = new File(staging, path);
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new ImportException(Problem.STORAGE);
                long written = 0;
                try (OutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[16 * 1024];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        written += n;
                        total += n;
                        if (written > limit || total > MAX_ZIP_TOTAL_BYTES) throw new ImportException(Problem.TOO_BIG);
                        out.write(buffer, 0, n);
                    }
                } catch (ImportException e) {
                    throw e;
                } catch (IOException e) {
                    throw new ImportException(Problem.STORAGE);
                }
                if (written == 0) {
                    target.delete();
                    continue;
                }
                staged.add(path);
            }
            // ZipInputStream reads a file that is not a zip as a zip with no entries: that is not an empty theme
            if (entries == 0) throw new ImportException(Problem.UNREADABLE);
        } catch (ImportException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new ImportException(Problem.UNREADABLE);   // not a zip, or a damaged one
        }
    }

    /**
     * The theme-relative path of a zip entry, or null when it is not a file of a theme. A theme zipped together with
     * its folder ("mytheme/config.json") is read as if the folder were not there.
     */
    static String themePath(String entryName) {
        if (entryName == null) return null;
        String name = entryName.replace('\\', '/');
        while (name.startsWith("./")) name = name.substring(2);
        List<String> segments = new ArrayList<>(Arrays.asList(name.split("/", -1)));
        String path = ThemeFiles.path(segments);
        if (path == null && segments.size() > 1) path = ThemeFiles.path(segments.subList(1, segments.size()));
        // theme_revision is the tools' own marker; an import must not forge a push, so it is left out
        return "theme_revision".equals(path) ? null : path;
    }

    private static long limitOf(String path) {
        if (Config.FILE_NAME.equals(path)) return MAX_CONFIG_BYTES;
        if (path.endsWith(WallpaperCaptions.FILE_NAME)) return MAX_TEXT_BYTES;
        return MAX_IMAGE_BYTES;
    }

    /**
     * Moves the staged files into the theme folder. When the zip brings pictures, the collection's pictures that came
     * with an earlier theme are replaced (the ones the owner picked, WallpaperImport's, stay); config.json goes last.
     */
    private static Outcome place(File staging, File dir, List<String> staged) throws IOException {
        boolean hasCollection = false;
        for (String path : staged) {
            if (path.startsWith(ThemeFiles.WALLPAPERS + "/") && !path.endsWith(WallpaperCaptions.FILE_NAME)) hasCollection = true;
        }
        File wallpapers = new File(dir, ThemeFiles.WALLPAPERS);
        if (hasCollection) {
            File[] old = wallpapers.listFiles();
            if (old != null) {
                for (File f : old) {
                    if (ThemeFiles.collectionImage(f.getName()) && !WallpaperImport.isImported(f.getName())) f.delete();
                }
            }
        }
        if (!staged.isEmpty() && !wallpapers.isDirectory()) wallpapers.mkdirs();
        boolean config = false;
        int files = 0;
        List<String> order = new ArrayList<>(staged);
        if (order.remove(Config.FILE_NAME)) order.add(Config.FILE_NAME);
        for (String path : order) {
            File from = new File(staging, path);
            File to = new File(dir, path);
            File parent = to.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new ImportException(Problem.STORAGE);
            if (!from.renameTo(to)) throw new ImportException(Problem.STORAGE);
            files++;
            if (Config.FILE_NAME.equals(path)) config = true;
        }
        return new Outcome(files, config);
    }

    /** Throws NOT_JSON unless the bytes are a JSON object, which is what Config reads. */
    static void validateConfig(byte[] bytes) throws ImportException {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);   // Windows editors add a BOM; Config skips it too
        try {
            new JSONObject(text);
        } catch (JSONException | RuntimeException e) {
            throw new ImportException(Problem.NOT_JSON);
        }
    }

    private static byte[] readAll(File file, long max) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > max) throw new ImportException(Problem.TOO_BIG);
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (ImportException e) {
            throw e;
        } catch (IOException e) {
            throw new ImportException(Problem.UNREADABLE);
        }
    }

    static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File c : children) deleteTree(c);
        file.delete();
    }
}
