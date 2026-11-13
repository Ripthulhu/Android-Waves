package org.lgxmb.wallpaper;

import java.time.Instant;
import java.util.TimeZone;

public final class SystemPaletteCheck {
    private static void near(double actual, double expected, double tolerance) {
        if (!Double.isFinite(actual) || Math.abs(actual - expected) > tolerance)
            throw new AssertionError(actual + " != " + expected);
    }

    public static void main(String[] args) {
        SystemPalette palette = new SystemPalette();
        assert palette.set(0xffff0000, 0xff00ff00, 0xff0000ff);
        for (int i = 0; i < 9; i++) near(palette.linear[i], i % 4 == 0 ? 1 : 0, .000001);
        assert !palette.set(0x00ff0000, 0x8800ff00, 0xff0000ff) : "Alpha cannot invalidate an opaque palette";
        assert palette.set(0xff808080, 0xff808080, 0xff808080);
        for (float channel : palette.linear) near(channel, .2158605, .000001);
        for (int i = 0; i < 256; i++) {
            float linear = SystemPalette.linearChannel(i);
            double encoded = linear <= .0031308 ? 12.92 * linear : 1.055 * Math.pow(linear, 1 / 2.4) - .055;
            near(encoded * 255, i, .0001);
        }

        SystemPalette custom = new SystemPalette();
        custom.set(0xff102030, 0xff405060, 0xff708090);
        float[] savedCustom = custom.linear.clone();
        palette.set(0xffdd0011, 0xff22bb33, 0xff44aaee);
        for (int i = 0; i < 9; i++) near(custom.linear[i], savedCustom[i], 0);
        float[] latestSystem = palette.linear.clone();
        custom.set(0xffeeeeee, 0xff111111, 0xff0a0b0c);
        for (int i = 0; i < 9; i++) near(palette.linear[i], latestSystem[i], 0);
        assert !custom.set(0xffeeeeee, 0xff111111, 0xff0a0b0c) : "Unchanged custom settings must reuse the background cache";

        BackgroundClock clock = new BackgroundClock();
        TimeZone zone = TimeZone.getTimeZone("Europe/Amsterdam");
        long time = Instant.parse("2026-11-12T10:00:00Z").toEpochMilli();
        clock.update(time, zone, 13, 1);
        near(SystemPalette.daylight(clock.nightDayBlend), 1, .000001);
        clock.update(time, zone, 13, 2);
        near(SystemPalette.daylight(clock.nightDayBlend), 0, .000001);
        for (int hour = 0; hour < 24; hour++) {
            clock.update(time + hour * 3_600_000L, zone, 13, 0);
            float daylight = SystemPalette.daylight(clock.nightDayBlend);
            assert Float.isFinite(daylight) && daylight >= 0 && daylight <= 1;
        }
        clock.update(time, zone, 14, 1);
        near(SystemPalette.daylight(clock.nightDayBlend), 1, .000001);
        clock.update(time, zone, 14, 2);
        near(SystemPalette.daylight(clock.nightDayBlend), 0, .000001);
        System.out.println("System palette checks passed: sRGB conversion, cached updates and day/night lighting");
    }
}
