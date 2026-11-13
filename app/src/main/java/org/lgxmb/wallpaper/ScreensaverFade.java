/* LG-XMB, 2026. SPDX-License-Identifier: GPL-3.0-or-later */
package org.lgxmb.wallpaper;

/** TV screensaver timings and smoothstep, retargeted from the displayed gains. */
final class ScreensaverFade {
    static final int ENTER_MILLIS = 1200;
    static final int EXIT_MILLIS = 160;
    float background = 1, wave = 1, sparkles = 1;
    private float dimBackground = .25f, dimWave = .25f, dimSparkles = .25f;
    private float fromBackground = 1, fromWave = 1, fromSparkles = 1;
    private float targetBackground = 1, targetWave = 1, targetSparkles = 1;
    private boolean active;
    private double elapsedMillis;
    private int durationMillis;

    void configure(int backgroundPercent, int wavePercent, int sparklePercent) {
        dimBackground = percent(backgroundPercent);
        dimWave = percent(wavePercent);
        dimSparkles = percent(sparklePercent);
        if (active) retarget();
    }

    void setActive(boolean active) {
        this.active = active;
        retarget();
    }

    private void retarget() {
        float nextBackground = active ? dimBackground : 1;
        float nextWave = active ? dimWave : 1;
        float nextSparkles = active ? dimSparkles : 1;
        if (targetBackground == nextBackground && targetWave == nextWave && targetSparkles == nextSparkles) return;
        targetBackground = nextBackground; targetWave = nextWave; targetSparkles = nextSparkles;
        fromBackground = background; fromWave = wave; fromSparkles = sparkles;
        elapsedMillis = 0;
        durationMillis = active ? ENTER_MILLIS : EXIT_MILLIS;
        if (background == targetBackground && wave == targetWave && sparkles == targetSparkles) durationMillis = 0;
    }

    boolean isFading() { return elapsedMillis < durationMillis; }

    void advance(float seconds) {
        if (!isFading() || !(seconds > 0) || !Float.isFinite(seconds)) return;
        elapsedMillis = Math.min(durationMillis, elapsedMillis + (double) seconds * 1000);
        if (elapsedMillis == durationMillis) {
            background = targetBackground; wave = targetWave; sparkles = targetSparkles;
            return;
        }
        float progress = (float) (elapsedMillis / durationMillis);
        float eased = progress * progress * (3 - 2 * progress);
        background = fromBackground + (targetBackground - fromBackground) * eased;
        wave = fromWave + (targetWave - fromWave) * eased;
        sparkles = fromSparkles + (targetSparkles - fromSparkles) * eased;
    }

    private static float percent(int value) { return Math.max(0, Math.min(100, value)) / 100f; }
}
