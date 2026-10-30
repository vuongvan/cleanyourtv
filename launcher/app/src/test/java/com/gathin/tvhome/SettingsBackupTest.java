package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class SettingsBackupTest {
    private File root, files, theme, staging;

    @Before
    public void setUp() throws IOException {
        root = Files.createTempDirectory("backup").toFile();
        files = new File(root, "files");
        theme = new File(root, "theme");
        staging = new File(root, "staging");
        assertTrue(files.mkdirs() && theme.mkdirs());
    }

    @After
    public void tearDown() {
        ThemeImport.deleteTree(root);
    }

    private static void write(File f, String text) throws IOException {
        File parent = f.getParentFile();
        if (!parent.isDirectory()) assertTrue(parent.mkdirs());
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private File zipOf(Map<String, String> entries) throws IOException {
        File z = new File(root, "z-" + System.nanoTime() + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(z))) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return z;
    }

    private static Map<String, String> entries(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return m;
    }

    private void expect(ThemeImport.Problem problem, File zip) {
        try {
            SettingsBackup.unpack(zip, staging);
            fail("expected " + problem);
        } catch (ThemeImport.ImportException e) {
            assertEquals(problem, e.problem);
        }
    }

    private static String manifest(String choices) {
        return "{\"format\":1,\"app\":\"x\",\"created\":5,\"wallpapers\":true,\"launcher_settings\":" + choices + "}";
    }

    @Test
    public void onlyKnownChoicesWithTheRightTypeAreBackedUp() {
        Map<String, Object> all = new HashMap<>();
        all.put("columns", 5);
        all.put("animations", true);
        all.put(HomeChoices.CHANNELS_KEY, "vtvgo:1");
        all.put("theme_signature", "secret-internal");
        all.put("settings_revision", 12L);
        all.put(HomeChoices.DOCK_DOWN_KEY, "not an int");
        Map<String, Object> picked = SettingsBackup.pickPrefs(all);
        assertEquals(3, picked.size());
        assertEquals(5, picked.get("columns"));
        assertFalse(picked.containsKey("theme_signature"));
        assertFalse(picked.containsKey("settings_revision"));
        assertFalse(picked.containsKey(HomeChoices.DOCK_DOWN_KEY));
    }

    @Test
    public void theManifestKeepsChoicesAndDropsWhatItDoesNotKnow() throws Exception {
        Map<String, Object> prefs = new LinkedHashMap<>();
        prefs.put("columns", 6);
        prefs.put("animations", false);
        prefs.put(HomeChoices.CHANNELS_KEY, "vtvgo:1,fpt:vtv1-hd");
        SettingsBackup.Parsed p = SettingsBackup.parseManifest(SettingsBackup.manifestJson(prefs, true, 99L, "app"));
        assertEquals(prefs, p.prefs);
        assertTrue(p.wallpapers);
        assertEquals(99L, p.created);
        p = SettingsBackup.parseManifest(manifest("{\"columns\":\"six\",\"animations\":1,\"evil\":5,\"dock_only\":true}"));
        assertEquals(1, p.prefs.size());
        assertEquals(true, p.prefs.get(HomeChoices.DOCK_ONLY_KEY));
    }

    @Test
    public void channelPicksAreCleanedWhenRead() throws Exception {
        SettingsBackup.Parsed p = SettingsBackup.parseManifest(manifest("{\"home_channels\":\"vtvgo:1, bad id!,vtvgo:1,<x>\"}"));
        assertEquals("vtvgo:1", p.prefs.get(HomeChoices.CHANNELS_KEY));
    }

    @Test
    public void aManifestOfAnotherFormatOrNotJsonIsNotABackup() {
        for (String bad : new String[]{"{\"format\":2}", "{}", "not json", "{\"format\":\"1\"}"}) {
            try {
                SettingsBackup.parseManifest(bad);
                fail(bad);
            } catch (ThemeImport.ImportException e) {
                assertEquals(ThemeImport.Problem.NOT_A_BACKUP, e.problem);
            }
        }
    }

    @Test
    public void aBackupRoundTripsEverythingItCovers() throws Exception {
        write(new File(files, "order.txt"), "a\nb\n");
        write(new File(files, "cards.json"), "{\"cards\":1}");
        write(new File(files, "library_apps.txt"), "com.x");
        write(new File(theme, "config.json"), "{\"columns\":6}");
        write(new File(theme, "wallpaper.jpg"), "WP");
        write(new File(theme, "kenh-vtv1.jpg"), "KENH");
        write(new File(theme, "theme_revision"), "r1");
        write(new File(theme, "wallpapers/a.jpg"), "A");
        write(new File(theme, "wallpapers/captions.txt"), "a.jpg\tA");
        write(new File(theme, "random.txt"), "not a theme file");
        Map<String, Object> prefs = new LinkedHashMap<>();
        prefs.put("columns", 6);
        File out = new File(root, "out/backup.zip");
        SettingsBackup.Summary summary = SettingsBackup.writeZip(out, prefs, files, theme, 7L, "app");
        assertTrue(summary.wallpapers);
        assertTrue(SettingsBackup.isBackup(out));
        assertFalse(new File(out.getPath() + ".part").exists());

        // restore into a different, partly filled place
        File files2 = new File(root, "files2"), theme2 = new File(root, "theme2");
        write(new File(files2, "order.txt"), "old order");
        write(new File(files2, "library_home_removed.txt"), "stale");
        write(new File(files2, "library_apps.txt.bak"), "stale bak");
        write(new File(theme2, "wallpapers/old.jpg"), "OLD");
        SettingsBackup.Parsed parsed = SettingsBackup.unpack(out, staging);
        assertEquals(prefs, parsed.prefs);
        int placed = SettingsBackup.place(staging, parsed, theme2, files2);
        assertEquals(8, placed);   // 3 state files and config.json, wallpaper.jpg, kenh-vtv1.jpg, a.jpg, captions.txt
        assertEquals("a\nb\n", read(new File(files2, "order.txt")));
        assertEquals("{\"cards\":1}", read(new File(files2, "cards.json")));
        assertEquals("com.x", read(new File(files2, "library_apps.txt")));
        assertFalse("a file the backup did not have is gone", new File(files2, "library_home_removed.txt").exists());
        assertFalse("AtomicFile leftovers are gone", new File(files2, "library_apps.txt.bak").exists());
        assertEquals("{\"columns\":6}", read(new File(theme2, "config.json")));
        assertEquals("KENH", read(new File(theme2, "kenh-vtv1.jpg")));
        assertEquals("A", read(new File(theme2, "wallpapers/a.jpg")));
        assertFalse("the old collection is replaced", new File(theme2, "wallpapers/old.jpg").exists());
        assertFalse(new File(theme2, "theme_revision").exists());
        assertFalse(new File(theme2, "random.txt").exists());
    }

    @Test
    public void theBackupLeavesTheWallpapersAloneWhenTheyWereNotInIt() throws Exception {
        write(new File(files, "order.txt"), "x");
        SettingsBackup.Parsed parsed = new SettingsBackup.Parsed(new LinkedHashMap<String, Object>(), false, 0);
        write(new File(theme, "wallpapers/keep.jpg"), "KEEP");
        parsed.files.add(SettingsBackup.STATE + "order.txt");
        write(new File(staging, "state/order.txt"), "new");
        SettingsBackup.place(staging, parsed, theme, files);
        assertTrue(new File(theme, "wallpapers/keep.jpg").exists());
        assertEquals("new", read(new File(files, "order.txt")));
    }

    @Test
    public void aThemeZipIsNotABackup() throws Exception {
        File themeZip = zipOf(entries("config.json", "{}", "wallpapers/a.jpg", "A"));
        assertFalse(SettingsBackup.isBackup(themeZip));
        expect(ThemeImport.Problem.NOT_A_BACKUP, themeZip);
        assertFalse(SettingsBackup.isBackup(zipOf(entries())));
    }

    @Test
    public void somethingThatIsNotAZipIsUnreadable() throws Exception {
        File fake = new File(root, "fake.zip");
        write(fake, "not a zip");
        assertFalse(SettingsBackup.isBackup(fake));
        expect(ThemeImport.Problem.UNREADABLE, fake);
    }

    @Test
    public void aBackupWithABadThemeConfigChangesNothing() throws Exception {
        File z = zipOf(entries("backup.json", manifest("{}"), "state/order.txt", "o", "theme/config.json", "{broken"));
        expect(ThemeImport.Problem.NOT_JSON, z);
    }

    @Test
    public void entriesThatAreNotOnTheListAreNeverStaged() throws Exception {
        File z = zipOf(entries("backup.json", manifest("{}"), "state/order.txt", "o", "state/evil.sh", "x",
                "state/../escape.txt", "x", "theme/../escape2.jpg", "x", "theme/wallpapers/../x.jpg", "x",
                "theme/theme_revision", "forged", "other/file.txt", "x", "../escape3.txt", "x"));
        SettingsBackup.Parsed parsed = SettingsBackup.unpack(z, staging);
        assertEquals(1, parsed.files.size());
        assertEquals("state/order.txt", parsed.files.get(0));
        assertFalse(new File(root, "escape.txt").exists());
        assertFalse(new File(staging, "state/evil.sh").exists());
    }

    @Test
    public void entryNamesAreMappedToTheStagedNames() {
        assertEquals("backup.json", SettingsBackup.relativeName("backup.json"));
        assertEquals("state/order.txt", SettingsBackup.relativeName("state/order.txt"));
        assertNull(SettingsBackup.relativeName("state/other.txt"));
        assertEquals("theme/config.json", SettingsBackup.relativeName("theme/config.json"));
        assertEquals("theme/wallpapers/a.jpg", SettingsBackup.relativeName("theme/wallpapers/a.jpg"));
        assertEquals("theme/kenh-vtv1.jpg", SettingsBackup.relativeName("theme/kenh-vtv1.jpg"));
        assertNull(SettingsBackup.relativeName("theme/theme_revision"));
        assertNull(SettingsBackup.relativeName("theme/readme.txt"));
        assertNull(SettingsBackup.relativeName(null));
    }

    @Test
    public void aStateFileOverTheLimitIsRefused() throws Exception {
        byte[] big = new byte[(int) SettingsBackup.MAX_STATE_BYTES + 1];
        File z = new File(root, "big.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(z))) {
            out.putNextEntry(new ZipEntry("backup.json"));
            out.write(manifest("{}").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("state/cards.json"));
            out.write(big);
            out.closeEntry();
        }
        expect(ThemeImport.Problem.TOO_BIG, z);
    }

    @Test
    public void themeFilesLeaveOutThePicturesWhenAsked() throws Exception {
        write(new File(theme, "config.json"), "{}");
        write(new File(theme, "wallpapers/a.jpg"), "A");
        List<String> with = SettingsBackup.themeFiles(theme, true);
        List<String> without = SettingsBackup.themeFiles(theme, false);
        assertTrue(with.contains("wallpapers/a.jpg"));
        assertFalse(without.contains("wallpapers/a.jpg"));
        assertTrue(without.contains("config.json"));
    }
}
