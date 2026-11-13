/* LG-XMB, 2026. SPDX-License-Identifier: GPL-3.0-or-later
 * Native GLES port of LG-XMB's ps3-native-renderer.js. Shader research and
 * reference data retain their existing notices; see app/src/main/assets/licenses/.
 */
package org.lgxmb.wallpaper;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TimeZone;

import static android.opengl.GLES30.*;

/** All methods run on the engine's GL thread with its GLES3 context current. */
public final class WaveRenderer {
    private final ArrayList<Integer> textures = new ArrayList<>();
    private final ArrayList<Integer> buffers = new ArrayList<>();
    private final ArrayList<Integer> vaos = new ArrayList<>();
    private final ArrayList<Program> programs = new ArrayList<>();
    private final BackgroundClock clock = new BackgroundClock();
    private final ScrollMotion scroll;
    private final SystemPalette systemPalette = new SystemPalette();
    private final ScreensaverFade screensaver = new ScreensaverFade();
    private final float[] particleProjection = new float[16];
    private final float[] originalProjection = new float[16];
    private final float[] waveBand = new float[2];
    private WaveSimulation wave;
    private ParticleSimulation particles;
    private FloatBuffer controlData, particleData, birthData;
    private Program waveProgram, monthlyProgram, systemProgram, backdropProgram, compositeProgram;
    private Program particleProgram, glareProgram;
    private int waveVao, fullscreenVao, particleVao, instanceBuffer, birthBuffer, indexBuffer;
    private int controlTexture, fresnelTexture, iridescentTexture, monthlyTexture;
    private int indexCount, width, height;
    private Target waveTarget, monthlyTarget, backdropTarget;
    private long clockSecond = Long.MIN_VALUE;
    private int grid = 128, resolutionPercent = 100, particleLimit = 2000;
    private int colourMonth, timeMode;
    private float panDistance = .3f, brightness = 1;
    private float aspectCorrection = 1, uploadedPan = Float.NaN;
    private float lift = SceneProjection.lift(40), uploadedLift = Float.NaN;
    private float uploadedDimBackground = 1, uploadedDimWave = 1, uploadedSparkleGain = 1;
    private boolean panEnabled = true, fxaa = true;
    private boolean released;

    WaveRenderer(Context context, WaveScene scene) {
        scroll = scene.scroll;
        try {
            AssetManager assets = context.getAssets();
            JSONObject reference = new JSONObject(new String(read(assets, "reference.json"), StandardCharsets.UTF_8));
            if (reference.getInt("format") != 1) throw new IllegalStateException("Unsupported reference format");
            scene.initialize(reference);
            wave = scene.wave;
            particles = scene.particles;
            controlData = floats(wave.controls.length);
            particleData = floats(particles.instances.length);
            birthData = floats(particles.birthHeights.length);
            waveProgram = program(assets, "waveVertex.vert", "waveFragment.frag");
            monthlyProgram = program(assets, "fullscreenVertex.vert", "monthlyBackground.frag");
            systemProgram = program(assets, "fullscreenVertex.vert", "systemBackground.frag");
            backdropProgram = program(assets, "fullscreenVertex.vert", "backdropFragment.frag");
            compositeProgram = program(assets, "fullscreenVertex.vert", "compositeFragment.frag");
            particleProgram = program(assets, "particleVertex.vert", "particleFragment.frag");
            glareProgram = program(assets, "glareVertex.vert", "glareFragment.frag");
            fullscreenVao = vao();
            createMesh();
            createParticles();
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            controlTexture = texture(GL_TEXTURE_2D);
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA32F, 19, 19);
            filtering(GL_TEXTURE_2D, GL_NEAREST);
            fresnelTexture = image(reference.getJSONObject("fresnel"));
            iridescentTexture = image(reference.getJSONObject("iridescence"));
            byte[] months = read(assets, "monthly.rgba");
            if (months.length != 64 * 32 * 24 * 4) throw new IllegalStateException("Invalid month texture data");
            monthlyTexture = texture(GL_TEXTURE_2D_ARRAY);
            glTexStorage3D(GL_TEXTURE_2D_ARRAY, 1, GL_RGBA8, 64, 32, 24);
            glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, 0, 64, 32, 24, GL_RGBA, GL_UNSIGNED_BYTE, bytes(months));
            filtering(GL_TEXTURE_2D_ARRAY, GL_LINEAR);
            monthlyTarget = targetWithFallback(64, 32, GL_RGBA16F);
            prepareWaveMaterial(reference);
            prepareParticleMaterial(reference);
            check("Renderer initialization");
        } catch (Exception error) {
            release();
            throw new IllegalStateException("Cannot initialize XMB wallpaper", error);
        }
    }

    public void surfaceChanged(int newWidth, int newHeight) {
        if (released || newWidth <= 0 || newHeight <= 0) return;
        if (width == newWidth && height == newHeight && waveTarget != null) return;
        width = newWidth;
        height = newHeight;
        dispose(backdropTarget);
        backdropTarget = null;
        resizeWaveTarget();
        backdropTarget = targetWithFallback(width, height, GL_RGB10_A2);
        aspectCorrection = (16f / 9f) / ((float) width / height);
        waveProgram.use();
        waveProgram.f("uAspectCorrection", aspectCorrection);
        uploadedPan = Float.NaN;
        updatePan();
        float spread = SceneProjection.particleSpread(width, height);
        particleProgram.use();
        particleProgram.f("uPortraitSpread", spread);
        glareProgram.use();
        glareProgram.f("uPortraitSpread", spread);
        clockSecond = Long.MIN_VALUE;
        check("Surface resize");
    }

    public void applySettings(WallpaperSettings settings) {
        if (released) return;
        if (grid != settings.mesh) {
            grid = settings.mesh;
            createMesh();
            waveProgram.use();
            waveProgram.i("uGrid", grid);
        }
        if (resolutionPercent != settings.resolutionPercent) {
            resolutionPercent = settings.resolutionPercent;
            if (width > 0 && height > 0) resizeWaveTarget();
        }
        if (colourMonth != settings.colourMonth || timeMode != settings.timeMode) {
            colourMonth = settings.colourMonth;
            timeMode = settings.timeMode;
            clockSecond = Long.MIN_VALUE;
        }
        particleLimit = settings.particleCount;
        screensaver.configure(settings.screensaverBackgroundPercent, settings.screensaverWavePercent,
                settings.screensaverSparklePercent);
        panDistance = settings.panDistancePercent / 100f;
        panEnabled = settings.panEnabled;
        lift = SceneProjection.lift(settings.wavePositionPercent);
        brightness = settings.brightnessPercent / 100f;
        fxaa = settings.fxaa;
        compositeProgram.use();
        compositeProgram.f("uBrightness", brightness);
        compositeProgram.i("uFilter", fxaa ? 1 : 0);
    }

    public void setSystemColours(int primary, int secondary, int tertiary) {
        if (systemPalette.set(primary, secondary, tertiary) && colourMonth == WallpaperSettings.COLOUR_SYSTEM)
            clockSecond = Long.MIN_VALUE;
    }

    public void setScreensaver(boolean active) { screensaver.setActive(active); }

    public boolean isScreensaverFading() { return screensaver.isFading(); }

    private void resizeWaveTarget() {
        int[] limit = new int[1];
        glGetIntegerv(GL_MAX_TEXTURE_SIZE, limit, 0);
        float scale = Math.min(resolutionPercent / 100f, limit[0] / (float) Math.max(width, height));
        Target next = new Target(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)), GL_RGBA8);
        dispose(waveTarget);
        waveTarget = next;
        waveProgram.use();
        waveProgram.f("uGuardClip", 1f + 8f / waveTarget.width);
    }

    private void updatePan() {
        float pan = scroll.pan(aspectCorrection, panEnabled, panDistance);
        if (pan == uploadedPan && lift == uploadedLift) return;
        uploadedPan = pan;
        uploadedLift = lift;
        waveProgram.use();
        waveProgram.f("uPan", pan);
        waveProgram.f("uLift", lift);
        SceneProjection.particleRows(originalProjection, particleProjection, aspectCorrection, pan, lift);
        particleProgram.use();
        glUniform4fv(particleProgram.location("uModelviewProjection"), 4, particleProjection, 0);
        glareProgram.use();
        glUniform4fv(glareProgram.location("uModelviewProjection"), 4, particleProjection, 0);
    }

    /** Delta excludes hidden time. The service caps it after a delayed frame. */
    public void drawFrame(float deltaSeconds) {
        if (released || waveTarget == null) return;
        if (deltaSeconds > 0 && Float.isFinite(deltaSeconds)) {
            float elapsed = Math.min(deltaSeconds, .1f);
            screensaver.advance(elapsed);
        }
        updatePan();
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_STENCIL_TEST);
        glColorMask(true, true, true, true);
        updateBackground();

        waveTarget.bind();
        glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT);
        additiveBlend();
        waveProgram.use();
        glBindVertexArray(waveVao);
        bindTexture(0, GL_TEXTURE_2D, controlTexture);
        controlData.clear();
        controlData.put(wave.controls).flip();
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 19, 19, GL_RGBA, GL_FLOAT, controlData);
        bindTexture(1, GL_TEXTURE_2D, fresnelTexture);
        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_SHORT, 0);

        glDisable(GL_BLEND);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, width, height);
        glBindVertexArray(fullscreenVao);
        compositeProgram.use();
        if (uploadedDimBackground != screensaver.background || uploadedDimWave != screensaver.wave) {
            compositeProgram.f2("uIdleBrightness", screensaver.background, screensaver.wave);
            uploadedDimBackground = screensaver.background;
            uploadedDimWave = screensaver.wave;
        }
        bindTexture(0, GL_TEXTURE_2D, waveTarget.texture);
        bindTexture(1, GL_TEXTURE_2D, backdropTarget.texture);
        bindTexture(2, GL_TEXTURE_2D, fresnelTexture);
        compositeProgram.f2("uTexel", 1f / width, 1f / height);
        SceneProjection.waveBand(wave.controls, lift, height, waveBand);
        compositeProgram.f2("uBand", waveBand[0], waveBand[1]);
        glDrawArrays(GL_TRIANGLES, 0, 3);

        int count = Math.min(particleLimit, particles.instanceCount);
        if (count > 0) {
            particleData.clear();
            particleData.put(particles.instances, 0, count * 8).flip();
            glBindBuffer(GL_ARRAY_BUFFER, instanceBuffer);
            glBufferSubData(GL_ARRAY_BUFFER, 0, count * 32, particleData);
            birthData.clear();
            birthData.put(particles.birthHeights, 0, count).flip();
            glBindBuffer(GL_ARRAY_BUFFER, birthBuffer);
            glBufferSubData(GL_ARRAY_BUFFER, 0, count * 4, birthData);
            additiveBlend();
            glBindVertexArray(particleVao);
            bindTexture(1, GL_TEXTURE_2D, iridescentTexture);
            bindTexture(2, GL_TEXTURE_2D, fresnelTexture);
            float sparkleGain = brightness * screensaver.sparkles;
            particleProgram.use();
            if (uploadedSparkleGain != sparkleGain) particleProgram.f("uGamma", sparkleGain);
            glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, count);
            glareProgram.use();
            if (uploadedSparkleGain != sparkleGain) glareProgram.f("uGamma", sparkleGain);
            glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, count);
            uploadedSparkleGain = sparkleGain;
        }
        glDisable(GL_BLEND);
        glBindVertexArray(0);
    }

    private void updateBackground() {
        long second = System.currentTimeMillis() / 1000;
        if (second == clockSecond || (colourMonth != 0 && timeMode != 0 && clockSecond != Long.MIN_VALUE)) return;
        clockSecond = second;
        clock.update(second * 1000, TimeZone.getDefault(), colourMonth, timeMode);
        monthlyTarget.bind();
        glDisable(GL_BLEND);
        glBindVertexArray(fullscreenVao);
        if (colourMonth == WallpaperSettings.COLOUR_SYSTEM) {
            systemProgram.use();
            glUniform3fv(systemProgram.location("uPalette"), 3, systemPalette.linear, 0);
            systemProgram.f("uDaylight", SystemPalette.daylight(clock.nightDayBlend));
        } else {
            monthlyProgram.use();
            bindTexture(0, GL_TEXTURE_2D_ARRAY, monthlyTexture);
            monthlyProgram.i("uTextures", 0);
            glUniform1fv(monthlyProgram.location("uLayers"), 4, clock.layers, 0);
            monthlyProgram.f("_DayTime", clock.dayTime);
            monthlyProgram.f("_NightTime", clock.nightTime);
            monthlyProgram.f("_MonthTime", clock.monthTime);
            monthlyProgram.f("_NightDayBlend", clock.nightDayBlend);
            monthlyProgram.f("_NightBrightness", .4860590100288391f);
            monthlyProgram.f("_Alpha", 1);
        }
        glDrawArrays(GL_TRIANGLES, 0, 3);

        backdropTarget.bind();
        backdropProgram.use();
        bindTexture(0, GL_TEXTURE_2D, monthlyTarget.texture);
        backdropProgram.i("uMonthly", 0);
        backdropProgram.i("uMonthlyEnabled", 1);
        backdropProgram.i("uColorEnabled", 0);
        backdropProgram.f("uCacheLevels", backdropTarget.format == GL_RGB10_A2 ? 1023 : 255);
        backdropProgram.f("uBackdropScale", 1);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    private void prepareWaveMaterial(JSONObject reference) throws Exception {
        float[] basis = new float[32], derivative = new float[32];
        JSONArray b = reference.getJSONArray("basis"), d = reference.getJSONArray("derivative");
        for (int j = 0; j < 8; j++) for (int k = 0; k < 4; k++) {
            basis[j * 4 + k] = (float) b.getDouble(j * 64 + k);
            derivative[j * 4 + k] = (float) d.getDouble(j * 64 + k);
        }
        waveProgram.use();
        glUniform4fv(waveProgram.location("uBasis"), 8, basis, 0);
        glUniform4fv(waveProgram.location("uDerivative"), 8, derivative, 0);
        waveProgram.i("uControls", 0);
        waveProgram.i("uFresnel", 1);
        waveProgram.i("uGrid", grid);
        waveProgram.f3("uWave", .92f, .96f, 1);
        waveProgram.f("uBrightness", 1);
        JSONObject settings = reference.getJSONObject("settings");
        waveProgram.f3("uMaterial", number(settings, "FRESNEL"), number(settings, "BRIGHTNESS"), number(settings, "MIPMAP BIAS"));
        compositeProgram.use();
        compositeProgram.i("uScene", 0);
        compositeProgram.i("uBackdrop", 1);
        compositeProgram.i("uAmbient", 2);
        compositeProgram.i("uAmbientEnabled", 0);
        compositeProgram.f("uBackdropScale", 1);
        compositeProgram.f("uBrightness", 1);
        compositeProgram.f2("uIdleBrightness", 1, 1);
        compositeProgram.f("uSoftness", 1.5f);
        compositeProgram.i("uFilter", fxaa ? 1 : 0);
        compositeProgram.f3("uTuning", .0078125f, .0833333f, .75f);
    }

    private void prepareParticleMaterial(JSONObject reference) throws Exception {
        JSONObject m = reference.getJSONObject("particleMaterial");
        ByteBuffer params = ByteBuffer.wrap(jsonBytes(reference.getJSONArray("particleParams"))).order(ByteOrder.BIG_ENDIAN);
        Map<String, float[]> values = new HashMap<>();
        values.put("uLifeBoundsMin", new float[]{params.getFloat(0x800), params.getFloat(0x804), params.getFloat(0x808), 0});
        values.put("uLifeBoundsMax", new float[]{params.getFloat(0x810), params.getFloat(0x814), params.getFloat(0x818), 0});
        float[] projection = new float[16];
        JSONArray source = reference.getJSONArray("viewProjection");
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) projection[i * 4 + j] = (float) source.getDouble(j * 4 + i);
        values.put("uModelviewProjection", projection);
        values.put("uModelview", new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, -2, 0, 0, 0, 1});
        values.put("uLightPack", new float[]{0, number(m,"spot pos x"), number(m,"spot attn x"), number(m,"lambert coeff"), 0, number(m,"spot pos y"), number(m,"spot attn y"), number(m,"specular coeff"), 2, number(m,"spot pos z"), number(m,"spot attn z"), number(m,"exposure"), 0, number(m,"specular power"), 0, number(m,"color_control")});
        values.put("uParticleSize", new float[]{number(m,"size middle"), number(m,"size near"), number(m,"size far"), 0});
        values.put("uFocus", new float[]{number(m,"near focus"), number(m,"near focus") + number(m,"near focus_dist"), number(m,"far focus"), number(m,"far focus") + number(m,"far focus_dist")});
        values.put("uFocusCurves", new float[]{number(m,"near focus_pow"), number(m,"far focus_pow"), .9250245094299316f, 0});
        values.put("uFrontFacingQuaternion", new float[]{0, 0, 0, 1});
        values.put("uTransparency", new float[]{number(m,"fresnel"), number(m,"global alpha"), 0, 0});
        values.put("uNearControl", new float[]{number(m,"size align"), number(m,"near fuzziness"), number(m,"near align"), 0});
        values.put("uDarkness", new float[]{number(m,"near darkness"), number(m,"far darkness"), 0, 0});
        values.put("uGlare", new float[]{number(m,"glare"), number(m,"glare scale"), number(m,"glare p1"), number(m,"glare p2")});
        values.put("uColor", new float[]{1, 1, 1, 0});
        JSONObject optics = reference.optJSONObject("optics");
        if (optics != null) {
            JSONObject uniforms = optics.getJSONObject("uniforms");
            for (Iterator<String> keys = uniforms.keys(); keys.hasNext();) {
                String key = keys.next();
                JSONArray array = uniforms.optJSONArray(key);
                if (array == null) continue;
                float[] data = new float[array.length()];
                for (int i = 0; i < data.length; i++) data[i] = (float) array.getDouble(i);
                values.put(key, data);
            }
        }
        System.arraycopy(values.get("uModelviewProjection"), 0, originalProjection, 0, 16);
        for (Program program : new Program[]{particleProgram, glareProgram}) {
            program.use();
            for (Map.Entry<String, float[]> uniform : values.entrySet()) {
                if (uniform.getKey().equals("uModelviewProjection")) continue;
                float[] data = uniform.getValue();
                glUniform4fv(program.location(uniform.getKey()), data.length / 4, data, 0);
            }
            program.f("uIridescentExponent", number(m, "iridescent exp"));
            program.f("uGamma", 1);
            program.i("uAmbientEnabled", 0);
            program.i("uAmbient", 2);
            program.i("uIridescent", 1);
        }
    }

    private void createMesh() {
        short[] indices = new short[(grid - 1) * (grid - 1) * 6 + (grid - 1) * 12];
        int at = 0, guard = grid * grid;
        for (int y = 0; y < grid - 1; y++) {
            for (int x = 0; x < grid - 1; x++) {
                int a = y * grid + x, b = a + grid;
                indices[at++] = (short) a; indices[at++] = (short) b; indices[at++] = (short) (a + 1);
                indices[at++] = (short) (a + 1); indices[at++] = (short) b; indices[at++] = (short) (b + 1);
            }
            int g = guard + y * 2, a = y * grid, b = (y + 1) * grid;
            indices[at++] = (short) g; indices[at++] = (short) (g + 2); indices[at++] = (short) a;
            indices[at++] = (short) a; indices[at++] = (short) (g + 2); indices[at++] = (short) b;
            indices[at++] = (short) (a + grid - 1); indices[at++] = (short) (b + grid - 1); indices[at++] = (short) (g + 1);
            indices[at++] = (short) (g + 1); indices[at++] = (short) (b + grid - 1); indices[at++] = (short) (g + 3);
        }
        indexCount = at;
        if (waveVao == 0) { waveVao = vao(); indexBuffer = buffer(); }
        glBindVertexArray(waveVao);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
        ShortBuffer data = ByteBuffer.allocateDirect(indices.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        data.put(indices).flip();
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices.length * 2, data, GL_STATIC_DRAW);
        glBindVertexArray(0);
    }

    private void createParticles() {
        particleVao = vao();
        glBindVertexArray(particleVao);
        instanceBuffer = buffer();
        glBindBuffer(GL_ARRAY_BUFFER, instanceBuffer);
        glBufferData(GL_ARRAY_BUFFER, particles.instances.length * 4, null, GL_DYNAMIC_DRAW);
        for (int i = 0; i < 2; i++) {
            glEnableVertexAttribArray(i);
            glVertexAttribPointer(i, 4, GL_FLOAT, false, 32, i * 16);
            glVertexAttribDivisor(i, 1);
        }
        birthBuffer = buffer();
        glBindBuffer(GL_ARRAY_BUFFER, birthBuffer);
        glBufferData(GL_ARRAY_BUFFER, particles.birthHeights.length * 4, null, GL_DYNAMIC_DRAW);
        glEnableVertexAttribArray(3);
        glVertexAttribPointer(3, 1, GL_FLOAT, false, 4, 0);
        glVertexAttribDivisor(3, 1);
        glBindBuffer(GL_ARRAY_BUFFER, buffer());
        FloatBuffer corners = floats(8);
        corners.put(new float[]{0, 0, 1, 0, 0, 1, 1, 1}).flip();
        glBufferData(GL_ARRAY_BUFFER, 32, corners, GL_STATIC_DRAW);
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 2, GL_FLOAT, false, 8, 0);
        glVertexAttribDivisor(2, 0);
        glBindVertexArray(0);
    }

    private int image(JSONObject image) throws Exception {
        int id = texture(GL_TEXTURE_2D);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, image.getInt("width"), image.getInt("height"), 0, GL_RGBA, GL_UNSIGNED_BYTE, bytes(jsonBytes(image.getJSONArray("rgba"))));
        filtering(GL_TEXTURE_2D, GL_LINEAR);
        return id;
    }

    private static void additiveBlend() {
        glEnable(GL_BLEND);
        glBlendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD);
        glBlendFuncSeparate(GL_ONE, GL_ONE, GL_ONE, GL_ONE);
    }

    private static void bindTexture(int unit, int target, int name) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(target, name);
    }

    private int texture(int target) {
        int[] name = new int[1];
        glGenTextures(1, name, 0);
        textures.add(name[0]);
        glBindTexture(target, name[0]);
        return name[0];
    }

    private int buffer() {
        int[] name = new int[1];
        glGenBuffers(1, name, 0);
        buffers.add(name[0]);
        return name[0];
    }

    private int vao() {
        int[] name = new int[1];
        glGenVertexArrays(1, name, 0);
        vaos.add(name[0]);
        return name[0];
    }

    private static void filtering(int target, int filter) {
        glTexParameteri(target, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(target, GL_TEXTURE_MAG_FILTER, filter);
        glTexParameteri(target, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(target, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    private Target targetWithFallback(int width, int height, int preferred) {
        try { return new Target(width, height, preferred); }
        catch (IllegalStateException error) {
            Log.w("XmbWallpaper", "Using RGBA8 render target", error);
            return new Target(width, height, GL_RGBA8);
        }
    }

    private static final class Target {
        final int width, height, format;
        int texture, framebuffer;
        Target(int width, int height, int format) {
            this.width = width; this.height = height; this.format = format;
            int[] name = new int[1];
            try {
                glGenTextures(1, name, 0); texture = name[0];
                glBindTexture(GL_TEXTURE_2D, texture);
                glTexStorage2D(GL_TEXTURE_2D, 1, format, width, height);
                filtering(GL_TEXTURE_2D, GL_LINEAR);
                glGenFramebuffers(1, name, 0); framebuffer = name[0];
                glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
                int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
                check("Render target");
                if (status != GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("Incomplete framebuffer: " + status);
            } catch (RuntimeException error) { dispose(this); throw error; }
            finally { glBindFramebuffer(GL_FRAMEBUFFER, 0); }
        }
        void bind() { glBindFramebuffer(GL_FRAMEBUFFER, framebuffer); glViewport(0, 0, width, height); }
    }

    private static void dispose(Target target) {
        if (target == null) return;
        int[] name = {target.framebuffer};
        glDeleteFramebuffers(1, name, 0);
        name[0] = target.texture;
        glDeleteTextures(1, name, 0);
        target.framebuffer = target.texture = 0;
    }

    public void release() {
        if (released) return;
        released = true;
        dispose(waveTarget); dispose(monthlyTarget); dispose(backdropTarget);
        int[] name = new int[1];
        for (int id : textures) { name[0] = id; glDeleteTextures(1, name, 0); }
        for (int id : buffers) { name[0] = id; glDeleteBuffers(1, name, 0); }
        for (int id : vaos) { name[0] = id; glDeleteVertexArrays(1, name, 0); }
        for (Program program : programs) glDeleteProgram(program.id);
        textures.clear(); buffers.clear(); vaos.clear(); programs.clear();
    }

    private Program program(AssetManager assets, String vertex, String fragment) throws Exception {
        int vs = 0, fs = 0, id = 0;
        try {
            vs = shader(GL_VERTEX_SHADER, new String(read(assets, "shaders/" + vertex), StandardCharsets.UTF_8));
            fs = shader(GL_FRAGMENT_SHADER, new String(read(assets, "shaders/" + fragment), StandardCharsets.UTF_8));
            id = glCreateProgram();
            glAttachShader(id, vs); glAttachShader(id, fs); glLinkProgram(id);
            int[] status = new int[1];
            glGetProgramiv(id, GL_LINK_STATUS, status, 0);
            if (status[0] == 0) throw new IllegalStateException(vertex + ": " + glGetProgramInfoLog(id));
            Program program = new Program(id);
            programs.add(program);
            return program;
        } catch (Exception error) { if (id != 0) glDeleteProgram(id); throw error; }
        finally { if (vs != 0) glDeleteShader(vs); if (fs != 0) glDeleteShader(fs); }
    }

    private static int shader(int type, String source) {
        int id = glCreateShader(type);
        glShaderSource(id, source); glCompileShader(id);
        int[] status = new int[1];
        glGetShaderiv(id, GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String message = glGetShaderInfoLog(id);
            glDeleteShader(id);
            throw new IllegalStateException("Shader compilation: " + message);
        }
        return id;
    }

    private static final class Program {
        final int id;
        final HashMap<String, Integer> uniforms = new HashMap<>();
        Program(int id) { this.id = id; }
        void use() { glUseProgram(id); }
        int location(String name) {
            Integer location = uniforms.get(name);
            if (location == null) { location = glGetUniformLocation(id, name); uniforms.put(name, location); }
            return location;
        }
        void i(String name, int value) { glUniform1i(location(name), value); }
        void f(String name, float value) { glUniform1f(location(name), value); }
        void f2(String name, float x, float y) { glUniform2f(location(name), x, y); }
        void f3(String name, float x, float y, float z) { glUniform3f(location(name), x, y, z); }
    }

    private static void check(String operation) {
        int error = glGetError();
        if (error == GL_NO_ERROR) return;
        while (glGetError() != GL_NO_ERROR) { /* Drain errors before a format retry. */ }
        throw new IllegalStateException(operation + ": GLES error 0x" + Integer.toHexString(error));
    }

    private static float number(JSONObject object, String key) throws Exception { return (float) object.getDouble(key); }
    private static FloatBuffer floats(int count) { return ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer(); }
    private static ByteBuffer bytes(byte[] values) { ByteBuffer result = ByteBuffer.allocateDirect(values.length); result.put(values).flip(); return result; }
    private static byte[] jsonBytes(JSONArray values) throws Exception {
        byte[] result = new byte[values.length()];
        for (int i = 0; i < result.length; i++) result[i] = (byte) values.getInt(i);
        return result;
    }
    private static byte[] read(AssetManager assets, String name) throws Exception {
        try (InputStream input = assets.open(name); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            for (int count; (count = input.read(buffer)) >= 0;) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
}
