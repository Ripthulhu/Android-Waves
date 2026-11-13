// SPDX-License-Identifier: GPL-3.0-or-later
package org.lgxmb.wallpaper;

import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.service.dreams.DreamService;
import android.view.SurfaceView;
import android.view.View;
import android.view.Choreographer;
import android.view.WindowManager;
import android.view.MotionEvent;
import android.view.Window;

/** Android owns the idle/charging policy; the shared renderer owns the scene. */
public final class WaveDreamService extends DreamService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable finishWake = () -> finishExit(true);
    private final Choreographer.FrameCallback finishAfterFrame = frameTime -> finishExit(false);
    private WaveRenderHost host;
    private SurfaceView surface;
    private boolean dreaming, waking, windowVisible, finishing;
    private int wakeGeneration;
    private long wakeStarted;

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        // Dim the rendered layers independently instead of dimming every sparkle with the screen.
        setScreenBright(true);
        surface = new SurfaceView(this) {
            @Override protected void onWindowVisibilityChanged(int visibility) {
                super.onWindowVisibilityChanged(visibility);
                windowVisible = visibility == View.VISIBLE;
                if (host != null) host.setVisible(dreaming && windowVisible);
            }
        };
        surface.getHolder().setFormat(PixelFormat.OPAQUE);
        host = new WaveRenderHost(this, getWindow().getWindowManager().getDefaultDisplay(), true, false);
        surface.getHolder().addCallback(host);
        setContentView(surface);
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        handler.removeCallbacks(finishWake);
        Choreographer.getInstance().removeFrameCallback(finishAfterFrame);
        wakeGeneration++;
        showWallpaper(false);
        dreaming = true;
        waking = false;
        finishing = false;
        if (host != null) {
            host.setScreensaver(true);
            host.setVisible(windowVisible);
        }
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        // The default tap handler reveals keyguard before onWakeUp can refresh the wallpaper.
        if (event.getActionMasked() == MotionEvent.ACTION_UP) onWakeUp();
        return true;
    }

    @Override public void onWakeUp() {
        if (waking) return;
        waking = true;
        wakeStarted = SystemClock.uptimeMillis();
        Log.d("XMBWallpaper", "Dream exit requested");
        if (!dreaming || !windowVisible || host == null) {
            finish();
            return;
        }
        // Let Android unfreeze and redraw the wallpaper while the dream still covers it.
        showWallpaper(true);
        final int generation = ++wakeGeneration;
        host.wakeScreensaver(() -> handler.post(() -> {
            if (generation != wakeGeneration || !dreaming || !waking || finishing) return;
            Log.d("XMBWallpaper", "Wallpaper exit frame ready after "
                    + (SystemClock.uptimeMillis() - wakeStarted) + " ms");
            // Give the compositor a refresh to latch the submitted wallpaper buffer.
            Choreographer.getInstance().postFrameCallback(finishAfterFrame);
        }));
        // Never trap the user if the wallpaper or its surface is unavailable.
        handler.postDelayed(finishWake, 500);
    }

    private void showWallpaper(boolean visible) {
        Window window = getWindow();
        if (visible) window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        // DreamService does not forward attribute changes to WindowManager like Activity does.
        window.getWindowManager().updateViewLayout(window.getDecorView(), window.getAttributes());
    }

    private void finishExit(boolean timedOut) {
        if (finishing) return;
        finishing = true;
        handler.removeCallbacks(finishWake);
        Choreographer.getInstance().removeFrameCallback(finishAfterFrame);
        // Android may keep both surfaces visible during its lock-screen transition.
        if (host != null) host.endScreensaverHandoff();
        Log.d("XMBWallpaper", "Dream finish after " + (SystemClock.uptimeMillis() - wakeStarted)
                + " ms, timedOut=" + timedOut);
        wakeUp();
        finish();
    }

    @Override public void onDreamingStopped() {
        Log.d("XMBWallpaper", "Dream stopped");
        dreaming = false;
        handler.removeCallbacks(finishWake);
        Choreographer.getInstance().removeFrameCallback(finishAfterFrame);
        wakeGeneration++;
        if (host != null) host.setVisible(false);
        super.onDreamingStopped();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (host != null) host.configurationChanged();
    }

    @Override public void onDetachedFromWindow() {
        release();
        super.onDetachedFromWindow();
    }

    @Override public void onDestroy() {
        release();
        super.onDestroy();
    }

    private void release() {
        dreaming = false;
        handler.removeCallbacks(finishWake);
        Choreographer.getInstance().removeFrameCallback(finishAfterFrame);
        wakeGeneration++;
        if (host != null) {
            host.close();
            surface.getHolder().removeCallback(host);
            host = null;
            surface = null;
        }
    }
}
