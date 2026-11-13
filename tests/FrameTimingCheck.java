package org.lgxmb.wallpaper;

public final class FrameTimingCheck {
    public static void main(String[] args) {
        FrameTiming clock = new FrameTiming();
        assert clock.deltaSeconds(1_000_000_000L) == 0;
        assert Math.abs(clock.deltaSeconds(1_033_333_333L) - 1f / 30) < 0.00001f;
        clock.pause();
        assert clock.deltaSeconds(9_000_000_000L) == 0 : "Hidden time must not be simulated";
        assert clock.deltaSeconds(10_000_000_000L) == 0.1f : "Bound slow frames";
        assert clock.deltaSeconds(9_000_000_000L) == 0;
        for (int fps : new int[] {30, 60}) {
            for (float refresh : new float[] {59.94f, 60, 90, 119.88f, 120}) {
                FrameTiming pacing = new FrameTiming();
                pacing.setFramesPerSecond(fps);
                pacing.setRefreshRate(refresh);
                int frames = 0;
                for (int i = 0; i < refresh * 10; i++) {
                    long vsync = 1_000_000_000L + Math.round(i * 1_000_000_000d / refresh);
                    if (pacing.shouldRender(vsync)) frames++;
                }
                assert Math.abs(frames - fps * 10) <= 1 : fps + " fps at " + refresh + " Hz: " + frames;
            }
        }
        FrameTiming pacing = new FrameTiming();
        pacing.setRefreshRate(120);
        assert pacing.shouldRender(1_000_000_000L);
        assert !pacing.shouldRender(1_008_333_333L);
        assert pacing.shouldRender(1_016_666_667L);
        assert pacing.shouldRender(2_000_000_000L) : "Resume after a missed deadline";
        assert !pacing.shouldRender(2_008_333_333L) : "Do not catch up missed frames";
        pacing.pause();
        assert pacing.shouldRender(9_000_000_000L);
        assert pacing.deltaSeconds(9_000_000_000L) == 0;
        pacing.setFramesPerSecond(30);
        assert pacing.shouldRender(10_000_000_000L);
        assert !pacing.shouldRender(10_016_666_667L);
        assert pacing.shouldRender(10_033_333_333L);
        System.out.println("Frame timing checks passed");
    }
}
