package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WallpaperImportTest {

    @Test
    public void importedPicturesAreRecognisedByTheirName() {
        assertTrue(WallpaperImport.isImported(WallpaperImport.PREFIX + "1700000000000.jpg"));
        assertFalse(WallpaperImport.isImported("sunset.jpg"));
        assertFalse(WallpaperImport.isImported(null));
    }

    @Test
    public void exifOrientationTurnsThePictureUpright() {
        assertEquals(0, WallpaperImport.rotationDegrees(1));   // normal
        assertEquals(180, WallpaperImport.rotationDegrees(3));
        assertEquals(90, WallpaperImport.rotationDegrees(6));
        assertEquals(270, WallpaperImport.rotationDegrees(8));
        assertEquals(0, WallpaperImport.rotationDegrees(0));   // undefined
    }
}
