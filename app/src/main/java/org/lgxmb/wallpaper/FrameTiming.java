// SPDX-License-Identifier: GPL-3.0-or-later
package org.lgxmb.wallpaper;

final class FrameTiming {
    private long periodNs = 1_000_000_000L / 60;
    private long refreshNs = 1_000_000_000L / 60;
    private long previousNs, nextFrameNs;
    private boolean scheduled;

    void setFramesPerSecond(int fps) {
        long period = 1_000_000_000L / (fps == 30 ? 30 : 60);
        if (period != periodNs) {
            periodNs = period;
            pause();
        }
    }

    void setRefreshRate(float hz) {
        refreshNs = Math.round(1_000_000_000d / (Float.isFinite(hz) && hz > 0 ? hz : 60));
    }

    void pause() {
        previousNs = 0;
        scheduled = false;
    }

    boolean shouldRender(long frameTimeNs) {
        if (!scheduled) {
            nextFrameNs = frameTimeNs;
            scheduled = true;
        }
        // Pick the nearest display refresh; never round 16.67 ms up to a 17 ms timer.
        long toleranceNs = Math.min(periodNs, refreshNs) / 2;
        if (frameTimeNs + toleranceNs < nextFrameNs) return false;
        nextFrameNs += periodNs;
        // Drop missed frames without leaving a catch-up burst in the queue.
        if (nextFrameNs <= frameTimeNs - toleranceNs) nextFrameNs = frameTimeNs + periodNs;
        return true;
    }

    float deltaSeconds(long nowNs) {
        float seconds = previousNs == 0 ? 0
                : Math.max(0, Math.min(0.1f, (nowNs - previousNs) / 1_000_000_000f));
        previousNs = nowNs;
        return seconds;
    }

}
