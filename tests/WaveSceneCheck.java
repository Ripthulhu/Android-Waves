package org.lgxmb.wallpaper;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

/** Real CPU models exercise dream/wallpaper ownership without an Android surface. */
public final class WaveSceneCheck {
    private static final long FRAME = 16_666_667L;

    public static void main(String[] args) throws Exception {
        JSONObject reference = new JSONObject(Files.readString(Path.of(args[0])));
        continuity(reference);
        viewportAndMotion(reference);
        exitPresentation(reference);
        System.out.println("Shared scene handoff checks passed");
    }

    private static void continuity(JSONObject reference) throws Exception {
        Map<String, Object> values = new HashMap<>();
        values.put(WallpaperSettings.PHONE_MOTION, false);
        values.put(WallpaperSettings.NAVIGATION_PARTICLES, false);
        values.put(WallpaperSettings.SCROLL_STRENGTH, 0);
        WaveScene scene = new WaveScene();
        scene.initialize(reference);
        scene.configure(settings(values));
        WaveSimulation expectedWave = new WaveSimulation(reference);
        ParticleSimulation expectedParticles = new ParticleSimulation(reference, expectedWave);
        Object wallpaper = new Object(), secondary = new Object(), dream = new Object();
        scene.activate(wallpaper, false);
        scene.activate(secondary, false);
        check(scene.isOwner(wallpaper), "A second wallpaper must not steal ownership");
        long time = 1_000_000_000L;
        scene.advance(wallpaper, time);
        for (int i = 0; i < 120; i++) {
            time += FRAME;
            scene.advance(secondary, time + FRAME);
            scene.advance(wallpaper, time);
            scene.advance(wallpaper, time);
            scene.advance(wallpaper, time - FRAME);
            advanceExpected(expectedWave, expectedParticles);
        }
        compare(scene, expectedWave, expectedParticles);
        WaveSimulation originalWave = scene.wave;
        ParticleSimulation originalParticles = scene.particles;
        scene.activate(dream, true);
        check(scene.isOwner(dream), "The dream must own the scene during overlap");
        scene.deactivate(secondary);
        scene.activate(wallpaper, false);
        check(scene.isOwner(dream), "Wallpaper callbacks must not steal the dream's scene");
        scene.advance(wallpaper, time + FRAME);
        scene.advance(dream, time += FRAME);
        compare(scene, expectedWave, expectedParticles);
        for (int i = 0; i < 180; i++) {
            scene.advance(dream, time += FRAME);
            advanceExpected(expectedWave, expectedParticles);
        }
        scene.deactivate(dream);
        check(scene.isOwner(wallpaper), "Wallpaper must take over when the dream stops");
        scene.advance(wallpaper, time += FRAME);
        compare(scene, expectedWave, expectedParticles);
        scene.advance(wallpaper, time += FRAME);
        advanceExpected(expectedWave, expectedParticles);
        compare(scene, expectedWave, expectedParticles);
        scene.deactivate(wallpaper);
        time += 8L * 60 * 60 * 1_000_000_000L;
        scene.initialize(reference);
        check(scene.wave == originalWave && scene.particles == originalParticles,
                "Surface recreation must retain all simulation history");
        scene.advance(wallpaper, time);
        compare(scene, expectedWave, expectedParticles);
        scene.activate(wallpaper, false);
        scene.advance(wallpaper, time);
        compare(scene, expectedWave, expectedParticles);
        scene.advance(wallpaper, time + FRAME);
        advanceExpected(expectedWave, expectedParticles);
        compare(scene, expectedWave, expectedParticles);
    }

    private static void viewportAndMotion(JSONObject reference) throws Exception {
        WaveScene scene = new WaveScene();
        scene.initialize(reference);
        scene.configure(settings(new HashMap<>()));
        Object wallpaper = new Object(), dream = new Object(), secondary = new Object();
        scene.activate(wallpaper, false);
        scene.setOffset(wallpaper, 0, .5f);
        long time = 1_000_000_000L;
        scene.advance(wallpaper, time);
        scene.setOffset(wallpaper, 1, .5f);
        scene.setPhoneMotion(wallpaper, .8f, -.4f, .6f);
        scene.advance(wallpaper, time += FRAME);
        float pan = scene.scroll.pan(4, true, .3f);
        float motion = scene.phoneMotion.waveX;
        check(motion > 0, "Phone input should reach the shared scene");
        check(pan < 3 && pan > 1.2f, "Viewport must be between pages before handoff");
        scene.activate(dream, true);
        scene.setOffset(dream, .5f, 0);
        scene.setOffset(wallpaper, .5f, 0);
        scene.setPhoneMotion(wallpaper, -1, -1, 1);
        scene.advance(dream, time += FRAME);
        check(scene.scroll.pan(4, true, .3f) == pan && scene.phoneMotion.waveX == motion,
                "Ownership change must not snap the viewport or filtered motion");
        scene.advance(dream, time += FRAME);
        check(scene.phoneMotion.waveX > 0 && scene.phoneMotion.waveX < motion,
                "The new owner's neutral target must settle the previous response gently");
        scene.activate(secondary, false);
        scene.deactivate(secondary);
        check(scene.isOwner(dream), "Secondary host cleanup must not interrupt the dream");
        scene.deactivate(dream);
        pan = scene.scroll.pan(4, true, .3f);
        scene.setOffset(wallpaper, 1, .5f);
        scene.advance(wallpaper, time += FRAME);
        check(scene.scroll.pan(4, true, .3f) == pan, "Exit must retain the dream's last viewport");
    }

    private static void advanceExpected(WaveSimulation wave, ParticleSimulation particles) {
        float seconds = FRAME / 1_000_000_000f;
        wave.advance(seconds);
        particles.advance(seconds);
    }

    private static void exitPresentation(JSONObject reference) throws Exception {
        WaveScene scene = new WaveScene();
        scene.initialize(reference);
        scene.configure(settings(new HashMap<>()));
        Object wallpaper = new Object(), dream = new Object(), preview = new Object();
        int[] completions = {0};
        Runnable ready = () -> completions[0]++;
        scene.activate(dream, true);
        check(scene.beginWallpaperHandoff(dream, ready) == ready,
                "A standalone dream must not wait for a nonexistent wallpaper");
        scene.wallpaperAttached();
        check(scene.beginWallpaperHandoff(wallpaper, ready) == ready,
                "Only the active dream can begin the handoff");
        long time = 1_000_000_000L;
        scene.advance(dream, time);
        scene.advance(dream, time += FRAME);
        float[] controls = scene.wave.controls.clone();
        float[] particles = scene.particles.instances.clone();
        long stamp = scene.frameStamp();
        check(scene.beginWallpaperHandoff(dream, ready) == null,
                "An attached wallpaper must submit the final frame before exit");
        scene.advance(dream, time += FRAME);
        check(scene.frameStamp() == stamp && Arrays.equals(controls, scene.wave.controls)
                        && Arrays.equals(particles, scene.particles.instances),
                "The final dream state must stay frozen while waiting");
        check(scene.wallpaperPresented(dream, stamp) == null
                        && scene.wallpaperPresented(preview, stamp) == null
                        && scene.wallpaperPresented(wallpaper, stamp) == null,
                "A dream, preview or inactive wallpaper cannot finish the handoff");
        scene.activate(wallpaper, false);
        check(scene.wallpaperPresented(wallpaper, stamp - FRAME) == null,
                "A cached old wallpaper frame must not finish the handoff");
        Runnable completed = scene.wallpaperPresented(wallpaper, stamp);
        check(completed == ready, "The matching submitted wallpaper frame must finish the handoff");
        completed.run();
        check(scene.wallpaperPresented(wallpaper, stamp) == null && completions[0] == 1,
                "The completion must be returned only once");
        scene.advance(dream, time += FRAME);
        check(Arrays.equals(controls, scene.wave.controls),
                "Acknowledgement must rebase time without catching up frozen frames");
        scene.advance(dream, time += FRAME);
        check(!Arrays.equals(controls, scene.wave.controls),
                "The shared scene must keep moving if Android delays dream destruction");
        controls = scene.wave.controls.clone();
        scene.deactivate(dream);
        scene.advance(wallpaper, time += FRAME);
        check(Arrays.equals(controls, scene.wave.controls), "The first wallpaper frame must keep the final state");
        scene.advance(wallpaper, time += FRAME);
        check(!Arrays.equals(controls, scene.wave.controls), "Wallpaper animation must resume after exit");
        scene.activate(dream, true);
        scene.advance(dream, time += FRAME);
        stamp = scene.frameStamp();
        check(scene.beginWallpaperHandoff(dream, ready) == null, "A later exit must support a new handoff");
        scene.deactivate(dream);
        check(scene.wallpaperPresented(wallpaper, stamp) == null,
                "Losing dream ownership must cancel its pending completion");
        scene.activate(dream, true);
        scene.advance(dream, time += FRAME);
        stamp = scene.frameStamp();
        check(scene.beginWallpaperHandoff(dream, ready) == null, "A timeout needs a pending handoff");
        controls = scene.wave.controls.clone();
        time += 500_000_000L;
        scene.endWallpaperHandoff();
        check(scene.wallpaperPresented(wallpaper, stamp) == null,
                "A wallpaper frame arriving after timeout must not finish the dream again");
        scene.advance(dream, time += FRAME);
        check(Arrays.equals(controls, scene.wave.controls), "Timeout must exclude the frozen interval");
        scene.endWallpaperHandoff();
        scene.advance(dream, time += FRAME);
        check(!Arrays.equals(controls, scene.wave.controls),
                "Timeout must resume animation and repeated cleanup must not pause it again");
        scene.wallpaperDetached();
        scene.activate(dream, true);
        check(scene.beginWallpaperHandoff(dream, ready) == ready,
                "Removing the installed wallpaper must restore the immediate exit path");
    }

    private static void compare(WaveScene actual, WaveSimulation wave, ParticleSimulation particles) {
        check(Arrays.equals(actual.wave.controls, wave.controls), "Wave history differs across handoff");
        check(actual.particles.instanceCount == particles.instanceCount,
                "Particle count differs across handoff");
        check(Arrays.equals(actual.particles.instances, particles.instances),
                "Particle history differs across handoff");
        check(Arrays.equals(actual.particles.birthHeights, particles.birthHeights),
                "Particle birth anchors differ across handoff");
    }

    private static WallpaperSettings settings(Map<String, Object> values) {
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getAll")) return new HashMap<>(values);
                    throw new AssertionError("Unexpected preference call: " + method.getName());
                });
        return WallpaperSettings.read(preferences);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
