package org.lgxmb.wallpaper;

import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;

/** Phone policy checks plus a retained native rshake input/output fixture. */
public final class PhoneMotionResponseCheck {
    private static void near(double actual, double expected, double tolerance) {
        if (!Double.isFinite(actual) || Math.abs(actual - expected) > tolerance)
            throw new AssertionError(actual + " != " + expected);
    }
    private static void equal(float[] first, float[] second, int count) {
        for (int i = 0; i < count; i++)
            if (Float.floatToRawIntBits(first[i]) != Float.floatToRawIntBits(second[i]))
                throw new AssertionError("Idle changed at " + i);
    }
    private static PhoneMotionResponse ramp(int hz) {
        PhoneMotionResponse response = new PhoneMotionResponse();
        response.configure(true, .35f);
        for (int i = 1; i <= hz; i++) {
            response.setInput((float) i / hz, 0, 0);
            response.advance(1f / hz);
        }
        return response;
    }
    private static void finite(WaveSimulation wave, ParticleSimulation particles) {
        for (float value : wave.controls) assert Float.isFinite(value) && Math.abs(value) < 100;
        for (int i = 0; i < particles.instanceCount * 8; i++) assert Float.isFinite(particles.instances[i]);
    }
    public static void main(String[] args) throws Exception {
        // outputs/ps3-shake-response-review-2026-09-19.md: newest sensor
        // difference 1/512, retained rshake output 0.014646874740719795.
        near(PhoneMotionResponse.recoveredAgitation(0, 0, .001953125), .014646874740719795, 0);
        near(PhoneMotionResponse.recoveredAgitation(-.001953125, 0, 0), .014646874740719795, 0);
        near(PhoneMotionResponse.recoveredAgitation(0, 0, 0), 0, 0);

        PhoneMotionResponse response = new PhoneMotionResponse();
        response.configure(true, .35f);
        response.setInput(1, -.5f, 0);
        response.advance(1f / 60);
        assert response.waveX > 0 && response.waveY < 0 && response.particleAgitation > 0;
        for (int i = 0; i < 120; i++) response.advance(1f / 60);
        near(response.waveX, .35f, 0);
        near(response.waveY, -.175f, 0);
        near(response.particleAgitation, 0, 0); // A held pose must not continuously agitate sparkles.
        response.setInput(100, -100, 100);
        for (int i = 0; i < 120; i++) response.advance(1f / 60);
        near(response.waveX, .35f, 0); near(response.waveY, -.35f, 0);
        near(response.waveShake, .35f, 0);
        assert response.particleAgitation <= .6f * .35f;
        response.setInput(Float.NaN, 0, 0);
        response.advance(1f / 60);
        assert Float.isFinite(response.particleAgitation);
        response.reset();
        near(response.waveX + response.waveY + response.waveShake + response.particleAgitation, 0, 0);
        response.configure(false, 1);
        response.setInput(1, 1, 1); response.advance(.1f);
        near(response.waveX + response.waveY + response.waveShake + response.particleAgitation, 0, 0);
        response.configure(true, 0);
        response.setInput(1, 1, 1); response.advance(.1f);
        near(response.waveX + response.waveY + response.waveShake + response.particleAgitation, 0, 0);
        PhoneMotionResponse slow = ramp(30), fast = ramp(60);
        near(slow.waveX, fast.waveX, .003);
        near(slow.particleAgitation, fast.particleAgitation, .001);

        JSONObject reference = new JSONObject(Files.readString(Path.of(args[0])));
        WaveSimulation plain = new WaveSimulation(reference), wave = new WaveSimulation(reference);
        ParticleSimulation plainParticles = new ParticleSimulation(reference, plain);
        ParticleSimulation particles = new ParticleSimulation(reference, wave);
        response.configure(true, .35f);
        for (int frame = 0; frame < 120; frame++) {
            response.advance(1f / 60);
            wave.setPhoneMotion(response.waveX, response.waveY, response.waveShake);
            particles.setPhoneAgitation(response.particleAgitation);
            plain.advance(1f / 60); wave.advance(1f / 60);
            plainParticles.advance(1f / 60); particles.advance(1f / 60);
            equal(plain.controls, wave.controls, wave.controls.length);
            assert plainParticles.instanceCount == particles.instanceCount;
            equal(plainParticles.instances, particles.instances, particles.instanceCount * 8);
        }

        float[] band = new float[2];
        int alignedBirths = 0;
        double deformationRange = 0;
        for (int frame = 0; frame < 1800; frame++) {
            // Deliberately stronger and more abrupt than filtered default phone input.
            wave.setPhoneMotion(frame % 120 < 60 ? 1 : -1, frame % 90 < 45 ? 1 : -1, 1);
            particles.setPhoneAgitation(.6f);
            wave.advance(1f / 60); particles.advance(1f / 60);
            finite(wave, particles);
            SceneProjection.waveBand(wave.controls, .2f, 2404, band);
            for (int i = 0; i < wave.controls.length; i += 4) {
                float y = (wave.controls[i + 1] / wave.controls[i + 3] + 1.2f) * .5f;
                if (y >= 0 && y <= 1) assert y >= band[0] && y <= band[1] : "Motion clipped by wave band";
            }
            for (int i = 0; i < particles.instanceCount; i++) {
                if (particles.instances[i * 8 + 3] == 0) {
                    near(particles.instances[i * 8 + 1], particles.birthHeights[i], 0);
                    alignedBirths++;
                }
            }
            if (frame == 119) {
                for (int i = 0; i < 120; i++) plain.advance(1f / 60);
                double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
                for (int i = 0; i < wave.controls.length; i += 4) {
                    double difference = wave.controls[i + 1] / wave.controls[i + 3]
                        - plain.controls[i + 1] / plain.controls[i + 3];
                    low = Math.min(low, difference); high = Math.max(high, difference);
                }
                deformationRange = high - low;
            }
        }
        assert deformationRange > .0001 : "Response must deform the sheet, not just translate it";
        assert alignedBirths > 500 : "Check fresh births under continuous motion";
        wave.setPhoneMotion(0, 0, 0); particles.setPhoneAgitation(0);
        for (int frame = 0; frame < 120; frame++) {
            wave.advance(1f / 60); particles.advance(1f / 60); finite(wave, particles);
        }
        System.out.println("Phone response checks passed: recovered gain, idle parity, 30/60 fps, spring stability, clipping and "
            + alignedBirths + " aligned births");
    }
}
