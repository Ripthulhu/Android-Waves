/* LG-XMB, 2026. SPDX-License-Identifier: GPL-3.0-or-later */
package org.lgxmb.wallpaper;

/** Camera placement shared by wave projection, particle projection and clipping. */
final class SceneProjection {
    static float particleSpread(int width, int height) {
        // No change in landscape. Tall portrait doubles post-birth Y travel,
        // while the birth point and the dimensions of every sparkle stay put.
        return Math.max(0, Math.min(1, (float) height / Math.max(1, width) - 1));
    }

    static float lift(int positionPercent) {
        return (.5f - positionPercent / 100f) * 2;
    }

    static void particleRows(float[] original, float[] result, float aspect, float pan, float lift) {
        System.arraycopy(original, 0, result, 0, 16);
        for (int i = 0; i < 4; i++) {
            result[i] = original[i] * aspect + pan * original[12 + i];
            result[4 + i] = original[4 + i] + lift * original[12 + i];
        }
    }

    static void waveBand(float[] controls, float lift, int height, float[] result) {
        float low = 1, high = -1;
        for (int i = 0; i < controls.length; i += 4) {
            if (controls[i + 3] <= .00001f) { result[0] = 0; result[1] = 1; return; }
            float y = controls[i + 1] / controls[i + 3];
            low = Math.min(low, y);
            high = Math.max(high, y);
        }
        float margin = 24f / Math.max(1, height);
        result[0] = Math.max(0, (low + lift + 1) * .5f - margin);
        result[1] = Math.min(1, (high + lift + 1) * .5f + margin);
    }
}
