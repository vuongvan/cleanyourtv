package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ThemeImportTest {
    private File dir;

    @Before
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("theme-import").toFile();
    }

    @After
    public void tearDown() {
        ThemeImport.deleteTree(dir);
    }

    private File write(String name, byte[] bytes) throws IOException {
        File f = new File(dir.getParentFile(), "src-" + System.nanoTime() + "-" + name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(bytes);
        }
        f.deleteOnExit();
        return f;
    }

    private File zip(Map<String, String> entries) throws IOException {
        File f = new File(dir.getParentFile(), "zip-" + System.nanoTime() + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(f))) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        f.deleteOnExit();
        return f;
    }

    private static Map<String, String> entries(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return m;
    }

    private void expect(ThemeImport.Problem problem, File source, String name) {
        try {
            ThemeImport.importFile(source, name, dir);
            fail("expected " + problem);
        } catch (ThemeImport.ImportException e) {
            assertEquals(problem, e.problem);
        } catch (IOException e) {
            fail("wrong exception " + e);
        }
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void configJsonIsWrittenAsConfigJson() throws IOException {
        ThemeImport.Outcome o = ThemeImport.importFile(write("mytheme.json", "{\"columns\":5}".getBytes(StandardCharsets.UTF_8)), "mytheme.json", dir);
        assertTrue(o.config);
        assertEquals(1, o.files);
        assertEquals("{\"columns\":5}", read(new File(dir, "config.json")));
        assertFalse(new File(dir, "config.json.part").exists());
    }

    @Test
    public void aConfigThatIsNotJsonChangesNothing() throws IOException {
        Files.write(new File(dir, "config.json").toPath(), "{\"old\":true}".getBytes(StandardCharsets.UTF_8));
        expect(ThemeImport.Problem.NOT_JSON, write("x.json", "not json at all".getBytes(StandardCharsets.UTF_8)), "x.json");
        assertEquals("{\"old\":true}", read(new File(dir, "config.json")));
    }

    @Test
    public void aConfigOverSixtyFourKilobytesIsRefused() throws IOException {
        byte[] big = new byte[(int) ThemeImport.MAX_CONFIG_BYTES + 1];
        java.util.Arrays.fill(big, (byte) ' ');
        expect(ThemeImport.Problem.TOO_BIG, write("big.json", big), "big.json");
    }

    @Test
    public void zipFilesGoWhereTheThemeToolsPutThem() throws IOException {
        File z = zip(entries("config.json", "{\"columns\":6}", "wallpapers/dusk.jpg", "JPG", "wallpapers/captions.txt", "dusk.jpg\tDusk",
                "wallpaper.jpg", "JPG"));
        ThemeImport.Outcome o = ThemeImport.importFile(z, "theme.zip", dir);
        assertEquals(4, o.files);
        assertTrue(o.config);
        assertEquals("{\"columns\":6}", read(new File(dir, "config.json")));
        assertTrue(new File(dir, "wallpapers/dusk.jpg").isFile());
        assertTrue(new File(dir, "wallpapers/captions.txt").isFile());
        assertTrue(new File(dir, "wallpaper.jpg").isFile());
        assertFalse("the staging folder is removed", new File(dir, ThemeImport.STAGING).exists());
    }

    @Test
    public void entriesThatAreNotThemeFilesAreSkippedAndCannotEscape() throws IOException {
        File z = zip(entries("config.json", "{}", "../evil.txt", "x", "wallpapers/../evil.jpg", "x", "readme.txt", "x",
                "/etc/passwd", "x", "wallpapers/.hidden.jpg", "x", "theme_revision", "forged"));
        ThemeImport.Outcome o = ThemeImport.importFile(z, "theme.zip", dir);
        assertEquals(1, o.files);
        assertFalse(new File(dir.getParentFile(), "evil.txt").exists());
        assertFalse(new File(dir, "evil.jpg").exists());
        assertFalse(new File(dir, "readme.txt").exists());
        assertFalse("an import must not forge a theme push", new File(dir, "theme_revision").exists());
    }

    @Test
    public void aThemeZippedWithItsFolderIsReadAsIfWithoutIt() throws IOException {
        File z = zip(entries("mytheme/config.json", "{\"a\":1}", "mytheme/wallpapers/a.png", "PNG"));
        ThemeImport.importFile(z, "mytheme.zip", dir);
        assertEquals("{\"a\":1}", read(new File(dir, "config.json")));
        assertTrue(new File(dir, "wallpapers/a.png").isFile());
    }

    @Test
    public void aZipWithABadConfigWritesNothing() throws IOException {
        File z = zip(entries("wallpapers/a.jpg", "JPG", "config.json", "{broken"));
        expect(ThemeImport.Problem.NOT_JSON, z, "theme.zip");
        assertFalse(new File(dir, "wallpapers/a.jpg").exists());
        assertFalse(new File(dir, "config.json").exists());
    }

    @Test
    public void aZipWithNoThemeFilesIsEmpty() throws IOException {
        expect(ThemeImport.Problem.EMPTY, zip(entries("notes.txt", "hello")), "theme.zip");
    }

    @Test
    public void somethingThatIsNotAZipIsUnreadable() throws IOException {
        expect(ThemeImport.Problem.UNREADABLE, write("fake.zip", "this is not a zip".getBytes(StandardCharsets.UTF_8)), "fake.zip");
    }

    @Test
    public void unknownKindsAreUnsupported() throws IOException {
        expect(ThemeImport.Problem.UNSUPPORTED, write("a.txt", new byte[]{1}), "a.txt");
    }

    @Test
    public void newPicturesReplaceThoseOfTheOldThemeButNotTheOwnersOwn() throws IOException {
        File wallpapers = new File(dir, "wallpapers");
        assertTrue(wallpapers.mkdirs());
        Files.write(new File(wallpapers, "old.jpg").toPath(), new byte[]{1});
        Files.write(new File(wallpapers, WallpaperImport.PREFIX + "1.jpg").toPath(), new byte[]{1});
        ThemeImport.importFile(zip(entries("wallpapers/new.jpg", "JPG")), "theme.zip", dir);
        assertFalse(new File(wallpapers, "old.jpg").exists());
        assertTrue(new File(wallpapers, "new.jpg").exists());
        assertTrue(new File(wallpapers, WallpaperImport.PREFIX + "1.jpg").exists());
    }

    @Test
    public void aZipWithoutPicturesLeavesTheCollectionAlone() throws IOException {
        File wallpapers = new File(dir, "wallpapers");
        assertTrue(wallpapers.mkdirs());
        Files.write(new File(wallpapers, "keep.jpg").toPath(), new byte[]{1});
        ThemeImport.importFile(zip(entries("config.json", "{}")), "theme.zip", dir);
        assertTrue(new File(wallpapers, "keep.jpg").exists());
    }

    @Test
    public void kindsFollowTheExtension() {
        assertEquals(ThemeImport.Kind.CONFIG, ThemeImport.kind("Theme.JSON"));
        assertEquals(ThemeImport.Kind.ZIP, ThemeImport.kind("theme.zip"));
        assertEquals(ThemeImport.Kind.IMAGE, ThemeImport.kind("a.JPEG"));
        assertEquals(ThemeImport.Kind.IMAGE, ThemeImport.kind("a.webp"));
        assertEquals(ThemeImport.Kind.UNKNOWN, ThemeImport.kind("a.txt"));
        assertEquals(ThemeImport.Kind.UNKNOWN, ThemeImport.kind(null));
    }

    @Test
    public void pictureNamesBecomeNamesTheCollectionAccepts() {
        assertEquals("IMG_2024_01", ThemeImport.imageBaseName("IMG 2024-01.jpg", 7).replace('-', '_'));
        assertTrue(ThemeFiles.collectionImage(ThemeImport.imageBaseName("../../ảnh đẹp!.png", 7) + ".jpg"));
        assertEquals("photo_7", ThemeImport.imageBaseName("???.png", 7));
        assertEquals("a", ThemeImport.imageBaseName("C:\\pics\\a.jpg", 7));
    }

    @Test
    public void uploadNamesAreReducedToTheirLastSegment() {
        assertEquals("a b.zip", ThemeServer.fileName("..%2F..%2Fa%20b.zip"));
        assertEquals("config.json", ThemeServer.fileName("config.json"));
        assertEquals("", ThemeServer.fileName(null));
    }

    @Test
    public void onlyClientsOnThisNetworkAreServed() throws Exception {
        assertTrue(ThemeServer.isLocalClient(InetAddress.getByName("192.168.1.20")));
        assertTrue(ThemeServer.isLocalClient(InetAddress.getByName("10.0.0.5")));
        assertTrue(ThemeServer.isLocalClient(InetAddress.getByName("127.0.0.1")));
        assertTrue(ThemeServer.isLocalClient(InetAddress.getByName("fd12:3456::1")));
        assertFalse(ThemeServer.isLocalClient(InetAddress.getByName("8.8.8.8")));
        assertFalse(ThemeServer.isLocalClient(null));
    }

    @Test
    public void pinsHaveSixDigits() {
        for (int i = 0; i < 50; i++) assertTrue(ThemeServer.newPin().matches("[0-9]{6}"));
    }
}
