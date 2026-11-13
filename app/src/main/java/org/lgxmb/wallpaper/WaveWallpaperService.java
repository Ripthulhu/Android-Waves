// SPDX-License-Identifier: GPL-3.0-or-later
package org.lgxmb.wallpaper;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Build;
import android.service.wallpaper.WallpaperService;
import android.view.Display;
import android.view.SurfaceHolder;
import android.view.WindowManager;
import java.util.ArrayList;

public final class WaveWallpaperService extends WallpaperService {
    private final ArrayList<WaveEngine> engines = new ArrayList<>();

    @Override public Engine onCreateEngine() {
        return new WaveEngine();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        for (WaveEngine engine : engines) engine.host.configurationChanged();
    }

    private final class WaveEngine extends Engine {
        private WaveRenderHost host;

        @Override public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            holder.setFormat(PixelFormat.OPAQUE);
            setOffsetNotificationsEnabled(true);
            setTouchEventsEnabled(false);
            Context displayContext = Build.VERSION.SDK_INT >= 29 ? getDisplayContext() : WaveWallpaperService.this;
            Display screen = ((WindowManager) displayContext.getSystemService(WINDOW_SERVICE)).getDefaultDisplay();
            host = new WaveRenderHost(WaveWallpaperService.this, screen, false, isPreview());
            engines.add(this);
        }

        @Override public void onVisibilityChanged(boolean visible) {
            host.setVisible(visible);
        }

        @Override public void onOffsetsChanged(float xOffset, float yOffset, float xStep, float yStep,
                                               int xPixels, int yPixels) {
            host.setOffsets(xOffset, xStep);
        }

        @Override public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            host.surfaceCreated(holder);
        }

        @Override public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            host.surfaceChanged(holder, format, width, height);
        }

        @Override public void onSurfaceRedrawNeeded(SurfaceHolder holder) {
            host.surfaceRedrawNeeded(holder);
        }

        @Override public void onSurfaceDestroyed(SurfaceHolder holder) {
            host.surfaceDestroyed(holder);
            super.onSurfaceDestroyed(holder);
        }

        @Override public void onDestroy() {
            engines.remove(this);
            host.close();
            super.onDestroy();
        }
    }
}
