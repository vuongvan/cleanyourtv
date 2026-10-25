package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HomeChoicesTest {

    @Test
    public void dockDownIsClampedToTheSlidersRange() {
        assertEquals(0, HomeChoices.clampDown(-5));
        assertEquals(HomeChoices.DOCK_DOWN_MAX_DP, HomeChoices.clampDown(9999));
        assertEquals(40, HomeChoices.clampDown(40));
    }

    @Test
    public void dockDownSliderStepsDivideTheRange() {
        assertEquals(0, HomeChoices.DOCK_DOWN_MAX_DP % HomeChoices.DOCK_DOWN_STEP_DP);
    }

    @Test
    public void heroTitleSliderRestsInTheMiddleAtNoChange() {
        int max = 2 * HomeChoices.HERO_TITLE_RANGE_SP / HomeChoices.HERO_TITLE_STEP_SP;
        assertEquals(max / 2, HomeChoices.heroTitleProgress(0));
        assertEquals(0, HomeChoices.heroTitleDeltaFromProgress(max / 2));
    }

    @Test
    public void heroTitleSliderMovesBothWays() {
        int max = 2 * HomeChoices.HERO_TITLE_RANGE_SP / HomeChoices.HERO_TITLE_STEP_SP;
        assertEquals(-HomeChoices.HERO_TITLE_RANGE_SP, HomeChoices.heroTitleDeltaFromProgress(0));
        assertEquals(HomeChoices.HERO_TITLE_RANGE_SP, HomeChoices.heroTitleDeltaFromProgress(max));
        for (int p = 0; p <= max; p++) {
            assertEquals(p, HomeChoices.heroTitleProgress(HomeChoices.heroTitleDeltaFromProgress(p)));
        }
    }

    @Test
    public void heroTitleSizeIsThemeSizePlusChangeButNeverTiny() {
        assertEquals(68, HomeChoices.heroTitleSp(68, 0));
        assertEquals(60, HomeChoices.heroTitleSp(68, -8));
        assertEquals(76, HomeChoices.heroTitleSp(68, 8));
        assertEquals(HomeChoices.HERO_TITLE_MIN_SP, HomeChoices.heroTitleSp(20, -24));
        assertEquals(68 + HomeChoices.HERO_TITLE_RANGE_SP, HomeChoices.heroTitleSp(68, 500));
    }
}
