/* LG-XMB, 2026. SPDX-License-Identifier: GPL-3.0-or-later */
package org.lgxmb.wallpaper;

/** Screen-space camera pan, accepted page changes and a brief speed pulse. */
final class ScrollMotion {
    private static final int NO_NAVIGATION = 4;
    private float target = .5f, position = .5f, pulse;
    private float pageStep;
    private int acceptedPage, navigationDirection = NO_NAVIGATION;
    private boolean initialized, pageInitialized;

    void setOffset(float offset) {
        setOffset(offset, 0);
    }

    void setOffset(float offset, float step) {
        if (!Float.isFinite(offset)) return;
        target = Math.max(0, Math.min(1, offset));
        if (!initialized) {
            initialized = true;
            reset();
        }
        if (!Float.isFinite(step) || step <= 0 || step > 1 || !Float.isFinite(target / step)) {
            pageInitialized = false;
            navigationDirection = NO_NAVIGATION;
            return;
        }
        float pagePosition = target / step;
        int nearestPage = Math.round(pagePosition);
        if (!pageInitialized || step != pageStep) {
            pageInitialized = true;
            pageStep = step;
            acceptedPage = nearestPage;
            navigationDirection = NO_NAVIGATION;
        } else {
            // The 0.1-page dead band also applies if coalescing skips an intermediate page.
            int nextPage = acceptedPage;
            if (pagePosition > acceptedPage + .6f) nextPage = (int) Math.floor(pagePosition + .4f);
            else if (pagePosition < acceptedPage - .6f) nextPage = (int) Math.ceil(pagePosition - .4f);
            if (nextPage != acceptedPage) {
                navigationDirection = nextPage > acceptedPage ? 1 : 0;
                acceptedPage = nextPage;
            }
        }
    }

    int consumeNavigationDirection() {
        int direction = navigationDirection;
        navigationDirection = NO_NAVIGATION;
        return direction;
    }

    void reset() {
        position = target;
        pulse = 0;
        pageInitialized = false;
        navigationDirection = NO_NAVIGATION;
    }

    void advance(float seconds) {
        if (!(seconds > 0) || !Float.isFinite(seconds)) return;
        float dt = Math.min(.1f, seconds);
        float previous = position;
        position += (target - position) * (1 - (float) Math.exp(-14 * dt));
        if (Math.abs(target - position) < .0001f) position = target;
        float velocity = Math.abs(position - previous) / dt;
        float desiredPulse = Math.min(.45f, velocity * .18f);
        pulse += (desiredPulse - pulse) * (1 - (float) Math.exp(-10 * dt));
        if (pulse < .0001f) pulse = 0;
    }

    float pan(float aspectCorrection, boolean enabled, float distance) {
        // Keep the left page at the scene's left edge; distance changes travel,
        // not the starting view, so a gentle pan still reveals that side.
        return enabled ? (1 - 2 * position * distance) * Math.max(0, aspectCorrection - 1) : 0;
    }

    float speed(float strength) {
        return 1 + pulse * strength;
    }
}
