/* LG-XMB, 2026. SPDX-License-Identifier: GPL-3.0-or-later */
package org.lgxmb.wallpaper;

/** Phone-input adaptation around the recovered PS3 rshake particle gain.
 * Sensor normalization, smoothing, limits and wave coupling are Android policy;
 * they are not a reconstruction of the controller's physical input channels.
 */
final class PhoneMotionResponse {
    private boolean enabled;
    private float strength, targetX, targetY, targetShake, x, y, shake;
    float waveX, waveY, waveShake, particleAgitation;

    void configure(boolean enabled, float strength) {
        this.strength = Float.isFinite(strength) ? Math.max(0, Math.min(1, strength)) : 0;
        this.enabled = enabled && this.strength > 0;
        if (!this.enabled) reset();
    }

    void setInput(float tiltX, float tiltY, float movement) {
        if (!enabled || !Float.isFinite(tiltX) || !Float.isFinite(tiltY) || !Float.isFinite(movement)) return;
        targetX = Math.max(-1, Math.min(1, tiltX));
        targetY = Math.max(-1, Math.min(1, tiltY));
        targetShake = Math.max(0, Math.min(1, movement));
    }

    void reset() {
        targetX = targetY = targetShake = x = y = shake = 0;
        waveX = waveY = waveShake = particleAgitation = 0;
    }

    void advance(float seconds) {
        if (!enabled || !(seconds > 0) || !Float.isFinite(seconds)) return;
        float dt = Math.min(.1f, seconds), blend = 1 - (float) Math.exp(-12 * dt);
        float oldX = x, oldY = y;
        x += (targetX - x) * blend;
        y += (targetY - y) * blend;
        shake += (targetShake - shake) * blend;
        if (Math.abs(targetX - x) < .0001f) x = targetX;
        if (Math.abs(targetY - y) < .0001f) y = targetY;
        if (Math.abs(targetShake - shake) < .0001f) shake = targetShake;
        // Express filtered tilt changes per 60 Hz host interval, independent
        // of render rate. The phone's bounded shake envelope supplies Z energy.
        double dx = (x - oldX) / (60 * dt), dy = (y - oldY) / (60 * dt);
        particleAgitation = (float) Math.min(.6, recoveredAgitation(dx, dy, shake * .08f)) * strength;
        waveX = x * strength;
        waveY = y * strength;
        waveShake = shake * strength;
    }

    // custom_render_plugin 0x6a560; retained rshake brw=7.499199867248535.
    // This arithmetic is supported by disassembly + one retained input/output,
    // unlike the separately instruction-tested direction response.
    static double recoveredAgitation(double dx, double dy, double dz) {
        double sum = f(f(dx * dx) + f(dy * dy));
        sum = f(sum + f(dz * dz));
        return f(f(Math.sqrt(sum)) * f(7.499199867248535));
    }

    private static double f(double value) { return (float) value; }
}
