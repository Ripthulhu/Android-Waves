/* LG-XMB, 2026. SPDX-License-Identifier: GPL-3.0-or-later */
package org.lgxmb.wallpaper;

/** System and custom colours arrive as sRGB; blend their cached gradients in linear light. */
final class SystemPalette {
    static final int FALLBACK_PRIMARY = 0xff49617e;
    static final int FALLBACK_SECONDARY = 0xff53606f;
    static final int FALLBACK_TERTIARY = 0xff3c665e;
    final float[] linear = new float[9];
    private int primary, secondary, tertiary;
    private boolean initialized;

    SystemPalette() { set(FALLBACK_PRIMARY, FALLBACK_SECONDARY, FALLBACK_TERTIARY); }

    boolean set(int primary, int secondary, int tertiary) {
        primary &= 0xffffff; secondary &= 0xffffff; tertiary &= 0xffffff;
        if (initialized && this.primary == primary && this.secondary == secondary && this.tertiary == tertiary)
            return false;
        this.primary = primary; this.secondary = secondary; this.tertiary = tertiary;
        decode(primary, 0); decode(secondary, 3); decode(tertiary, 6);
        initialized = true;
        return true;
    }

    private void decode(int colour, int offset) {
        linear[offset] = linearChannel((colour >>> 16) & 255);
        linear[offset + 1] = linearChannel((colour >>> 8) & 255);
        linear[offset + 2] = linearChannel(colour & 255);
    }

    static float linearChannel(int channel) {
        float value = channel / 255f;
        return value <= .04045f ? value / 12.92f : (float) Math.pow((value + .055f) / 1.055f, 2.4);
    }

    static float daylight(float nightDayBlend) {
        // BackgroundClock's retained curve spans 1-gain at night to 1 by day.
        return Math.max(0, Math.min(1, (nightDayBlend - (1 - .4999470114707947f)) / .4999470114707947f));
    }
}
