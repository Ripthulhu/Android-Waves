package org.lgxmb.wallpaper;

import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;

/** Portrait presentation changes travel, not births, physics or sprite geometry. */
public final class PortraitParticleCheck {
    private static void near(double actual, double expected) {
        if (!Double.isFinite(actual) || Math.abs(actual - expected) > .00001)
            throw new AssertionError(actual + " != " + expected);
    }
    private static double row(float[] matrix, int at, double x, double y, double z) {
        return matrix[at] * x + matrix[at + 1] * y + matrix[at + 2] * z + matrix[at + 3];
    }
    public static void main(String[] args) throws Exception {
        near(SceneProjection.particleSpread(1920, 1080), 0);
        near(SceneProjection.particleSpread(1080, 1080), 0);
        near(SceneProjection.particleSpread(1000, 1500), .5);
        near(SceneProjection.particleSpread(1080, 2404), 1);
        JSONObject reference = new JSONObject(Files.readString(Path.of(args[0])));
        WaveSimulation wave = new WaveSimulation(reference);
        ParticleSimulation particles = new ParticleSimulation(reference, wave);
        particles.setNavigationEnabled(true);
        for (int i = 0; i < particles.instanceCount; i++)
            near(particles.birthHeights[i], particles.instances[i * 8 + 1]);

        int observedBirths = 0, travelled = 0;
        for (int frame = 0; frame < 900; frame++) {
            if (frame % 70 == 0) particles.navigate((frame / 70) % 2);
            wave.advance(1f / 60); particles.advance(1f / 60);
            for (int i = 0; i < particles.instanceCount; i++) {
                float y = particles.instances[i * 8 + 1], birth = particles.birthHeights[i];
                assert Float.isFinite(birth);
                if (particles.instances[i * 8 + 3] == 0) {
                    near(y, birth); observedBirths++;
                }
                if (Math.abs(y - birth) > .01) travelled++;
            }
        }
        assert observedBirths > 500 : "Check fresh wave births, including reused slots";
        assert travelled > 1000 : "The portrait mapping must have actual travel to disperse";

        float[] original = new float[16], projection = new float[16];
        JSONArray view = reference.getJSONArray("viewProjection");
        for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) original[r * 4 + c] = (float) view.getDouble(c * 4 + r);
        double normalEnergy = 0, portraitEnergy = 0;
        for (float lift : new float[]{-.6f, .2f, .6f}) for (float pan : new float[]{-.9f, 0, 2.95f}) {
            SceneProjection.particleRows(original, projection, 3.9572f, pan, lift);
            for (int i = 0; i < particles.instanceCount; i++) {
                int at = i * 8;
                double x = particles.instances[at], y = particles.instances[at + 1], z = particles.instances[at + 2];
                double birth = particles.birthHeights[i], w = row(projection, 12, x, y, z);
                if (w <= .00001) continue;
                double projectedY = row(projection, 4, x, y, z);
                double birthY = row(projection, 4, x, birth, z);
                double shift = (y - birth) * projection[5];
                near((projectedY + shift - birthY) / w, 2 * (projectedY - birthY) / w);
                // At a birth both passes use zero displacement, independent of
                // the panorama page, wave-position setting and perspective W.
                near((birth - birth) * projection[5], 0);
                normalEnergy += Math.pow((projectedY - birthY) / w, 2);
                portraitEnergy += Math.pow((projectedY + shift - birthY) / w, 2);
            }
        }
        near(portraitEnergy / normalEnergy, 4);
        System.out.println("Portrait particle checks passed: " + observedBirths + " aligned births; 2x vertical travel");
    }
}
