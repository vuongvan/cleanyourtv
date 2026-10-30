package com.gathin.tvhome;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Backup and restore of the owner's settings as one .zip: what Launcher settings keeps (the on-TV choices), the dock
 * order, the module-card decisions, the App library shortcuts, and the theme folder (config.json, wallpapers, channel
 * pictures). Layout of the zip:
 * <pre>
 *   backup.json          format, app, time, whether wallpapers are in it, and the on-TV choices
 *   state/order.txt ...  the launcher's own files (STATE_FILES)
 *   theme/config.json    the theme folder, only names ThemeFiles accepts
 * </pre>
 * Only these names are ever read back, only these preference keys with the type each has, and every file is capped, so
 * a zip from elsewhere cannot write anywhere else or set anything else. No Android types except org.json, so it runs
 * in the JVM tests; BackupActions does the Android side (the preferences, the folders, notifying Home).
 */
final class SettingsBackup {
    static final int FORMAT = 1;
    static final String MANIFEST = "backup.json";
    static final String STATE = "state/";
    static final String THEME = "theme/";
    /** Where backups are kept, inside Config.dir. */
    static final String DIR_NAME = "backups";
    static final int KEEP = 10;
    static final long MAX_STATE_BYTES = 512 * 1024;
    private static final long MAX_MANIFEST_BYTES = 64 * 1024;
    /** What a restore will read, as large as a theme zip. */
    static final long MAX_BYTES = ThemeImport.MAX_ZIP_BYTES;
    /** The launcher's own files, in filesDir; AtomicFile keeps a ".bak" beside the library ones, never backed up. */
    static final String[] STATE_FILES = {"order.txt", "cards.json", "library_apps.txt", "library_home_removed.txt"};

    /** The on-TV choices that are backed up, and the type each must have: i int, b boolean, s string. */
    private static final Map<String, Character> KEYS = new LinkedHashMap<>();

    static {
        KEYS.put("columns", 'i');
        KEYS.put(Config.ROWS_BELOW_DOCK_KEY, 'i');
        KEYS.put("animations", 'b');
        KEYS.put("advanced_effects", 'b');
        KEYS.put(Config.WALLPAPER_CHOICE_KEY, 's');
        KEYS.put(Config.DOCK_OPACITY_KEY, 'i');
        KEYS.put(HomeChoices.DOCK_ONLY_KEY, 'b');
        KEYS.put(HomeChoices.DOCK_DOWN_KEY, 'i');
        KEYS.put(HomeChoices.HERO_TITLE_KEY, 'i');
        KEYS.put(HomeChoices.STOCK_REDIRECT_KEY, 'b');
        KEYS.put(HomeChoices.CHANNELS_KEY, 's');
        KEYS.put(OpenOnStart.KEY, 's');
    }

    private SettingsBackup() {
    }

    /** What a backup holds once read: the choices, whether the wallpaper pictures are in it, and when it was made. */
    static final class Parsed {
        final Map<String, Object> prefs;
        final boolean wallpapers;
        final long created;
        /** Relative paths staged under the staging folder ("state/order.txt", "theme/config.json"). */
        final List<String> files = new ArrayList<>();

        Parsed(Map<String, Object> prefs, boolean wallpapers, long created) {
            this.prefs = prefs;
            this.wallpapers = wallpapers;
            this.created = created;
        }
    }

    /** The preference keys a backup covers, in the order they are written. */
    static List<String> backedUpKeys() {
        return new ArrayList<>(KEYS.keySet());
    }

    /** The backed-up choices among {@code all} (SharedPreferences.getAll()), those with the expected type only. */
    static Map<String, Object> pickPrefs(Map<String, ?> all) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Character> k : KEYS.entrySet()) {
            Object v = all.get(k.getKey());
            if (matches(k.getValue(), v)) out.put(k.getKey(), v);
        }
        return out;
    }

    private static boolean matches(char type, Object v) {
        switch (type) {
            case 'i':
                return v instanceof Integer;
            case 'b':
                return v instanceof Boolean;
            default:
                return v instanceof String;
        }
    }

    /** backup.json for these choices. */
    static String manifestJson(Map<String, Object> prefs, boolean wallpapers, long created, String app) {
        try {
            JSONObject choices = new JSONObject();
            for (Map.Entry<String, Object> e : prefs.entrySet()) choices.put(e.getKey(), e.getValue());
            return new JSONObject().put("format", FORMAT).put("app", app).put("created", created)
                    .put("wallpapers", wallpapers).put("launcher_settings", choices).toString(2);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reads backup.json: the format must be this one, and only known keys with the right type are taken. */
    static Parsed parseManifest(String json) throws ThemeImport.ImportException {
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (JSONException | RuntimeException e) {
            throw new ThemeImport.ImportException(ThemeImport.Problem.NOT_A_BACKUP);
        }
        Object format = root.opt("format");   // a number: optInt would also accept the text "1"
        if (!(format instanceof Number) || ((Number) format).intValue() != FORMAT) {
            throw new ThemeImport.ImportException(ThemeImport.Problem.NOT_A_BACKUP);
        }
        Map<String, Object> prefs = new LinkedHashMap<>();
        JSONObject choices = root.optJSONObject("launcher_settings");
        if (choices != null) {
            for (Map.Entry<String, Character> k : KEYS.entrySet()) {
                Object v = choices.opt(k.getKey());
                char type = k.getValue();
                if (type == 'i' && v instanceof Number && ((Number) v).doubleValue() == ((Number) v).intValue()) {
                    prefs.put(k.getKey(), ((Number) v).intValue());
                } else if (type == 'b' && v instanceof Boolean) {
                    prefs.put(k.getKey(), v);
                } else if (type == 's' && v instanceof String && ((String) v).length() <= 2000) {
                    String text = (String) v;
                    if (HomeChoices.CHANNELS_KEY.equals(k.getKey())) text = ChannelCatalog.encode(ChannelCatalog.decode(text));
                    prefs.put(k.getKey(), text);
                }
            }
        }
        return new Parsed(prefs, root.optBoolean("wallpapers", true), root.optLong("created", 0));
    }

    /** True when the zip has a backup.json: a settings backup, not a theme (ThemeIntake routes on this). */
    static boolean isBackup(File zip) {
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry entry;
            for (int i = 0; i < ThemeImport.MAX_ZIP_ENTRIES && (entry = in.getNextEntry()) != null; i++) {
                if (MANIFEST.equals(entry.getName())) return true;
            }
        } catch (IOException | RuntimeException ignored) {
            // not a zip: not a backup
        }
        return false;
    }

    /** What went into a backup. */
    static final class Summary {
        final int files;
        final boolean wallpapers;

        Summary(int files, boolean wallpapers) {
            this.files = files;
            this.wallpapers = wallpapers;
        }
    }

    /** The theme-relative names that exist in {@code themeDir} and that a backup keeps (pictures only if asked). */
    static List<String> themeFiles(File themeDir, boolean wallpapers) {
        List<String> names = new ArrayList<>();
        if (themeDir == null) return names;
        File[] root = themeDir.listFiles();
        if (root != null) {
            Arrays.sort(root);
            for (File f : root) {
                String path = f.isFile() ? ThemeFiles.path(java.util.Collections.singletonList(f.getName())) : null;
                if (path != null && !"theme_revision".equals(path)) names.add(path);
            }
        }
        File[] collection = new File(themeDir, ThemeFiles.WALLPAPERS).listFiles();
        if (wallpapers && collection != null) {
            Arrays.sort(collection);
            for (File f : collection) {
                String path = f.isFile() ? ThemeFiles.path(Arrays.asList(ThemeFiles.WALLPAPERS, f.getName())) : null;
                if (path != null) names.add(path);
            }
        }
        return names;
    }

    private static long size(File dir, List<String> names) {
        long total = 0;
        for (String n : names) total += new File(dir, n).length();
        return total;
    }

    /**
     * Writes the backup zip to {@code out} (through a ".part" file). The wallpaper pictures go in unless they would take
     * the zip past what a restore reads; the rest always does.
     */
    static Summary writeZip(File out, Map<String, Object> prefs, File filesDir, File themeDir, long created, String app)
            throws IOException {
        List<String> theme = themeFiles(themeDir, true);
        boolean wallpapers = size(themeDir, theme) + 2L * 1024 * 1024 <= MAX_BYTES;
        if (!wallpapers) theme = themeFiles(themeDir, false);
        List<String> state = new ArrayList<>();
        for (String name : STATE_FILES) {
            File f = new File(filesDir, name);
            if (f.isFile() && f.length() > 0 && f.length() <= MAX_STATE_BYTES) state.add(name);
        }
        File parent = out.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
        File part = new File(out.getPath() + ".part");
        int files = 0;
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(part))) {
            put(zip, MANIFEST, manifestJson(prefs, wallpapers, created, app).getBytes(StandardCharsets.UTF_8));
            files++;
            for (String name : state) {
                put(zip, STATE + name, Files.readAllBytes(new File(filesDir, name).toPath()));
                files++;
            }
            for (String name : theme) {
                put(zip, THEME + name, Files.readAllBytes(new File(themeDir, name).toPath()));
                files++;
            }
        } catch (IOException | RuntimeException e) {
            part.delete();
            throw e;
        }
        if (!part.renameTo(out)) {
            part.delete();
            throw new IOException("cannot write " + out);
        }
        return new Summary(files, wallpapers);
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    /**
     * Unpacks a backup into {@code staging} and checks it. Only backup.json, state/<STATE_FILES> and theme/<names
     * ThemeFiles accepts> are taken; the rest of the zip is skipped. Nothing outside {@code staging} is touched.
     */
    static Parsed unpack(File zip, File staging) throws ThemeImport.ImportException {
        if (zip.length() > MAX_BYTES) throw new ThemeImport.ImportException(ThemeImport.Problem.TOO_BIG);
        if (!(staging.isDirectory() || staging.mkdirs())) throw new ThemeImport.ImportException(ThemeImport.Problem.STORAGE);
        Parsed parsed = null;
        List<String> staged = new ArrayList<>();
        long total = 0;
        int entries = 0;
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (++entries > ThemeImport.MAX_ZIP_ENTRIES) throw new ThemeImport.ImportException(ThemeImport.Problem.TOO_BIG);
                if (entry.isDirectory()) continue;
                String rel = relativeName(entry.getName());
                if (rel == null) continue;
                long limit = MANIFEST.equals(rel) ? MAX_MANIFEST_BYTES : limitOf(rel);
                File target = new File(staging, rel);
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new ThemeImport.ImportException(ThemeImport.Problem.STORAGE);
                long written = 0;
                try (OutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[16 * 1024];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        written += n;
                        total += n;
                        if (written > limit || total > ThemeImport.MAX_ZIP_TOTAL_BYTES) throw new ThemeImport.ImportException(ThemeImport.Problem.TOO_BIG);
                        out.write(buffer, 0, n);
                    }
                }
                if (written == 0) {
                    target.delete();
                    continue;
                }
                if (MANIFEST.equals(rel)) {
                    parsed = parseManifest(new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
                } else {
                    staged.add(rel);
                }
            }
        } catch (ThemeImport.ImportException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new ThemeImport.ImportException(ThemeImport.Problem.UNREADABLE);
        }
        if (parsed == null) throw new ThemeImport.ImportException(entries == 0 ? ThemeImport.Problem.UNREADABLE : ThemeImport.Problem.NOT_A_BACKUP);
        if (staged.contains(THEME + Config.FILE_NAME)) {
            try {
                ThemeImport.validateConfig(Files.readAllBytes(new File(staging, THEME + Config.FILE_NAME).toPath()));
            } catch (ThemeImport.ImportException e) {
                throw e;   // NOT_JSON: ImportException is an IOException too, so it must be caught first
            } catch (IOException e) {
                throw new ThemeImport.ImportException(ThemeImport.Problem.UNREADABLE);
            }
        }
        parsed.files.addAll(staged);
        return parsed;
    }

    /** The staged name of a zip entry ("backup.json", "state/x", "theme/y"), or null when it is none that is read. */
    static String relativeName(String entryName) {
        if (entryName == null) return null;
        String name = entryName.replace('\\', '/');
        if (MANIFEST.equals(name)) return name;
        if (name.startsWith(STATE)) {
            String file = name.substring(STATE.length());
            for (String known : STATE_FILES) if (known.equals(file)) return name;
            return null;
        }
        if (name.startsWith(THEME)) {
            String path = ThemeFiles.path(new ArrayList<>(Arrays.asList(name.substring(THEME.length()).split("/", -1))));
            return path == null || "theme_revision".equals(path) ? null : THEME + path;
        }
        return null;
    }

    private static long limitOf(String rel) {
        if (rel.startsWith(STATE)) return MAX_STATE_BYTES;
        String path = rel.substring(THEME.length());
        if (Config.FILE_NAME.equals(path)) return ThemeImport.MAX_CONFIG_BYTES;
        if (path.endsWith(WallpaperCaptions.FILE_NAME)) return ThemeImport.MAX_TEXT_BYTES;
        return ThemeImport.MAX_IMAGE_BYTES;
    }

    /**
     * Moves the staged files into place. State files the backup does not have are removed (the backup had none), as are
     * the ".bak" copies AtomicFile keeps. The wallpaper collection is replaced by the backup's when it carries
     * pictures; a theme config.json the backup lacks is left alone.
     *
     * @return how many files were placed
     */
    static int place(File staging, Parsed parsed, File themeDir, File filesDir) throws ThemeImport.ImportException {
        int placed = 0;
        for (String name : STATE_FILES) {
            File target = new File(filesDir, name);
            new File(filesDir, name + ".bak").delete();
            File from = new File(staging, STATE + name);
            if (parsed.files.contains(STATE + name)) {
                if (!move(from, target)) throw new ThemeImport.ImportException(ThemeImport.Problem.STORAGE);
                placed++;
            } else {
                target.delete();
            }
        }
        File wallpapers = new File(themeDir, ThemeFiles.WALLPAPERS);
        if (parsed.wallpapers) {
            File[] old = wallpapers.listFiles();
            if (old != null) {
                for (File f : old) {
                    if (ThemeFiles.collectionImage(f.getName()) || WallpaperCaptions.FILE_NAME.equals(f.getName())) f.delete();
                }
            }
        }
        List<String> theme = new ArrayList<>();
        for (String rel : parsed.files) if (rel.startsWith(THEME)) theme.add(rel.substring(THEME.length()));
        if (theme.remove(Config.FILE_NAME)) theme.add(Config.FILE_NAME);   // config.json goes last
        for (String path : theme) {
            File to = new File(themeDir, path);
            File parent = to.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new ThemeImport.ImportException(ThemeImport.Problem.STORAGE);
            if (!move(new File(staging, THEME + path), to)) throw new ThemeImport.ImportException(ThemeImport.Problem.STORAGE);
            placed++;
        }
        return placed;
    }

    private static boolean move(File from, File to) {
        if (to.exists() && !to.delete()) return false;
        if (from.renameTo(to)) return true;
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
