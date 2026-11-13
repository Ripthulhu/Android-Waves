// SPDX-License-Identifier: GPL-3.0-or-later
package org.lgxmb.wallpaper;

import java.util.ArrayList;
import org.json.JSONException;
import org.json.JSONObject;

/** CPU animation shared across surfaces. Callers hold this scene's monitor. */
final class WaveScene {
    WaveSimulation wave;
    ParticleSimulation particles;
    final ScrollMotion scroll = new ScrollMotion();
    final PhoneMotionResponse phoneMotion = new PhoneMotionResponse();
    private final ArrayList<Object> wallpapers = new ArrayList<>();
    private final ArrayList<Object> dreams = new ArrayList<>();
    private final FrameTiming timing = new FrameTiming();
    private Object owner;
    private WallpaperSettings configured;
    private long lastFrameNs = Long.MIN_VALUE;
    private float waveSpeed = 1, scrollStrength = .2f;
    private int attachedWallpapers;
    private boolean awaitingWallpaper;
    private Runnable wallpaperReady;

    void initialize(JSONObject reference) throws JSONException {
        if (wave != null) return;
        WaveSimulation createdWave = new WaveSimulation(reference);
        ParticleSimulation createdParticles = new ParticleSimulation(reference, createdWave);
        wave = createdWave;
        particles = createdParticles;
    }

    void activate(Object host, boolean dream) {
        ArrayList<Object> active = dream ? dreams : wallpapers;
        if (!active.contains(host)) active.add(host);
        chooseOwner();
    }

    void deactivate(Object host) {
        wallpapers.remove(host);
        dreams.remove(host);
        chooseOwner();
    }

    void wallpaperAttached() { attachedWallpapers++; }

    void wallpaperDetached() {
        if (attachedWallpapers == 0) throw new IllegalStateException("Wallpaper was not attached");
        attachedWallpapers--;
    }

    Runnable beginWallpaperHandoff(Object dream, Runnable ready) {
        if (attachedWallpapers == 0 || !isOwner(dream) || !dreams.contains(dream)) return ready;
        // Hold the last dream frame until the wallpaper has actually submitted it.
        awaitingWallpaper = true;
        wallpaperReady = ready;
        return null;
    }

    long frameStamp() { return lastFrameNs; }

    Runnable wallpaperPresented(Object host, long stamp) {
        if (!awaitingWallpaper || stamp != lastFrameNs || !wallpapers.contains(host)) return null;
        Runnable ready = wallpaperReady;
        endWallpaperHandoff();
        return ready;
    }

    void endWallpaperHandoff() {
        if (!awaitingWallpaper) return;
        awaitingWallpaper = false;
        wallpaperReady = null;
        timing.pause();
    }

    private void chooseOwner() {
        Object next = !dreams.isEmpty() ? dreams.get(0)
                : !wallpapers.isEmpty() ? wallpapers.get(0) : null;
        if (owner == next) return;
        owner = next;
        endWallpaperHandoff();
        // A handoff keeps the scene, but never catches up time spent hidden.
        timing.pause();
        phoneMotion.setInput(0, 0, 0);
    }

    boolean isOwner(Object host) { return owner == host; }

    void configure(WallpaperSettings settings) {
        if (configured == settings) return;
        configured = settings;
        particles.limit = settings.particleCount;
        particles.setNavigationEnabled(settings.navigationParticles && settings.particleCount > 0);
        phoneMotion.configure(settings.phoneMotion, settings.motionStrengthPercent / 100f);
        waveSpeed = settings.waveSpeedPercent / 100f;
        scrollStrength = settings.scrollStrengthPercent / 100f;
    }

    void setOffset(Object host, float offset, float step) {
        if (isOwner(host) && !dreams.contains(host)) scroll.setOffset(offset, step);
    }

    void setPhoneMotion(Object host, float tiltX, float tiltY, float shake) {
        if (isOwner(host)) phoneMotion.setInput(tiltX, tiltY, shake);
    }

    void advance(Object host, long frameNs) {
        if (!isOwner(host) || awaitingWallpaper || wave == null || frameNs <= lastFrameNs) return;
        lastFrameNs = frameNs;
        float elapsed = timing.deltaSeconds(frameNs);
        if (!(elapsed > 0)) return;
        phoneMotion.advance(elapsed);
        wave.setPhoneMotion(phoneMotion.waveX, phoneMotion.waveY, phoneMotion.waveShake);
        particles.setPhoneAgitation(phoneMotion.particleAgitation);
        scroll.advance(elapsed);
        int navigation = scroll.consumeNavigationDirection();
        if (navigation != 4) particles.navigate(navigation);
        elapsed *= waveSpeed * scroll.speed(scrollStrength);
        wave.advance(elapsed);
        if (particles.limit > 0) particles.advance(elapsed);
    }
}
