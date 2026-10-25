package com.gathin.tvhome;

import android.content.res.Resources;

import java.util.Calendar;

/**
 * Vietnamese lunar calendar (âm lịch), computed from astronomical new moons and solar terms (the algorithm of
 * Hồ Ngọc Đức), with no ICU and no network. It uses UTC+7, which is what the Vietnamese calendar is defined
 * in; the Chinese calendar uses UTC+8 and differs from it on a few days.
 */
final class LunarDate {
    /** Vietnam's time zone offset, in hours. */
    static final double TIME_ZONE = 7.0;

    /** Lunar day, month (1-12), year, and whether the month is a leap month (tháng nhuận). */
    final int day, month, year;
    final boolean leap;

    private LunarDate(int day, int month, int year, boolean leap) {
        this.day = day;
        this.month = month;
        this.year = year;
        this.leap = leap;
    }

    /** e.g. "Âm lịch: 14/9 năm Bính Ngọ" (see R.string.lunar_date_format). */
    static String today(Resources res) {
        Calendar c = Calendar.getInstance();
        return describe(res, fromSolar(c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR)));
    }

    static String describe(Resources res, LunarDate d) {
        String[] can = res.getStringArray(R.array.lunar_can);
        String[] chi = res.getStringArray(R.array.lunar_chi);
        return res.getString(R.string.lunar_date_format, d.day, d.month,
                d.leap ? res.getString(R.string.lunar_leap) : "",
                can[canIndex(d.year)] + " " + chi[chiIndex(d.year)]);
    }

    /** Index into the ten heavenly stems (Giáp, Ất, ...) of a lunar year. */
    static int canIndex(int lunarYear) {
        return Math.floorMod(lunarYear + 6, 10);
    }

    /** Index into the twelve earthly branches (Tý, Sửu, ...) of a lunar year. */
    static int chiIndex(int lunarYear) {
        return Math.floorMod(lunarYear + 8, 12);
    }

    static LunarDate fromSolar(int dd, int mm, int yy) {
        int dayNumber = jdFromDate(dd, mm, yy);
        int k = (int) ((dayNumber - 2415021.076998695) / 29.530588853);
        int monthStart = newMoonDay(k + 1);
        if (monthStart > dayNumber) monthStart = newMoonDay(k);
        int a11 = lunarMonth11(yy);
        int b11 = a11;
        int lunarYear;
        if (a11 >= monthStart) {
            lunarYear = yy;
            a11 = lunarMonth11(yy - 1);
        } else {
            lunarYear = yy + 1;
            b11 = lunarMonth11(yy + 1);
        }
        int lunarDay = dayNumber - monthStart + 1;
        int diff = (monthStart - a11) / 29;
        boolean leap = false;
        int lunarMonth = diff + 11;
        if (b11 - a11 > 365) {
            int leapMonthDiff = leapMonthOffset(a11);
            if (diff >= leapMonthDiff) {
                lunarMonth = diff + 10;
                if (diff == leapMonthDiff) leap = true;
            }
        }
        if (lunarMonth > 12) lunarMonth -= 12;
        if (lunarMonth >= 11 && diff < 4) lunarYear -= 1;
        return new LunarDate(lunarDay, lunarMonth, lunarYear, leap);
    }

    /** Julian day number of a Gregorian date. */
    static int jdFromDate(int dd, int mm, int yy) {
        int a = (14 - mm) / 12;
        int y = yy + 4800 - a;
        int m = mm + 12 * a - 3;
        int jd = dd + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045;
        if (jd < 2299161) jd = dd + (153 * m + 2) / 5 + 365 * y + y / 4 - 32083;
        return jd;
    }

    /** Day number of the k-th new moon since 1900-01-01, in {@link #TIME_ZONE}. */
    private static int newMoonDay(int k) {
        double t = k / 1236.85;
        double t2 = t * t, t3 = t2 * t;
        double dr = Math.PI / 180;
        double jd1 = 2415020.75933 + 29.53058868 * k + 0.0001178 * t2 - 0.000000155 * t3;
        jd1 += 0.00033 * Math.sin((166.56 + 132.87 * t - 0.009173 * t2) * dr);
        double m = 359.2242 + 29.10535608 * k - 0.0000333 * t2 - 0.00000347 * t3;
        double mpr = 306.0253 + 385.81691806 * k + 0.0107306 * t2 + 0.00001236 * t3;
        double f = 21.2964 + 390.67050646 * k - 0.0016528 * t2 - 0.00000239 * t3;
        double c1 = (0.1734 - 0.000393 * t) * Math.sin(m * dr) + 0.0021 * Math.sin(2 * dr * m);
        c1 = c1 - 0.4068 * Math.sin(mpr * dr) + 0.0161 * Math.sin(dr * 2 * mpr);
        c1 = c1 - 0.0004 * Math.sin(dr * 3 * mpr);
        c1 = c1 + 0.0104 * Math.sin(dr * 2 * f) - 0.0051 * Math.sin(dr * (m + mpr));
        c1 = c1 - 0.0074 * Math.sin(dr * (m - mpr)) + 0.0004 * Math.sin(dr * (2 * f + m));
        c1 = c1 - 0.0004 * Math.sin(dr * (2 * f - m)) - 0.0006 * Math.sin(dr * (2 * f + mpr));
        c1 = c1 + 0.0010 * Math.sin(dr * (2 * f - mpr)) + 0.0005 * Math.sin(dr * (2 * mpr + m));
        double deltat;
        if (t < -11) {
            deltat = 0.001 + 0.000839 * t + 0.0002261 * t2 - 0.00000845 * t3 - 0.000000081 * t * t3;
        } else {
            deltat = -0.000278 + 0.000265 * t + 0.000262 * t2;
        }
        return (int) (jd1 + c1 - deltat + 0.5 + TIME_ZONE / 24);
    }

    /** The sun's longitude at the start of a day, in 30-degree steps (0-11): which solar term the day is in. */
    private static int sunLongitude(int dayNumber) {
        double jdn = dayNumber - 0.5 - TIME_ZONE / 24;
        double t = (jdn - 2451545.0) / 36525;
        double t2 = t * t;
        double dr = Math.PI / 180;
        double m = 357.52910 + 35999.05030 * t - 0.0001559 * t2 - 0.00000048 * t * t2;
        double l0 = 280.46645 + 36000.76983 * t + 0.0003032 * t2;
        double dl = (1.914600 - 0.004817 * t - 0.000014 * t2) * Math.sin(dr * m);
        dl += (0.019993 - 0.000101 * t) * Math.sin(dr * 2 * m) + 0.000290 * Math.sin(dr * 3 * m);
        double l = l0 + dl;
        double omega = 125.04 - 1934.136 * t;
        l = l - 0.00569 - 0.00478 * Math.sin(omega * dr);
        l = l * dr;
        l = l - Math.PI * 2 * (int) (l / (Math.PI * 2));
        return (int) (l / Math.PI * 6);
    }

    /** The new moon that starts the 11th lunar month (the one holding the winter solstice) before the end of year yy. */
    private static int lunarMonth11(int yy) {
        int off = jdFromDate(31, 12, yy) - 2415021;
        int k = (int) (off / 29.530588853);
        int nm = newMoonDay(k);
        if (sunLongitude(nm) >= 9) nm = newMoonDay(k - 1);
        return nm;
    }

    /** Offset, from the 11th month starting at a11, of the leap month in a 13-month year. */
    private static int leapMonthOffset(int a11) {
        int k = (int) ((a11 - 2415021.076998695) / 29.530588853 + 0.5);
        int last;
        int i = 1;
        int arc = sunLongitude(newMoonDay(k + i));
        do {
            last = arc;
            i++;
            arc = sunLongitude(newMoonDay(k + i));
        } while (arc != last && i < 14);
        return i - 1;
    }
}
