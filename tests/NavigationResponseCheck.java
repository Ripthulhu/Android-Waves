package org.lgxmb.wallpaper;

import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;

public final class NavigationResponseCheck {
    private static int direction(JSONArray events, int tick) {
        for (int i = 0; i < events.length(); i++) {
            JSONArray event = events.getJSONArray(i);
            if (event.getInt(0) == tick) return event.getInt(1);
        }
        return 4;
    }
    private static void exact(double actual, double expected, String label) {
        if (actual != expected) throw new AssertionError(label + ": " + actual + " != " + expected);
    }
    public static void main(String[] args) throws Exception {
        JSONObject reference = new JSONObject(Files.readString(Path.of(args[0])));
        JSONObject golden = new JSONObject(Files.readString(Path.of(args[1])));
        JSONArray responses = golden.getJSONArray("responseCases");
        for (int c = 0; c < responses.length(); c++) {
            JSONObject test = responses.getJSONObject(c);
            NavigationResponse response = new NavigationResponse();
            JSONArray samples = test.getJSONArray("samples"), events = test.getJSONArray("events");
            for (int tick = 0; tick < test.getInt("ticks"); tick++) {
                if (tick == test.optInt("reset", -1)) response.reset();
                response.step(direction(events, tick));
                exact(response.strength, samples.getDouble(tick * 3), test.getString("name") + " strength");
                exact(response.rotationY, samples.getDouble(tick * 3 + 1), "rotation");
                exact(response.brownian, samples.getDouble(tick * 3 + 2), "Brownian response");
                if (test.getString("name").equals("single right and decay"))
                    assert !response.rotating : "One original press stays under the rotation threshold";
            }
            System.out.println(test.getString("name") + ": exact direction response");
        }
        JSONArray cases = golden.getJSONArray("particleCases");
        for (int c = 0; c < cases.length(); c++) {
            JSONObject test = cases.getJSONObject(c);
            WaveSimulation wave = new WaveSimulation(reference);
            ParticleSimulation particles = new ParticleSimulation(reference, wave);
            particles.limit = 4096;
            particles.setNavigationEnabled(true);
            float seconds = (float) test.getDouble("seconds");
            for (int frame = 0; frame < test.getInt("frames"); frame++) {
                if (frame == test.optInt("reset", -1)) particles.resetNavigation();
                if (frame == test.optInt("disable", -1)) particles.setNavigationEnabled(false);
                particles.navigate(direction(test.getJSONArray("events"), frame));
                wave.advance(seconds); particles.advance(seconds);
            }
            if (particles.instanceCount != test.getInt("count")) throw new AssertionError(test.getString("name") + " particle count differs");
            JSONArray expected = test.getJSONArray("instances");
            double error = 0;
            for (int i = 0; i < expected.length(); i++) {
                if (!Float.isFinite(particles.instances[i])) throw new AssertionError("Non-finite particle at " + i);
                error = Math.max(error, Math.abs(particles.instances[i] - expected.getDouble(i)));
            }
            if (error > .000001) throw new AssertionError(test.getString("name") + " particle error " + error);
            System.out.println(test.getString("name") + ": particle error " + error);
        }
        System.out.println("Navigation response checks passed");
    }
}
