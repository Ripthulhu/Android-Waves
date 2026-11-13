package org.lgxmb.wallpaper;

public final class SceneProjectionCheck {
    private static void near(float actual, float expected) {
        if (Math.abs(actual - expected) > .00001f) throw new AssertionError(actual + " != " + expected);
    }
    private static float row(float[] matrix, int row, float[] point) {
        float sum = 0;
        for (int i = 0; i < 4; i++) sum += matrix[row * 4 + i] * point[i];
        return sum;
    }

    public static void main(String[] args) {
        near(SceneProjection.lift(40), .2f);
        near(SceneProjection.lift(50), 0);
        near(SceneProjection.lift(20), .6f);
        near(SceneProjection.lift(80), -.6f);
        float[] original = {1.2f, .1f, .2f, .3f, .2f, 1.7f, .1f, -.1f,
                0, .2f, .8f, -.3f, .1f, .2f, .7f, 1.4f};
        float[] projected = new float[16];
        float[] point = {.3f, -.7f, .5f, 1};
        for (float lift : new float[]{-.6f, 0, .2f, .6f}) {
            for (float pan : new float[]{-.9f, 0, .9f}) {
                SceneProjection.particleRows(original, projected, 4, pan, lift);
                float w = row(original, 3, point);
                // Particle centres and wave clip coordinates must undergo the
                // same screen-space translation, including perspective W.
                near(row(projected, 0, point) / w, row(original, 0, point) / w * 4 + pan);
                near(row(projected, 1, point) / w, row(original, 1, point) / w + lift);
                near(row(projected, 2, point), row(original, 2, point));
                near(row(projected, 3, point), w);
            }
        }
        float[] controls = {0, -.4f, 0, 1, 2, 1.4f, 0, 2, -.3f, -1.2f, 0, 3};
        float[] band = new float[2];
        for (float lift : new float[]{-.6f, 0, .2f, .6f}) {
            SceneProjection.waveBand(controls, lift, 2400, band);
            for (int i = 0; i < controls.length; i += 4) {
                float screen = (controls[i + 1] / controls[i + 3] + lift + 1) * .5f;
                if (screen >= 0 && screen <= 1)
                    assert screen >= band[0] && screen <= band[1] : "Raised wave must stay inside compositor band";
            }
        }
        SceneProjection.waveBand(controls, .2f, 2400, band);
        near(band[0], .39f); near(band[1], .96f);
        controls[3] = 0;
        SceneProjection.waveBand(controls, .2f, 2400, band);
        near(band[0], 0); near(band[1], 1);
        System.out.println("Scene projection checks passed");
    }
}
