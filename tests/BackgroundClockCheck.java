package org.lgxmb.wallpaper;

import java.time.Instant;
import java.util.TimeZone;

public final class BackgroundClockCheck {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static long time(String value) { return Instant.parse(value).toEpochMilli(); }
    private static void near(float actual, float expected) {
        if (Math.abs(actual - expected) > .001f) throw new AssertionError(actual + " != " + expected);
    }

    public static void main(String[] args) {
        BackgroundClock clock = new BackgroundClock(), other = new BackgroundClock();
        clock.update(time("2026-11-11T12:00:00Z"), UTC, 0, 0);
        assert clock.layers[0] == 10 && clock.layers[2] == 22;
        near(clock.dayTime, 1200); near(clock.nightTime, 0); near(clock.nightDayBlend, 1);

        clock.update(time("2026-03-01T00:30:00Z"), TimeZone.getTimeZone("America/New_York"), 0, 0);
        assert clock.layers[0] == 1 : "Season follows the local date, not UTC";
        clock.update(time("2026-03-01T00:30:00Z"), TimeZone.getTimeZone("Asia/Tokyo"), 0, 0);
        assert clock.layers[0] == 2 && clock.monthTime == 0;

        clock.update(time("2028-02-28T12:00:00Z"), UTC, 0, 0);
        float february = clock.monthTime;
        clock.update(time("2028-02-29T12:00:00Z"), UTC, 0, 0);
        assert clock.monthTime == february : "Match the PS3's leap-day colour rule";
        clock.update(time("2028-03-01T12:00:00Z"), UTC, 0, 0);
        assert clock.layers[0] == 2 && clock.monthTime == 0;

        clock.update(time("2026-03-29T01:30:00Z"), TimeZone.getTimeZone("Europe/Amsterdam"), 0, 0);
        other.update(time("2026-03-29T03:30:00Z"), UTC, 0, 0);
        near(clock.dayTime, other.dayTime); near(clock.nightDayBlend, other.nightDayBlend);

        clock.update(time("2026-01-01T00:00:00Z"), UTC, 9, 1);
        assert clock.layers[0] == 8 && clock.layers[1] == 9 && clock.monthTime == 0;
        near(clock.dayTime, 1200); near(clock.nightTime, 0); near(clock.nightDayBlend, 1);
        clock.update(time("2026-07-19T13:00:00Z"), UTC, 12, 2);
        assert clock.layers[0] == 11 && clock.layers[1] == 0 && clock.layers[3] == 12;
        near(clock.dayTime, 0); near(clock.nightTime, 1200); near(clock.nightDayBlend, .50005299f);
        clock.update(time("2026-07-19T13:00:00Z"), UTC, 12, 0);
        assert clock.dayTime != 0 : "A fixed colour can still follow the clock";
        clock.update(time("2026-12-31T23:59:59Z"), UTC, 0, 0);
        assert clock.layers[0] == 11 && clock.layers[1] == 0;
        assert Float.isFinite(clock.dayTime) && Float.isFinite(clock.nightDayBlend);
        System.out.println("Background calendar checks passed");
    }
}
