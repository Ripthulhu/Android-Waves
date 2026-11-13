package org.lgxmb.wallpaper;

public final class ScrollMotionCheck {
    private static void near(float actual, float expected) {
        if (Math.abs(actual - expected) > .0001f) throw new AssertionError(actual + " != " + expected);
    }

    private static void navigation(ScrollMotion motion, float offset, float step, int direction) {
        motion.setOffset(offset, step);
        int actual = motion.consumeNavigationDirection();
        assert actual == direction : "Offset " + offset + ", step " + step + ": " + actual + " != " + direction;
        assert motion.consumeNavigationDirection() == 4 : "Consume each accepted change once";
    }

    private static void navigationChecks() {
        ScrollMotion pages = new ScrollMotion();
        navigation(pages, .5f, .5f, 4); // Initial middle page is not a navigation action.
        navigation(pages, 0, .5f, 0);
        navigation(pages, .5f, .5f, 1);
        navigation(pages, 1, .5f, 1);
        navigation(pages, .5f, .5f, 0);
        navigation(pages, 0, .5f, 0);
        navigation(pages, 0, .5f, 4);

        // Noise around the midpoint cannot retrigger a change or its reversal.
        for (float offset : new float[] {.249f, .251f, .295f, .27f})
            navigation(pages, offset, .5f, 4);
        navigation(pages, .31f, .5f, 1);
        for (float offset : new float[] {.29f, .249f, .251f, .205f, .26f})
            navigation(pages, offset, .5f, 4);
        navigation(pages, .19f, .5f, 0);
        navigation(pages, .31f, .5f, 1);

        navigation(pages, .5f, .25f, 4); // A changed page layout establishes a new baseline.
        navigation(pages, .75f, .25f, 1);
        navigation(pages, .5f, .25f, 0);
        for (float invalid : new float[] {0, -.5f, 2, Float.NaN, Float.POSITIVE_INFINITY}) {
            navigation(pages, 0, invalid, 4);
            navigation(pages, 1, invalid, 4);
        }
        navigation(pages, 1, .5f, 4); // No guessed navigation when offsets regain a valid step.
        navigation(pages, .5f, .5f, 0);
        navigation(pages, 1, 1e-12f, 4);
        for (int frame = 0; frame < 3; frame++) navigation(pages, 1, 1e-12f, 4);
        navigation(pages, .5f, .5f, 4);
        pages.setOffset(1, .5f);
        pages.reset();
        assert pages.consumeNavigationDirection() == 4 : "Hide/reset clears pending navigation";
        navigation(pages, 0, .5f, 4); // First post-reset callback baselines without an impulse.
        navigation(pages, .5f, .5f, 1);
        pages.setOffset(Float.NaN, .5f);
        assert pages.consumeNavigationDirection() == 4;

        ScrollMotion coalesced = new ScrollMotion();
        navigation(coalesced, 0, .5f, 4);
        navigation(coalesced, .755f, .5f, 1); // Page 1 is accepted; page 2 is still in its dead band.
        navigation(coalesced, .805f, .5f, 1);
        navigation(coalesced, .245f, .5f, 0); // Same rule for a fast reversal.
        navigation(coalesced, .195f, .5f, 0);

        for (int fps : new int[] {30, 60}) {
            ScrollMotion swipe = new ScrollMotion();
            int left = 0, right = 0;
            for (int frame = 0; frame <= fps * 2; frame++) {
                float time = frame / (float) fps;
                swipe.setOffset(time <= 1 ? time : 2 - time, .5f);
                int direction = swipe.consumeNavigationDirection();
                if (direction == 0) left++;
                if (direction == 1) right++;
                swipe.advance(1f / fps);
            }
            assert left == 2 && right == 2 : "Same four page changes at " + fps + " fps: " + left + "/" + right;
        }
    }

    public static void main(String[] args) {
        navigationChecks();
        ScrollMotion motion = new ScrollMotion();
        motion.setOffset(.5f);
        near(motion.pan(4, true, 1), 0); near(motion.speed(1), 1);
        motion.setOffset(1);
        motion.advance(1f / 60);
        assert motion.pan(4, true, 1) < 0 && motion.pan(4, true, 1) > -3 : "Ease toward the next page";
        assert motion.speed(1) > 1 && motion.speed(1) <= 1.45f;
        near(motion.speed(0), 1);
        for (int i = 0; i < 180; i++) { motion.setOffset(1); motion.advance(1f / 60); }
        near(motion.pan(4, true, 1), -3); near(motion.speed(1), 1);
        near(motion.pan(4, true, .3f), 1.2f);
        near(motion.pan(4, true, 0), 3);
        near(motion.pan(4, false, .3f), 0);
        near(motion.pan(.8f, true, 1), 0);
        motion.setOffset(0); motion.reset();
        near(-4 + motion.pan(4, true, 1), -1); // Original left edge meets the viewport.
        near(motion.pan(4, true, .3f), 3);
        // Every distance starts at the same visible left edge, at any aspect.
        for (float aspect : new float[]{1, 16f / 9f / (1080f / 2404), 4}) {
            for (float distance : new float[]{0, .3f, 1})
                near(-aspect + motion.pan(aspect, true, distance), -1);
        }
        near(motion.speed(1), 1);
        motion.setOffset(.5f); motion.reset();
        near(motion.pan(4, true, .3f), 2.1f);
        motion.setOffset(1); motion.reset();
        // Default travel remains 1.8 clip units across all three pages.
        near(3 - motion.pan(4, true, .3f), 1.8f);
        near(4 + motion.pan(4, true, 1), 1); // Original right edge meets the viewport.
        motion.setOffset(Float.NaN); motion.advance(0); motion.advance(Float.NaN);
        near(motion.pan(4, true, 1), -3); near(motion.speed(1), 1);

        ScrollMotion thirty = new ScrollMotion(), sixty = new ScrollMotion();
        thirty.setOffset(0); sixty.setOffset(0);
        thirty.setOffset(1); sixty.setOffset(1);
        for (int i = 0; i < 6; i++) thirty.advance(1f / 30);
        for (int i = 0; i < 12; i++) sixty.advance(1f / 60);
        near(thirty.pan(4, true, .3f), sixty.pan(4, true, .3f));
        assert Math.abs(thirty.speed(1) - sixty.speed(1)) < .02f : "Similar response at 30/60 fps";
        thirty.reset();
        near(thirty.pan(4, true, 1), -3); near(thirty.speed(1), 1);
        System.out.println("Scroll motion checks passed");
    }
}
