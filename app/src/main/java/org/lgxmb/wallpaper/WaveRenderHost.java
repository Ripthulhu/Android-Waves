// SPDX-License-Identifier: GPL-3.0-or-later
package org.lgxmb.wallpaper;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;

/** Shared wallpaper/dream surface owner. Lifecycle calls arrive on the main thread. */
final class WaveRenderHost implements SurfaceHolder.Callback2 {
    private static final String TAG = "XMBWallpaper";
    private static EGLDisplay sharedDisplay = EGL14.EGL_NO_DISPLAY;
    private static int displayUsers;
    private static final HashMap<Integer, WaveScene> scenes = new HashMap<>();
    private final Context ownerContext;
    private final WaveScene scene;
    private final boolean dream;
    private final boolean wallpaper;

    private static synchronized WaveScene sceneFor(Display screen) {
        WaveScene scene = scenes.get(screen.getDisplayId());
        if (scene == null) {
            scene = new WaveScene();
            scenes.put(screen.getDisplayId(), scene);
        }
        return scene;
    }

    private int[] resolveSystemColours() {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                // Read the service's refreshed resources, not an Activity theme wrapper.
                Resources resources = ownerContext.getResources();
                return new int[] {
                        resources.getColor(android.R.color.system_accent1_500, null) | 0xff000000,
                        resources.getColor(android.R.color.system_accent2_500, null) | 0xff000000,
                        resources.getColor(android.R.color.system_accent3_500, null) | 0xff000000
                };
            } catch (Resources.NotFoundException unavailable) {
                Log.w(TAG, "System palette unavailable; using the blue theme", unavailable);
            }
        }
        // Match the app's blue theme on Android versions without a system palette.
        return new int[] {SystemPalette.FALLBACK_PRIMARY, SystemPalette.FALLBACK_SECONDARY,
                SystemPalette.FALLBACK_TERTIARY};
    }

    // Wallpaper, preview and dream surfaces share the EGL display until the last one closes.
    private static synchronized EGLDisplay acquireDisplay() {
        if (displayUsers == 0) {
            EGLDisplay candidate = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            if (candidate == EGL14.EGL_NO_DISPLAY
                    || !EGL14.eglInitialize(candidate, version, 0, version, 1))
                throw new IllegalStateException("EGL display initialization failed");
            sharedDisplay = candidate;
        }
        displayUsers++;
        return sharedDisplay;
    }

    private static synchronized void releaseDisplay() {
        if (--displayUsers == 0) {
            EGL14.eglTerminate(sharedDisplay);
            sharedDisplay = EGL14.EGL_NO_DISPLAY;
        }
    }

    private final HandlerThread thread = new HandlerThread("XMB renderer");
    private final Handler handler;
    private final FrameTiming timing = new FrameTiming();
    private final Choreographer.FrameCallback frame = this::onFrame;
    private final Runnable applySettings = this::updateSettings;
    private final SharedPreferences preferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;
    private final DisplayManager displayManager;
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) {}
        @Override public void onDisplayRemoved(int id) {}
        @Override public void onDisplayChanged(int id) {
            if (screen != null && id == screen.getDisplayId()) {
                timing.setRefreshRate(screen.getRefreshRate());
                if (motionSensorsRequested && screen.getRotation() != motionRotation) {
                    motionRotation = screen.getRotation();
                    phoneMotion.start(motionRotation, SystemClock.elapsedRealtimeNanos());
                }
            }
        }
    };
    private final SensorManager sensorManager;
    private final PhoneMotion phoneMotion = new PhoneMotion();
    private final float[] rotationMatrix = new float[9];
    private Sensor tiltSensor, shakeSensor;
    private boolean motionSensorsRequested;
    private int motionRotation;
    private final SensorEventListener motionListener = new SensorEventListener() {
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        @Override public void onSensorChanged(SensorEvent event) {
            if (!motionSensorsRequested || !visible || !surfaceAvailable || destroyed || failed
                    || event.values.length < 3) return;
            float[] values = event.values;
            int type = event.sensor.getType();
            if (type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
                for (int i = 0; i < Math.min(values.length, 4); i++)
                    if (!Float.isFinite(values[i])) return;
                SensorManager.getRotationMatrixFromVector(rotationMatrix, values);
                // The gravity row ignores the game sensor's unreferenced yaw drift.
                phoneMotion.gravity(rotationMatrix[6], rotationMatrix[7], rotationMatrix[8], event.timestamp);
            } else if (type == Sensor.TYPE_GRAVITY) {
                phoneMotion.gravity(values[0], values[1], values[2], event.timestamp);
            } else if (type == Sensor.TYPE_LINEAR_ACCELERATION) {
                phoneMotion.linearAcceleration(values[0], values[1], values[2], event.timestamp);
            } else if (type == Sensor.TYPE_ACCELEROMETER) {
                phoneMotion.accelerometer(values[0], values[1], values[2], event.timestamp,
                        event.sensor == tiltSensor, event.sensor == shakeSensor);
            }
        }
    };
    private Choreographer choreographer;
    private final Display screen;
    private volatile WallpaperSettings requestedSettings;
    private WallpaperSettings settings;
    private int[] systemColours;
    private boolean systemColoursChanged;
    private volatile boolean visible, surfaceAvailable, destroyed;
    private final Object scrollLock = new Object();
    private float scrollOffset = 0.5f, scrollStep;
    private Surface surface;
    private int width, height;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private WaveRenderer renderer;
    private boolean failed, retriedContext, sizeChanged, settingsChanged = true;
    private boolean framePosted;
    private boolean screensaver, screensaverChanged = true;
    private Runnable screensaverRendered;

    WaveRenderHost(Context ownerContext, Display screen, boolean dream, boolean preview) {
        this.ownerContext = ownerContext;
        this.screen = screen;
        this.dream = dream;
        wallpaper = !dream && !preview;
        scene = preview ? new WaveScene() : sceneFor(screen);
        preferences = WallpaperSettings.preferences(ownerContext);
        requestedSettings = WallpaperSettings.read(preferences);
        settings = requestedSettings;
        displayManager = ownerContext.getSystemService(DisplayManager.class);
        sensorManager = ownerContext.getSystemService(SensorManager.class);
        thread.start();
        handler = new Handler(thread.getLooper());
        preferenceListener = (prefs, key) -> {
            if (destroyed) return;
            requestedSettings = WallpaperSettings.read(prefs);
            handler.removeCallbacks(applySettings);
            handler.post(applySettings);
        };
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
        requestedSettings = WallpaperSettings.read(preferences);
        displayManager.registerDisplayListener(displayListener, handler);
        int[] colours = resolveSystemColours();
        handler.post(() -> {
            choreographer = Choreographer.getInstance();
            updateSystemColours(colours);
            timing.setRefreshRate(screen.getRefreshRate());
            updateSettings();
        });
        if (wallpaper) synchronized (scene) { scene.wallpaperAttached(); }
    }

    void configurationChanged() {
        if (!destroyed) refreshSystemColours(resolveSystemColours());
    }

    void setScreensaver(boolean active) {
        setScreensaver(active, null);
    }

    void wakeScreensaver(Runnable onReady) {
        setScreensaver(false, () -> {
            Log.d(TAG, "Dream exit fade submitted");
            Runnable ready;
            synchronized (scene) { ready = scene.beginWallpaperHandoff(this, onReady); }
            if (ready != null) ready.run();
        });
    }

    void endScreensaverHandoff() {
        handler.post(() -> {
            screensaverRendered = null;
            synchronized (scene) {
                if (scene.isOwner(this)) scene.endWallpaperHandoff();
            }
        });
    }

    /** Completion runs on the render thread after the fade's final frame is submitted. */
    void setScreensaver(boolean active, Runnable onRendered) {
        if (destroyed) return;
        handler.post(() -> {
            if (destroyed) return;
            screensaver = active;
            screensaverChanged = true;
            screensaverRendered = onRendered;
            scheduleFrame();
        });
    }

    void setOffsets(float xOffset, float xStep) {
        // Keep the latest offset and page step together without queuing each input event.
        if (Float.isFinite(xOffset)) {
            synchronized (scrollLock) {
                scrollOffset = Math.max(0, Math.min(1, xOffset));
                scrollStep = xStep;
            }
        }
    }

    void setVisible(boolean nowVisible) {
        if (destroyed) return;
        Log.d(TAG, (dream ? "Dream" : "Wallpaper") + " visible=" + nowVisible);
        visible = nowVisible;
        int[] colours = nowVisible ? resolveSystemColours() : null;
        handler.post(() -> {
            stopFrames();
            if (colours != null) updateSystemColours(colours);
            updateSurfaceFrameRate();
            scheduleFrame();
        });
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        if (destroyed) return;
        surfaceAvailable = true;
        Surface created = holder.getSurface();
        handler.post(() -> {
            stopFrames();
            releaseEgl();
            surface = created;
            failed = retriedContext = false;
            updateSurfaceFrameRate();
        });
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        if (destroyed) return;
        handler.post(() -> {
            width = w;
            height = h;
            sizeChanged = true;
            scheduleFrame();
        });
    }

    @Override public void surfaceRedrawNeeded(SurfaceHolder holder) {
        if (destroyed) return;
        handler.post(this::scheduleFrame);
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceAvailable = false;
        releaseSurface(false);
    }

    void close() {
        if (destroyed) return;
        destroyed = true;
        visible = false;
        surfaceAvailable = false;
        if (wallpaper) synchronized (scene) { scene.wallpaperDetached(); }
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        displayManager.unregisterDisplayListener(displayListener);
        handler.removeCallbacks(applySettings);
        releaseSurface(true);
    }

    private void releaseSurface(boolean closeThread) {
        CountDownLatch released = new CountDownLatch(1);
        boolean posted = handler.post(() -> {
            try {
                stopFrames();
                updateSurfaceFrameRate();
                releaseEgl();
                surface = null;
                width = height = 0;
            } finally {
                if (closeThread) thread.quitSafely();
                released.countDown();
            }
        });
        try {
            if (posted && !released.await(2, TimeUnit.SECONDS)) Log.w(TAG, "Surface release timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean canRender() {
        return !destroyed && visible && surfaceAvailable && !failed && width > 0 && height > 0
                && surface != null && surface.isValid();
    }

    private void scheduleFrame() {
        if (!framePosted && canRender()) {
            synchronized (scene) { scene.activate(this, dream); }
            updateMotionSensors();
            framePosted = true;
            choreographer.postFrameCallback(frame);
        }
    }

    private void stopFrames() {
        if (choreographer != null) choreographer.removeFrameCallback(frame);
        framePosted = false;
        screensaverRendered = null;
        timing.pause();
        synchronized (scene) { scene.deactivate(this); }
        stopMotionSensors();
    }

    private void updateSettings() {
        settings = requestedSettings;
        timing.setFramesPerSecond(settings.fps);
        settingsChanged = true;
        failed = false;
        updateMotionSensors();
        updateSurfaceFrameRate();
        scheduleFrame();
    }

    private void refreshSystemColours(int[] colours) {
        if (destroyed) return;
        handler.post(() -> {
            if (destroyed) return;
            updateSystemColours(colours);
            scheduleFrame();
        });
    }

    private void updateSystemColours(int[] colours) {
        if (systemColours != null && systemColours[0] == colours[0]
                && systemColours[1] == colours[1] && systemColours[2] == colours[2]) return;
        systemColours = colours;
        systemColoursChanged = true;
    }

    private void updateSurfaceFrameRate() {
        if (Build.VERSION.SDK_INT < 30 || surface == null || !surface.isValid()) return;
        float fps = visible && surfaceAvailable && !destroyed ? settings.fps : 0;
        try {
            if (Build.VERSION.SDK_INT >= 31)
                surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                        Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
            else surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
        } catch (IllegalStateException | IllegalArgumentException unavailable) {
            Log.w(TAG, "Could not set wallpaper frame rate", unavailable);
        }
    }

    private void onFrame(long frameTimeNs) {
        framePosted = false;
        if (!canRender()) {
            stopFrames();
            return;
        }
        // Reserve the next vsync before a slow swap can carry us past its deadline.
        scheduleFrame();
        if (timing.shouldRender(frameTimeNs)) drawFrame(frameTimeNs);
    }

    private void drawFrame(long frameTimeNs) {
        try {
            long submittedFrame;
            // Keep CPU state stable while each surface uploads it; swaps never hold this lock.
            synchronized (scene) {
                if (renderer == null) createEgl();
                if (settingsChanged) {
                    renderer.applySettings(settings);
                    settingsChanged = false;
                }
                if (screensaverChanged) {
                    renderer.setScreensaver(screensaver);
                    screensaverChanged = false;
                }
                if (systemColoursChanged && systemColours != null) {
                    renderer.setSystemColours(systemColours[0], systemColours[1], systemColours[2]);
                    systemColoursChanged = false;
                }
                if (sizeChanged) {
                    renderer.surfaceChanged(width, height);
                    sizeChanged = false;
                }
                float offset, step;
                synchronized (scrollLock) {
                    offset = scrollOffset;
                    step = scrollStep;
                }
                if (!dream) scene.setOffset(this, offset, step);
                if (motionSensorsRequested) phoneMotion.sample(SystemClock.elapsedRealtimeNanos());
                scene.setPhoneMotion(this, phoneMotion.tiltX(), phoneMotion.tiltY(), phoneMotion.shake());
                if (scene.isOwner(this)) scene.configure(settings);
                scene.advance(this, frameTimeNs);
                renderer.drawFrame(timing.deltaSeconds(frameTimeNs));
                submittedFrame = scene.frameStamp();
            }
            if (!EGL14.eglSwapBuffers(display, eglSurface)) {
                int error = EGL14.eglGetError();
                releaseEgl();
                timing.pause();
                if (error != EGL14.EGL_CONTEXT_LOST || retriedContext)
                    throw new IllegalStateException("EGL swap failed: 0x" + Integer.toHexString(error));
                retriedContext = true;
            } else {
                retriedContext = false;
                if (wallpaper) {
                    Runnable ready;
                    synchronized (scene) { ready = scene.wallpaperPresented(this, submittedFrame); }
                    if (ready != null) ready.run();
                }
                if (screensaverRendered != null && !renderer.isScreensaverFading()) {
                    Runnable completed = screensaverRendered;
                    screensaverRendered = null;
                    completed.run();
                }
            }
        } catch (RuntimeException error) {
            Log.e(TAG, "Wallpaper rendering stopped", error);
            failed = true;
            stopFrames();
            releaseEgl();
            return;
        }
    }

    private void updateMotionSensors() {
        boolean wanted = sensorManager != null && canRender()
                && settings.phoneMotion && settings.motionStrengthPercent > 0;
        synchronized (scene) { wanted &= scene.isOwner(this); }
        if (wanted == motionSensorsRequested) return;
        stopMotionSensors();
        if (!wanted) return;
        motionSensorsRequested = true;
        motionRotation = screen.getRotation();
        phoneMotion.start(motionRotation, SystemClock.elapsedRealtimeNanos());
        tiltSensor = registerMotionSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (tiltSensor == null) tiltSensor = registerMotionSensor(Sensor.TYPE_GRAVITY);
        if (tiltSensor == null) tiltSensor = registerMotionSensor(Sensor.TYPE_ACCELEROMETER);
        shakeSensor = registerMotionSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        if (shakeSensor == null) {
            shakeSensor = tiltSensor != null && tiltSensor.getType() == Sensor.TYPE_ACCELEROMETER
                    ? tiltSensor : registerMotionSensor(Sensor.TYPE_ACCELEROMETER);
        }
    }

    private Sensor registerMotionSensor(int type) {
        try {
            Sensor sensor = sensorManager.getDefaultSensor(type);
            if (sensor != null && sensorManager.registerListener(motionListener, sensor, 20_000, handler))
                return sensor;
        } catch (RuntimeException unavailable) {
            Log.w(TAG, "Motion sensor unavailable: " + type, unavailable);
        }
        return null;
    }

    private void stopMotionSensors() {
        if (motionSensorsRequested) {
            motionSensorsRequested = false;
            sensorManager.unregisterListener(motionListener);
        }
        tiltSensor = shakeSensor = null;
        phoneMotion.stop();
    }

    private void createEgl() {
        display = acquireDisplay();
        int[] attributes = {
                EGL14.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 0, EGL14.EGL_STENCIL_SIZE, 0, EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        require(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0);
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[] { EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE }, 0);
        require(context != EGL14.EGL_NO_CONTEXT);
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface,
                new int[] { EGL14.EGL_NONE }, 0);
        require(eglSurface != EGL14.EGL_NO_SURFACE);
        require(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context));
        EGL14.eglSwapInterval(display, 1);
        renderer = new WaveRenderer(ownerContext, scene);
        sizeChanged = settingsChanged = true;
        systemColoursChanged = screensaverChanged = true;
    }

    private void require(boolean success) {
        if (!success) throw new IllegalStateException("EGL setup failed: 0x"
                + Integer.toHexString(EGL14.eglGetError()));
    }

    private void releaseEgl() {
        if (renderer != null) {
            try {
                renderer.release();
            } catch (RuntimeException error) {
                Log.w(TAG, "Renderer cleanup failed", error);
            }
            renderer = null;
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            releaseDisplay();
            EGL14.eglReleaseThread();
        }
        display = EGL14.EGL_NO_DISPLAY;
        context = EGL14.EGL_NO_CONTEXT;
        eglSurface = EGL14.EGL_NO_SURFACE;
    }
}
