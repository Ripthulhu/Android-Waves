// SPDX-License-Identifier: GPL-3.0-or-later
package org.lgxmb.wallpaper;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Map;

/** One validated snapshot shared by the settings screen and wallpaper engines. */
public final class WallpaperSettings {
    public static final String FPS = "fps";
    public static final String RESOLUTION = "resolutionPercent";
    public static final String MESH = "mesh";
    public static final String PARTICLES = "particleCount";
    public static final String WAVE_SPEED = "waveSpeedPercent";
    public static final String WAVE_POSITION = "wavePositionPercent";
    public static final String SCROLL_STRENGTH = "scrollStrengthPercent";
    public static final String COLOUR_MONTH = "colourMonth";
    public static final int COLOUR_SYSTEM = 13;
    public static final int COLOUR_CUSTOM = 14;
    public static final String LAST_MONTH = "lastMonth";
    public static final String CUSTOM_PRIMARY = "customPrimary";
    public static final String CUSTOM_SECONDARY = "customSecondary";
    public static final String CUSTOM_TERTIARY = "customTertiary";
    public static final String TIME_MODE = "timeMode";
    public static final String BRIGHTNESS = "brightnessPercent";
    public static final String FXAA = "fxaa";
    public static final String PAN_ENABLED = "panEnabled";
    public static final String PAN_DISTANCE = "panDistancePercent";
    public static final String NAVIGATION_PARTICLES = "navigationParticles";
    public static final String PHONE_MOTION = "phoneMotion";
    public static final String MOTION_STRENGTH = "motionStrengthPercent";
    public static final String SCREENSAVER_BACKGROUND = "screensaverBackgroundPercent";
    public static final String SCREENSAVER_WAVE = "screensaverWavePercent";
    public static final String SCREENSAVER_SPARKLE = "screensaverSparklePercent";

    public final int fps, resolutionPercent, mesh, particleCount;
    public final int waveSpeedPercent, wavePositionPercent, scrollStrengthPercent, panDistancePercent, colourMonth, timeMode, brightnessPercent;
    public final int motionStrengthPercent;
    public final int lastMonth, customPrimary, customSecondary, customTertiary;
    public final int screensaverBackgroundPercent, screensaverWavePercent, screensaverSparklePercent;
    public final boolean fxaa, panEnabled, navigationParticles, phoneMotion;

    public static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("wallpaper", Context.MODE_PRIVATE);
    }

    public static WallpaperSettings read(SharedPreferences preferences) {
        return new WallpaperSettings(preferences.getAll());
    }

    private WallpaperSettings(Map<String, ?> values) {
        fps = choice(values, FPS, 60, 30, 60);
        resolutionPercent = choice(values, RESOLUTION, 100, 50, 75, 100);
        mesh = choice(values, MESH, 128, 64, 128);
        particleCount = choice(values, PARTICLES, 2000, 0, 500, 1000, 2000);
        waveSpeedPercent = range(values, WAVE_SPEED, 100, 25, 200);
        wavePositionPercent = range(values, WAVE_POSITION, 40, 20, 80);
        scrollStrengthPercent = range(values, SCROLL_STRENGTH, 20, 0, 100);
        panDistancePercent = range(values, PAN_DISTANCE, 30, 0, 100);
        colourMonth = range(values, COLOUR_MONTH, 0, 0, COLOUR_CUSTOM);
        lastMonth = range(values, LAST_MONTH, colourMonth >= 1 && colourMonth <= 12 ? colourMonth : 1, 1, 12);
        customPrimary = number(values, CUSTOM_PRIMARY, SystemPalette.FALLBACK_PRIMARY) | 0xff000000;
        customSecondary = number(values, CUSTOM_SECONDARY, SystemPalette.FALLBACK_SECONDARY) | 0xff000000;
        customTertiary = number(values, CUSTOM_TERTIARY, SystemPalette.FALLBACK_TERTIARY) | 0xff000000;
        timeMode = range(values, TIME_MODE, 0, 0, 2);
        brightnessPercent = range(values, BRIGHTNESS, 100, 25, 100);
        fxaa = flag(values, FXAA, true);
        panEnabled = flag(values, PAN_ENABLED, true);
        navigationParticles = flag(values, NAVIGATION_PARTICLES, true);
        phoneMotion = flag(values, PHONE_MOTION, true);
        motionStrengthPercent = range(values, MOTION_STRENGTH, 35, 0, 100);
        screensaverBackgroundPercent = range(values, SCREENSAVER_BACKGROUND, 25, 0, 100);
        screensaverWavePercent = range(values, SCREENSAVER_WAVE, 25, 0, 100);
        screensaverSparklePercent = range(values, SCREENSAVER_SPARKLE, 25, 0, 100);
    }

    private static int number(Map<String, ?> values, String key, int fallback) {
        Object value = values.get(key);
        return value instanceof Integer ? (Integer) value : fallback;
    }

    private static int choice(Map<String, ?> values, String key, int fallback, int... choices) {
        int value = number(values, key, fallback);
        for (int allowed : choices) if (value == allowed) return value;
        return fallback;
    }

    private static int range(Map<String, ?> values, String key, int fallback, int low, int high) {
        return Math.max(low, Math.min(high, number(values, key, fallback)));
    }

    private static boolean flag(Map<String, ?> values, String key, boolean fallback) {
        Object value = values.get(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }
}
