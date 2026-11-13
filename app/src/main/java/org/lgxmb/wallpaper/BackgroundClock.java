/* LG-XMB, 2026. SPDX-License-Identifier: GPL-3.0-or-later */
package org.lgxmb.wallpaper;

import java.util.Calendar;
import java.util.TimeZone;

/** The recovered PS3 calendar, with independent colour and lighting overrides. */
final class BackgroundClock {
    private static final int[] DAYS = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    private final Calendar calendar = Calendar.getInstance();
    final float[] layers = new float[4];
    float dayTime, nightTime, monthTime, nightDayBlend;

    void update(long millis, TimeZone zone, int colourMonth, int timeMode) {
        calendar.setTimeZone(zone);
        calendar.setTimeInMillis(millis);
        int month = calendar.get(Calendar.MONTH);
        int day = calendar.get(Calendar.DAY_OF_MONTH) - 1;
        // The original uses a 28-day February table and repeats its final
        // colour blend on leap day. March starts normally on March 1.
        if (month == 1 && day == 28) day = 27;
        float m = month + (float) day / DAYS[month];
        if (colourMonth >= 1 && colourMonth <= 12) m = colourMonth - 1;
        month = (int) m;
        float t = (calendar.get(Calendar.HOUR_OF_DAY) * 3600
                + calendar.get(Calendar.MINUTE) * 60 + calendar.get(Calendar.SECOND)) / 86400f;
        if (timeMode == 1) t = .5f;
        else if (timeMode == 2) t = 0;
        float t2 = t * t, t3 = t2 * t;
        float q = (-2 * t3 + 3 * t2) + (t3 - 2 * t2 + t) * 2.68038010597229f
                + (t3 - t2) * 2.68038010597229f;
        float night = t - .5f;
        if (night < 0) night += 1;
        float rise = smooth(0, .21525458991527557f, t);
        float fall = 1 - smooth(.7707499861717224f, .8471333384513855f, t);
        layers[0] = month;
        layers[1] = (month + 1) % 12;
        layers[2] = month + 12;
        layers[3] = (month + 1) % 12 + 12;
        dayTime = q * 2400;
        nightTime = night * 2400;
        monthTime = (m - month) * 30;
        nightDayBlend = 1 + (rise * fall - 1) * .4999470114707947f;
    }

    private static float smooth(float a, float b, float value) {
        float x = Math.max(0, Math.min(1, (value - a) / (b - a)));
        return x * x * (3 - 2 * x);
    }
}
