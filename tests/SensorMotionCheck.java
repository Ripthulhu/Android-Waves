package org.lgxmb.wallpaper;

public final class SensorMotionCheck {
    private static final long START = 1_000_000_000L;
    private static final float G = 9.81f;

    private static void near(float actual, float expected, float tolerance) {
        if (!Float.isFinite(actual) || Math.abs(actual - expected) > tolerance)
            throw new AssertionError(actual + " != " + expected);
    }

    private static void neutral(PhoneMotion motion) {
        near(motion.tiltX(), 0, .0001f);
        near(motion.tiltY(), 0, .0001f);
        near(motion.shake(), 0, .0001f);
    }

    private static float tilted(int rotation, int hz) {
        PhoneMotion motion = new PhoneMotion();
        motion.start(rotation, START);
        motion.gravity(0, 0, G, START);
        float amount = (float) Math.sin(.3), z = (float) Math.cos(.3);
        for (int event = 1; event <= hz; event++) {
            long time = START + Math.round(event * 1_000_000_000d / hz);
            float x = 0, y = 0;
            if (rotation == 0) x = amount;
            else if (rotation == 1) y = amount;
            else if (rotation == 2) x = -amount;
            else y = -amount;
            motion.gravity(x * G, y * G, z * G, time);
            motion.sample(time);
        }
        near(motion.tiltY(), 0, .0001f);
        assert motion.tiltX() > .5f && motion.tiltX() < 1;
        return motion.tiltX();
    }

    public static void main(String[] args) {
        PhoneMotion motion = new PhoneMotion();
        motion.gravity(G, 0, 0, START);
        motion.linearAcceleration(30, 0, 0, START);
        motion.sample(START + 1_000_000_000L);
        neutral(motion); // Dormant filters cannot react to queued callbacks.

        motion.start(0, START);
        motion.gravity(0, 0, G, START);
        motion.linearAcceleration(10, 0, 0, START);
        motion.sample(START + 20_000_000L);
        neutral(motion); // First samples define neutral, even while picking up the phone.
        for (int event = 1; event <= 50; event++) {
            long time = START + event * 20_000_000L;
            motion.gravity(.02f, -.02f, G, time);
            motion.linearAcceleration(.2f, .2f, 0, time);
            motion.sample(time);
        }
        neutral(motion);

        float portrait = tilted(0, 60);
        for (int rotation = 1; rotation < 4; rotation++) near(tilted(rotation, 60), portrait, .0001f);
        near(tilted(0, 30), portrait, .0001f);
        near(tilted(0, 50), portrait, .0001f);

        // Forward tilt must respond in the usual upright holding pose, using gravity Z.
        motion.start(0, START);
        motion.gravity(0, G, 0, START);
        for (int event = 1; event <= 50; event++) {
            long time = START + event * 20_000_000L;
            motion.gravity(0, G * (float) Math.cos(.3), G * (float) Math.sin(.3), time);
            motion.sample(time);
        }
        near(motion.tiltX(), 0, .0001f);
        near(motion.tiltY(), -portrait, .0001f);
        float held = motion.tiltY();
        for (int event = 51; event <= 550; event++) {
            long time = START + event * 20_000_000L;
            motion.gravity(0, G * (float) Math.cos(.3), G * (float) Math.sin(.3), time);
            motion.sample(time);
        }
        near(motion.tiltY(), held, .0001f); // Holding a pose cannot integrate into drift.

        motion.stop();
        neutral(motion);
        motion.gravity(G, 0, 0, START + 20_000_000_000L);
        motion.sample(START + 21_000_000_000L);
        neutral(motion);
        long resumed = START + 30_000_000_000L;
        motion.start(1, resumed);
        motion.gravity(G, 0, 0, resumed - 1); // Old session callback must not set the new neutral pose.
        motion.linearAcceleration(30, 0, 0, resumed - 1);
        motion.gravity(0, 0, G, resumed);
        motion.linearAcceleration(0, 0, 0, resumed);
        motion.sample(resumed + 20_000_000L);
        neutral(motion);
        motion.gravity(Float.NaN, 0, 1, resumed + 21_000_000L);
        motion.gravity(0, 0, 0, resumed + 22_000_000L);
        motion.linearAcceleration(Float.POSITIVE_INFINITY, 0, 0, resumed + 23_000_000L);
        motion.sample(resumed + 40_000_000L);
        neutral(motion);

        for (int event = 1; event <= 10; event++) {
            long time = resumed + event * 20_000_000L;
            motion.linearAcceleration(30, 0, 0, time);
            motion.sample(time);
        }
        assert motion.shake() > .8f && motion.shake() <= 1;
        for (int frame = 11; frame <= 110; frame++) motion.sample(resumed + frame * 20_000_000L);
        near(motion.shake(), 0, .0001f); // A silent sensor cannot leave shake stuck on.

        motion.start(0, START);
        for (int event = 0; event < 50; event++) {
            long time = START + event * 20_000_000L;
            motion.accelerometer(0, 0, G, time, true, true);
            motion.sample(time);
        }
        neutral(motion); // Accelerometer-only phones subtract stationary gravity.
        for (int event = 50; event < 55; event++) {
            long time = START + event * 20_000_000L;
            motion.accelerometer(8, 0, G, time, true, true);
            motion.sample(time);
        }
        assert motion.shake() > .5f && motion.shake() <= 1;
        for (int event = 55; event < 255; event++) {
            long time = START + event * 20_000_000L;
            motion.accelerometer(0, 0, G, time, true, true);
            motion.sample(time);
        }
        near(motion.shake(), 0, .001f);
        near(motion.tiltX(), 0, .001f);
        System.out.println("Phone motion checks passed");
    }
}
