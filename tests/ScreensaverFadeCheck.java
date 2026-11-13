package org.lgxmb.wallpaper;

public final class ScreensaverFadeCheck {
    private static void near(double actual, double expected, double tolerance) {
        if (!Double.isFinite(actual) || Math.abs(actual - expected) > tolerance)
            throw new AssertionError(actual + " != " + expected);
    }
    private static void full(ScreensaverFade fade) {
        near(fade.background, 1, 0); near(fade.wave, 1, 0); near(fade.sparkles, 1, 0);
    }
    private static ScreensaverFade entered(int hz) {
        ScreensaverFade fade = new ScreensaverFade();
        fade.configure(0, 25, 100);
        fade.setActive(true);
        full(fade);
        float previousBackground = 1, previousWave = 1;
        for (int frame = 0; frame < hz * 1.2; frame++) {
            fade.advance(1f / hz);
            assert fade.background <= previousBackground && fade.background >= 0;
            assert fade.wave <= previousWave && fade.wave >= .25f;
            near(fade.sparkles, 1, 0);
            previousBackground = fade.background; previousWave = fade.wave;
        }
        near(fade.background, 0, 0); near(fade.wave, .25f, 0);
        assert !fade.isFading();
        return fade;
    }
    public static void main(String[] args) {
        assert ScreensaverFade.ENTER_MILLIS == 1200 && ScreensaverFade.EXIT_MILLIS == 160;
        ScreensaverFade wallpaper = new ScreensaverFade();
        wallpaper.configure(0, 0, 0);
        for (int frame = 0; frame < 600; frame++) wallpaper.advance(1f / 60);
        full(wallpaper); assert !wallpaper.isFading();
        wallpaper.setActive(false); full(wallpaper);

        ScreensaverFade slow = entered(30), fast = entered(60);
        near(slow.background, fast.background, 0); near(slow.wave, fast.wave, 0);
        slow.setActive(false);
        float previous = slow.background;
        for (int frame = 0; frame < 5; frame++) {
            slow.advance(1f / 30);
            assert slow.background >= previous && slow.background <= 1;
            previous = slow.background;
        }
        full(slow); assert !slow.isFading();

        ScreensaverFade reversal = new ScreensaverFade();
        reversal.setActive(true);
        reversal.advance(.6f);
        near(reversal.background, .625, .000001); // Halfway through TV smoothstep.
        float before = reversal.background;
        reversal.setActive(false);
        near(reversal.background, before, 0);
        reversal.advance(.08f);
        near(reversal.background, (before + 1) / 2, .000001);
        reversal.advance(.081f); full(reversal);

        fast.setActive(true); assert !fast.isFading(); // Repeated activation cannot restart a fade.
        fast.configure(75, 50, 0);
        near(fast.background, 0, 0); near(fast.wave, .25f, 0); near(fast.sparkles, 1, 0);
        fast.advance(.6f);
        near(fast.background, .375, .000001); near(fast.wave, .375, .000001); near(fast.sparkles, .5, .000001);
        float midway = fast.background;
        fast.configure(75, 50, 0); // Same settings cannot restart the interpolation.
        fast.advance(.6f);
        near(fast.background, .75f, 0); near(fast.wave, .5f, 0); near(fast.sparkles, 0, 0);
        assert !fast.isFading() && fast.background > midway;
        fast.configure(-40, 200, 100); fast.advance(2);
        near(fast.background, 0, 0); near(fast.wave, 1, 0); near(fast.sparkles, 1, 0);
        fast.setActive(false);
        fast.advance(Float.NaN); fast.advance(Float.POSITIVE_INFINITY); fast.advance(-1);
        near(fast.background, 0, 0);
        fast.advance(.161f); full(fast);

        ScreensaverFade rate30 = new ScreensaverFade(), rate60 = new ScreensaverFade();
        rate30.setActive(true); rate60.setActive(true);
        for (int frame = 0; frame < 36; frame++) {
            rate30.advance(1f / 30);
            rate60.advance(1f / 60); rate60.advance(1f / 60);
            near(rate30.background, rate60.background, .000001);
        }
        System.out.println("Screensaver fade checks passed: TV timings, independent levels, wallpaper unchanged, retargeting and 30/60 fps");
    }
}
