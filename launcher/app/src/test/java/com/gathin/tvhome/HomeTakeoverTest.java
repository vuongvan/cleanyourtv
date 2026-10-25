package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HomeTakeoverTest {
    private static final String OWN = "com.gathin.tvhome";

    @Test
    public void knownStockLaunchersAreCandidates() {
        assertTrue(HomeTakeover.isStockCandidate("com.google.android.tvlauncher", true, OWN));
        assertTrue(HomeTakeover.isStockCandidate("com.google.android.apps.tv.launcherx", true, OWN));
        assertTrue(HomeTakeover.isStockCandidate("com.mitv.tvhome", true, OWN));
    }

    @Test
    public void thisLauncherTheChooserAndNonSystemAppsAreNot() {
        assertFalse(HomeTakeover.isStockCandidate(OWN, true, OWN));
        assertFalse(HomeTakeover.isStockCandidate("android", true, OWN));
        assertFalse(HomeTakeover.isStockCandidate("com.some.otherlauncher", false, OWN));
        assertFalse(HomeTakeover.isStockCandidate(null, true, OWN));
        assertFalse(HomeTakeover.isStockCandidate("", true, OWN));
    }

    @Test
    public void setupAndRecoveryScreensAreLeftToRun() {
        assertFalse(HomeTakeover.isStockCandidate("com.google.android.tungsten.setupwraith", true, OWN));
        assertFalse(HomeTakeover.isStockCandidate("com.android.provision", true, OWN));
        assertFalse(HomeTakeover.isStockCandidate("com.vendor.recoveryhome", true, OWN));
    }

    @Test
    public void theEnabledListIsReadInBothForms() {
        String list = "com.other/.Svc:com.gathin.tvhome/.HomeKeyService";
        assertTrue(HomeTakeover.listHas(list, OWN, "com.gathin.tvhome.HomeKeyService"));
        assertTrue(HomeTakeover.listHas("com.gathin.tvhome/com.gathin.tvhome.HomeKeyService", OWN, "com.gathin.tvhome.HomeKeyService"));
        assertFalse(HomeTakeover.listHas("com.other/.Svc", OWN, "com.gathin.tvhome.HomeKeyService"));
        assertFalse(HomeTakeover.listHas(null, OWN, "com.gathin.tvhome.HomeKeyService"));
        assertFalse(HomeTakeover.listHas("com.gathin.tvhome/.InputKeyService", OWN, "com.gathin.tvhome.HomeKeyService"));
    }

    @Test
    public void aServiceIsAppendedWithoutDroppingOthers() {
        String cls = "com.gathin.tvhome.HomeKeyService";
        assertEquals("com.gathin.tvhome/.HomeKeyService", HomeTakeover.listWith(null, OWN, cls));
        assertEquals("com.gathin.tvhome/.HomeKeyService", HomeTakeover.listWith("null", OWN, cls));
        assertEquals("com.gathin.tvhome/.HomeKeyService", HomeTakeover.listWith("", OWN, cls));
        assertEquals("com.other/.Svc:com.gathin.tvhome/.HomeKeyService", HomeTakeover.listWith("com.other/.Svc", OWN, cls));
        String already = "com.gathin.tvhome/.HomeKeyService:com.other/.Svc";
        assertEquals(already, HomeTakeover.listWith(already, OWN, cls));
    }
}
