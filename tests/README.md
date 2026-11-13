# Renderer checks

Run all checks on Linux with JDK 21, Node and `ANDROID_HOME` set to an SDK with
platform 37.0 installed:

```sh
bash tools/check.sh /path/to/LG-XMB
```

Use LG-XMB revision `a2c0602238099696b4e88caf76aa4d772304b488` for the comparison
fixtures. CI checks out that revision automatically. The commands below run checks
individually.

The simulation check compares every control point and packed particle against the
original LG-XMB JavaScript at different frame rates, a clock wrap and all four
deformation programs. The initial port passes all eight cases with zero float error.

From the project root on Linux or macOS, with Java and Node installed:

```sh
mkdir -p .build/simulation-check/classes
curl -fL https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar \
  -o .build/simulation-check/json.jar
node tests/export-simulation-reference.cjs ../LG-XMB .build/simulation-check/golden.json
javac -cp .build/simulation-check/json.jar -d .build/simulation-check/classes \
  app/src/main/java/org/lgxmb/wallpaper/WaveSimulation.java \
  app/src/main/java/org/lgxmb/wallpaper/NavigationResponse.java \
  app/src/main/java/org/lgxmb/wallpaper/ParticleSimulation.java \
  app/src/main/java/org/lgxmb/wallpaper/FrameTiming.java \
  tests/SimulationCheck.java tests/FrameTimingCheck.java
java -cp .build/simulation-check/classes:.build/simulation-check/json.jar \
  org.lgxmb.wallpaper.SimulationCheck app/src/main/assets/reference.json .build/simulation-check/golden.json
java -ea -cp .build/simulation-check/classes org.lgxmb.wallpaper.FrameTimingCheck
```

On Windows, use `;` instead of `:` in the Java classpath and put each command on
one line. Change the LG-XMB path if your checkout is elsewhere. The JSON jar is
only for the desktop test; Android supplies its own JSON implementation.

These checks cover numerical parity and timing rules. Use an emulator or phone
for shader compilation, surface lifecycle and the wallpaper picker.

The navigation response is compared against the recovered JavaScript equations,
including idle behavior, rapid changes, reversals, decay, reset and disabling it:

```sh
mkdir -p .build/navigation-check
node tests/export-navigation-reference.cjs ../LG-XMB .build/navigation-check/golden.json
javac -cp .build/simulation-check/json.jar -d .build/navigation-check \
  app/src/main/java/org/lgxmb/wallpaper/WaveSimulation.java \
  app/src/main/java/org/lgxmb/wallpaper/NavigationResponse.java \
  app/src/main/java/org/lgxmb/wallpaper/ParticleSimulation.java \
  tests/NavigationResponseCheck.java
java -ea -cp .build/navigation-check:.build/simulation-check/json.jar \
  org.lgxmb.wallpaper.NavigationResponseCheck app/src/main/assets/reference.json .build/navigation-check/golden.json
```

The clock and scrolling checks have no Android dependencies:

```sh
javac -d .build/motion-check \
  app/src/main/java/org/lgxmb/wallpaper/BackgroundClock.java \
  app/src/main/java/org/lgxmb/wallpaper/ScrollMotion.java \
  app/src/main/java/org/lgxmb/wallpaper/SceneProjection.java \
  tests/BackgroundClockCheck.java tests/ScrollMotionCheck.java tests/SceneProjectionCheck.java
java -ea -cp .build/motion-check org.lgxmb.wallpaper.BackgroundClockCheck
java -ea -cp .build/motion-check org.lgxmb.wallpaper.ScrollMotionCheck
java -ea -cp .build/motion-check org.lgxmb.wallpaper.SceneProjectionCheck
```

They cover month boundaries, leap day, local time and DST, fixed colours and
lighting, the full panorama range, settling, equivalent motion at 30/60 fps, and
matching wave/particle placement and clipping when moving the scene vertically.
Scrolling tests also cover one navigation event per accepted page, boundary
jitter, reversals, launcher page-spacing changes and visibility resets.

The system palette check covers sRGB conversion, avoiding redundant palette
updates, and automatic/fixed day/night lighting:

```sh
javac -d .build/palette-check \
  app/src/main/java/org/lgxmb/wallpaper/BackgroundClock.java \
  app/src/main/java/org/lgxmb/wallpaper/SystemPalette.java tests/SystemPaletteCheck.java
java -ea -cp .build/palette-check org.lgxmb.wallpaper.SystemPaletteCheck
```

On a phone, select **Colour → System colours**, then change Android's colour
palette and return Home. Check that the wallpaper follows it, including after
screen sleep/wake. Seasonal and fixed month selections should stay unchanged.

`PortraitParticleCheck` verifies that portrait spreading preserves fresh births
on the wave, keeps landscape unchanged, and expands vertical travel consistently
across the panorama and wave-position settings. Compile it with `SceneProjection`,
`WaveSimulation`, `NavigationResponse`, `ParticleSimulation` and the JSON test jar,
then run it with assertions enabled and `app/src/main/assets/reference.json` as
the argument.

Screen saver dimming keeps the TV's 1,200 ms entry and 160 ms exit fades. The check
covers independent layer levels, interruptions, changed settings during a fade,
invalid inputs and matching results at 30/60 fps:

```sh
javac -d .build/screensaver-check \
  app/src/main/java/org/lgxmb/wallpaper/ScreensaverFade.java tests/ScreensaverFadeCheck.java
java -ea -cp .build/screensaver-check org.lgxmb.wallpaper.ScreensaverFadeCheck
```

The shared scene check runs the real wave and particle models across wallpaper,
screen saver and wallpaper handoffs. It covers overlapping surfaces, duplicate and
stale frames, camera continuity, surface recreation and a long hidden interval.
Exit checks reject stale wallpaper buffers and verify that motion resumes after
the matching frame, a timeout or a change of owner:

```sh
javac -cp ".build/simulation-check/json.jar:$ANDROID_HOME/platforms/android-37.0/android.jar" \
  -d .build/scene-check \
  app/src/main/java/org/lgxmb/wallpaper/{WaveScene,WaveSimulation,ParticleSimulation,NavigationResponse,ScrollMotion,PhoneMotionResponse,WallpaperSettings,FrameTiming}.java \
  tests/WaveSceneCheck.java
java -ea -cp ".build/scene-check:.build/simulation-check/json.jar:$ANDROID_HOME/platforms/android-37.0/android.jar" \
  org.lgxmb.wallpaper.WaveSceneCheck app/src/main/assets/reference.json
```

Keep the JSON test jar before Android's stub jar on the runtime classpath.
On the phone, also leave the screen saver from different home pages and check
that the waves and sparkles keep their positions. Opening or closing a wallpaper
preview must not change the installed wallpaper's animation state.
Also lock the phone, let the screen saver start and tap to leave it. The wallpaper
must refresh before the screen saver uncovers it, and keep moving through the
lock-screen transition.

On Android, check the native screen saver picker and preview, exit with touch,
then return Home and open a wallpaper preview. Closing either preview must leave
the active wallpaper rendering. Check that rendering and sensors stop when the
screen saver closes and the wallpaper is hidden. The screen saver settings must
not dim the ordinary wallpaper.

Phone response checks cover the retained PS3 particle-agitation gain, the Android
input limits, settling, equivalent 30/60 fps response and unchanged idle physics.
They also exercise 30 seconds of maximum motion and reversals, checking spring
stability, wave clipping and sparkle births on the moving wave:

```sh
javac -cp .build/simulation-check/json.jar -d .build/phone-motion-check \
  app/src/main/java/org/lgxmb/wallpaper/WaveSimulation.java \
  app/src/main/java/org/lgxmb/wallpaper/NavigationResponse.java \
  app/src/main/java/org/lgxmb/wallpaper/ParticleSimulation.java \
  app/src/main/java/org/lgxmb/wallpaper/PhoneMotionResponse.java \
  app/src/main/java/org/lgxmb/wallpaper/SceneProjection.java \
  tests/PhoneMotionResponseCheck.java
java -ea -cp .build/phone-motion-check:.build/simulation-check/json.jar \
  org.lgxmb.wallpaper.PhoneMotionResponseCheck app/src/main/assets/reference.json
```

The phone mapping is an adaptation, not an exact controller-input reconstruction.
`PhoneMotionResponseCheck` verifies one retained native rshake input/output pair;
the broader motion cases check the Android behavior. The separate
`SensorMotionCheck` covers sensor filtering, orientation and sleep/wake resets:

```sh
javac -d .build/sensor-motion-check \
  app/src/main/java/org/lgxmb/wallpaper/PhoneMotion.java tests/SensorMotionCheck.java
java -ea -cp .build/sensor-motion-check org.lgxmb.wallpaper.SensorMotionCheck
```

The settings check verifies defaults, saved selections, immutable snapshots and
invalid stored values. It uses the Android SDK interface with a small Java proxy:

```sh
javac -cp "$ANDROID_HOME/platforms/android-37.0/android.jar" -d .build/settings-check \
  app/src/main/java/org/lgxmb/wallpaper/SystemPalette.java \
  app/src/main/java/org/lgxmb/wallpaper/WallpaperSettings.java tests/WallpaperSettingsCheck.java
java -cp ".build/settings-check:$ANDROID_HOME/platforms/android-37.0/android.jar" \
  org.lgxmb.wallpaper.WallpaperSettingsCheck
```
