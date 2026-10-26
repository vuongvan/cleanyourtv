package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ThemeServerIdleTest {
    private static final long TEN_MINUTES = 10L * 60 * 1000;

    @Test
    public void theLimitIsTenMinutes() {
        assertEquals(TEN_MINUTES, ThemeServer.IDLE_TIMEOUT_MS);
    }

    @Test
    public void notIdleBeforeTheTimeIsUp() {
        assertFalse(ThemeServer.idleExpired(1_000, 0, TEN_MINUTES, 0));
        assertFalse(ThemeServer.idleExpired(TEN_MINUTES - 1, 0, TEN_MINUTES, 0));
    }

    @Test
    public void idleOnceTheTimeIsUp() {
        assertTrue(ThemeServer.idleExpired(TEN_MINUTES, 0, TEN_MINUTES, 0));
        assertTrue(ThemeServer.idleExpired(TEN_MINUTES * 3, 5, TEN_MINUTES, 0));
    }

    @Test
    public void aTransferInProgressIsNeverIdle() {
        assertFalse(ThemeServer.idleExpired(TEN_MINUTES * 5, 0, TEN_MINUTES, 1));
        assertFalse(ThemeServer.idleExpired(TEN_MINUTES * 5, 0, TEN_MINUTES, 3));
    }

    @Test
    public void aRecentRequestRestartsTheCount() {
        long lastRequest = 9 * 60 * 1000;
        assertFalse(ThemeServer.idleExpired(TEN_MINUTES + 1, lastRequest, TEN_MINUTES, 0));
        assertTrue(ThemeServer.idleExpired(lastRequest + TEN_MINUTES, lastRequest, TEN_MINUTES, 0));
    }
}
