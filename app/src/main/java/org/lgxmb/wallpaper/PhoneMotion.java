// SPDX-License-Identifier: GPL-3.0-or-later
package org.lgxmb.wallpaper;

/** Sensor filtering in screen coordinates; timestamps use elapsed realtime nanoseconds. */
final class PhoneMotion {
    private boolean active, gravityReady, accelerationReady, rawReady;
    private int rotation;
    private long startedNs, frameNs, gravityNs, accelerationNs, rawNs;
    private float axisXX, axisXY, axisXZ, axisYX, axisYY, axisYZ;
    private float rawGravityX, rawGravityY, rawGravityZ;
    private float targetX, targetY, targetShake;
    private float tiltX, tiltY, shake;

    void start(int displayRotation, long nowNs) {
        stop();
        active = true;
        rotation = displayRotation & 3;
        startedNs = frameNs = nowNs;
    }

    void stop() {
        active = gravityReady = accelerationReady = rawReady = false;
        targetX = targetY = targetShake = tiltX = tiltY = shake = 0;
    }

    void gravity(float x, float y, float z, long timestampNs) {
        if (!valid(x, y, z, timestampNs) || (gravityReady && timestampNs <= gravityNs)) return;
        float screenX = x, screenY = y;
        if (rotation == 1) { screenX = y; screenY = -x; }
        else if (rotation == 2) { screenX = -x; screenY = -y; }
        else if (rotation == 3) { screenX = -y; screenY = x; }
        float length = (float) Math.sqrt((double) x * x + (double) y * y + (double) z * z);
        if (!Float.isFinite(length) || length < .01f) return;
        x = screenX / length;
        y = screenY / length;
        z /= length;
        if (!gravityReady || timestampNs - gravityNs > 500_000_000L) {
            // A tangent basis avoids Euler-angle jumps when held upright or face up.
            axisXX = 1 - x * x;
            axisXY = -x * y;
            axisXZ = -x * z;
            float norm = (float) Math.sqrt(axisXX * axisXX + axisXY * axisXY + axisXZ * axisXZ);
            if (norm < .01f) {
                axisXX = -x * y;
                axisXY = 1 - y * y;
                axisXZ = -y * z;
                norm = (float) Math.sqrt(axisXX * axisXX + axisXY * axisXY + axisXZ * axisXZ);
            }
            axisXX /= norm;
            axisXY /= norm;
            axisXZ /= norm;
            axisYX = y * axisXZ - z * axisXY;
            axisYY = z * axisXX - x * axisXZ;
            axisYZ = x * axisXY - y * axisXX;
            targetX = targetY = tiltX = tiltY = 0;
            gravityReady = true;
        } else {
            targetX = tilt(x * axisXX + y * axisXY + z * axisXZ);
            targetY = tilt(x * axisYX + y * axisYY + z * axisYZ);
        }
        gravityNs = timestampNs;
    }

    void linearAcceleration(float x, float y, float z, long timestampNs) {
        if (!valid(x, y, z, timestampNs) || (accelerationReady && timestampNs <= accelerationNs)) return;
        float magnitude = (float) Math.sqrt((double) x * x + (double) y * y + (double) z * z);
        if (!Float.isFinite(magnitude)) return;
        if (!accelerationReady || timestampNs - accelerationNs > 500_000_000L) {
            targetShake = shake = 0;
            accelerationReady = true;
        } else targetShake = Math.max(0, Math.min(1, (magnitude - .8f) / 6));
        accelerationNs = timestampNs;
    }

    void accelerometer(float x, float y, float z, long timestampNs, boolean forTilt, boolean forShake) {
        if (!valid(x, y, z, timestampNs) || (rawReady && timestampNs <= rawNs)) return;
        if (!rawReady || timestampNs - rawNs > 500_000_000L) {
            rawGravityX = x;
            rawGravityY = y;
            rawGravityZ = z;
            rawReady = true;
        } else {
            float alpha = 1 - (float) Math.exp(-(timestampNs - rawNs) / 350_000_000d);
            rawGravityX += (x - rawGravityX) * alpha;
            rawGravityY += (y - rawGravityY) * alpha;
            rawGravityZ += (z - rawGravityZ) * alpha;
        }
        rawNs = timestampNs;
        if (forTilt) gravity(rawGravityX, rawGravityY, rawGravityZ, timestampNs);
        if (forShake) linearAcceleration(x - rawGravityX, y - rawGravityY, z - rawGravityZ, timestampNs);
    }

    void sample(long nowNs) {
        if (!active || nowNs <= frameNs) return;
        float seconds = Math.min(.1f, (nowNs - frameNs) / 1_000_000_000f);
        frameNs = nowNs;
        if (!gravityReady || nowNs - gravityNs > 500_000_000L) targetX = targetY = 0;
        if (!accelerationReady || nowNs - accelerationNs > 150_000_000L) targetShake = 0;
        float alpha = 1 - (float) Math.exp(-seconds / .08f);
        tiltX += (targetX - tiltX) * alpha;
        tiltY += (targetY - tiltY) * alpha;
        float shakeAlpha = 1 - (float) Math.exp(-seconds / (targetShake > shake ? .035f : .18f));
        shake += (targetShake - shake) * shakeAlpha;
        if (shake < .0001f) shake = 0;
    }

    float tiltX() { return tiltX; }
    float tiltY() { return tiltY; }
    float shake() { return shake; }

    private boolean valid(float x, float y, float z, long timestampNs) {
        return active && timestampNs >= startedNs && Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z);
    }

    private static float tilt(float component) {
        // Ignore sensor noise and reach full response at roughly a 30-degree tilt.
        float magnitude = Math.max(0, Math.min(1, (Math.abs(component) - .01f) / .49f));
        return Math.copySign(magnitude, component);
    }
}
