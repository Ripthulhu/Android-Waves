package org.lgxmb.wallpaper;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/** Host check; Android's interface is mocked without a framework dependency. */
public final class WallpaperSettingsCheck {
    private static WallpaperSettings read(Map<String, Object> values) {
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getAll")) return new HashMap<>(values);
                    throw new AssertionError("Unexpected preference call: " + method.getName());
                });
        return WallpaperSettings.read(preferences);
    }

    public static void main(String[] args) {
        Map<String, Object> values = new HashMap<>();
        WallpaperSettings defaults = read(values);
        check(defaults.fps == 60 && defaults.resolutionPercent == 100 && defaults.mesh == 128,
                "High resolution and 60 fps must be the defaults");
        check(defaults.particleCount == 2000 && defaults.waveSpeedPercent == 100
                && defaults.wavePositionPercent == 40 && defaults.scrollStrengthPercent == 20
                && defaults.panDistancePercent == 30 && defaults.brightnessPercent == 100,
                "Unexpected motion or brightness defaults");
        check(defaults.colourMonth == 0 && defaults.timeMode == 0 && defaults.fxaa && defaults.panEnabled,
                "Seasonal colour, clock, FXAA and scrolling must be enabled by default");
        check(defaults.navigationParticles, "Navigation particles must be enabled by default");
        check(defaults.screensaverBackgroundPercent == 25 && defaults.screensaverWavePercent == 25
                && defaults.screensaverSparklePercent == 25, "Screen saver defaults match the TV dim levels");
        values.put(WallpaperSettings.SCREENSAVER_BACKGROUND, 0);
        values.put(WallpaperSettings.SCREENSAVER_WAVE, 50);
        values.put(WallpaperSettings.SCREENSAVER_SPARKLE, 100);
        check(read(values).screensaverBackgroundPercent == 0 && read(values).screensaverWavePercent == 50
                && read(values).screensaverSparklePercent == 100, "Screen saver levels must remain independent");
        values.put(WallpaperSettings.SCREENSAVER_BACKGROUND, -1);
        values.put(WallpaperSettings.SCREENSAVER_WAVE, 101);
        values.put(WallpaperSettings.SCREENSAVER_SPARKLE, "100");
        check(read(values).screensaverBackgroundPercent == 0 && read(values).screensaverWavePercent == 100
                && read(values).screensaverSparklePercent == 25, "Invalid screen saver levels must be safe");
        check(defaults.phoneMotion && defaults.motionStrengthPercent == 35, "Phone motion defaults must stay gentle");
        values.put("phoneMotion", false); values.put("motionStrengthPercent", 80);
        check(!read(values).phoneMotion && read(values).motionStrengthPercent == 80, "Phone motion settings must survive");
        values.put("phoneMotion", "true"); values.put("motionStrengthPercent", "80");
        check(read(values).phoneMotion && read(values).motionStrengthPercent == 35, "Invalid motion types use defaults");
        values.put("motionStrengthPercent", -1);
        check(read(values).motionStrengthPercent == 0, "Zero strength must be supported");
        values.put("motionStrengthPercent", 101);
        check(read(values).motionStrengthPercent == 100, "Motion strength must stay bounded");

        values.put("fps", 30); values.put("resolutionPercent", 75); values.put("mesh", 64);
        values.put("particleCount", 500); values.put("waveSpeedPercent", 175);
        values.put("wavePositionPercent", 50);
        values.put("scrollStrengthPercent", 0); values.put("panDistancePercent", 100); values.put("colourMonth", 12);
        values.put("timeMode", 2); values.put("brightnessPercent", 55);
        values.put("fxaa", false); values.put("panEnabled", false);
        values.put("navigationParticles", false);
        WallpaperSettings selected = read(values);
        check(selected.fps == 30 && selected.resolutionPercent == 75 && selected.mesh == 64
                && selected.particleCount == 500 && selected.waveSpeedPercent == 175 && selected.wavePositionPercent == 50
                && selected.scrollStrengthPercent == 0 && selected.panDistancePercent == 100 && selected.colourMonth == 12
                && selected.timeMode == 2 && selected.brightnessPercent == 55
                && !selected.fxaa && !selected.panEnabled && !selected.navigationParticles, "Valid selections must survive");
        values.put("fps", 120);
        check(selected.fps == 30 && read(values).fps == 60, "Snapshots must be immutable; invalid choices use defaults");
        values.put(WallpaperSettings.COLOUR_MONTH, WallpaperSettings.COLOUR_SYSTEM);
        check(read(values).colourMonth == WallpaperSettings.COLOUR_SYSTEM, "System colours must survive saving");
        values.put(WallpaperSettings.COLOUR_MONTH, "13");
        check(read(values).colourMonth == 0, "Invalid colour types must keep the Seasonal default");

        values.put("fps", "60"); values.put("resolutionPercent", 50L); values.put("mesh", true);
        values.put("particleCount", 123); values.put("waveSpeedPercent", -1);
        values.put("wavePositionPercent", -1);
        values.put("scrollStrengthPercent", 999); values.put("panDistancePercent", -3); values.put("colourMonth", -3);
        values.put("timeMode", 9); values.put("brightnessPercent", 0);
        values.put("fxaa", "false"); values.put("panEnabled", 0);
        values.put("navigationParticles", "false");
        WallpaperSettings sanitized = read(values);
        check(sanitized.fps == 60 && sanitized.resolutionPercent == 100 && sanitized.mesh == 128
                && sanitized.particleCount == 2000 && sanitized.fxaa && sanitized.panEnabled && sanitized.navigationParticles,
                "Wrong stored types must use defaults without throwing");
        check(sanitized.waveSpeedPercent == 25 && sanitized.wavePositionPercent == 20
                && sanitized.scrollStrengthPercent == 100 && sanitized.panDistancePercent == 0
                && sanitized.colourMonth == 0 && sanitized.timeMode == 2 && sanitized.brightnessPercent == 25,
                "Out-of-range values must stay within supported bounds");
        values.put("panDistancePercent", 101);
        check(read(values).panDistancePercent == 100, "Pan distance must not exceed the scene width");
        values.put("panDistancePercent", "30");
        check(read(values).panDistancePercent == 30, "Wrong pan distance types must use the subtle default");
        values.put("wavePositionPercent", 101);
        check(read(values).wavePositionPercent == 80, "Wave position must stay within the screen bounds");
        values.put("wavePositionPercent", "50");
        check(read(values).wavePositionPercent == 40, "Wrong wave position types must use the higher default");
        System.out.println("Wallpaper settings checks passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
