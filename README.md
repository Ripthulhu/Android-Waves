# Android Waves

An Android live wallpaper and screen saver, installed as **XMB Waves**.
It uses the same spring simulation,
spline deformation, wave shaders, sparkles and monthly backgrounds as the TV app.
Portrait screens show a crop of the scene, keeping the wave proportions intact.
Swiping between home screens pans across the original landscape scene, revealing
different parts of the waves and sparkles on each page. The default pan uses 30%
of the full range for gentler movement; raise Pan distance to reveal more. The
leftmost page starts at the left side of the scene at any pan distance. At 0%,
that view stays still; switching panoramic scrolling off restores the centre.
The waves sit slightly above centre by default, leaving room for app icons below.
In portrait, sparkles spread farther vertically after leaving the wave. Their
birth positions and size stay the same; no extra particles are added.

Download the APK from [Releases](https://github.com/Ripthulhu/Android-Waves/releases/latest),
install it, open **XMB Waves**, then tap **Preview wallpaper**. Android shows
its own preview and lets you choose where to apply it. The app never changes your
wallpaper without that confirmation.

It also appears as **XMB Waves** in Android’s screen saver picker. On Pixel, open
**Settings → Display & touch → Screen saver**, or use **Screen saver settings**
inside the app. Android controls when it starts, including charging and docking.
You can preview it there before changing your selected screen saver.

The renderer defaults to 60 fps, full surface resolution, a 128 × 128 wave mesh,
FXAA and up to 2,000 sparkles. It follows display refreshes and stops when Android
marks the wallpaper hidden.
It does not catch up on missed frames when the screen wakes. There are no network
permissions, analytics or accounts.

Open **XMB Waves** to change the settings. Changes apply to an active wallpaper
without applying it again:

- **Appearance:** seasonal colours, system colours, a fixed month or a custom palette, automatic daylight or fixed
  day/night lighting, brightness and vertical position. Automatic settings use your local date,
  time and time zone. Colour mode shows a month selector or three editable colour swatches
  when needed. Custom colours use RGB sliders or a hex value.
- **Motion:** panoramic scrolling and its distance, the extra movement from a swipe, and wave
  speed. Setting swipe motion to zero keeps the panorama enabled. Scrolling
  depends on the launcher sending wallpaper offsets.
  **Swipe sparkles** adds the recovered XMB particle agitation and small
  cloud rotation once per page change. It uses the launcher's page spacing and
  adds no particles or rendering passes. You can switch it off separately.
  **Phone motion** gently bends the waves when you tilt the phone and stirs the
  sparkles when you move it. Its strength is adjustable. Sensors stop while the
  wallpaper is hidden, and waking it establishes a new neutral holding position.
- **Rendering:** 30 or 60 fps, resolution, wave mesh, sparkle count and FXAA.
  These are separate controls, so you can choose which details to keep.
- **Screen saver:** separate brightness levels for the background, waves and
  sparkles, just like the TV version. Each defaults to 25%; set Sparkle brightness
  to 100% to keep bright sparkles above a dim scene. The scene dims over 1.2 seconds
  and returns to normal brightness over 160 ms when you touch the screen to exit.
  These levels only affect the screen saver. It shares the wallpaper’s colours,
  time-of-day lighting, motion and rendering settings.
  Both use the same wave, sparkle and camera state, so leaving the screen saver
  continues the animation from where you just saw it. Wallpaper previews stay separate.

Settings use Material 3 controls and follow Android's light or dark theme.
On Android 12 and newer, the settings screen uses the system colour palette.
Choose **Appearance → Colour mode → System colours** to use that palette for the
wallpaper too. It updates when your Android palette changes; time-of-day lighting
and brightness still apply. Older Android versions use a blue fallback palette.
Seasonal remains the default wallpaper colour setting.

## Build

Builds have been checked with JDK 21, Android SDK 37.0 and Build Tools 36.0.0.
Set `ANDROID_HOME` to your SDK directory, or set `sdk.dir` in a local
`local.properties` file. Then run:

```sh
./gradlew assembleDebug
```

On Windows, use `gradlew.bat assembleDebug`. You can also open the project in
Android Studio and build it there.

The APK is written to `app/build/outputs/apk/debug/`. The included renderer data
and shaders are enough to build; the TV project is not a build dependency.

To refresh those assets from an LG-XMB checkout:

```sh
node tools/export-assets.cjs /path/to/LG-XMB
```

The Android code lives under `app/src/main/java/org/lgxmb/wallpaper/`. Simulation,
rendering and Android services are separate files so changes stay local. The
wallpaper and screen saver share one render host for frame pacing, sensors and
graphics resource cleanup; neither keeps drawing after it becomes hidden.

The first build was checked on an Android 17 emulator at 1080 × 2400: wallpaper
preview, applying to both screens, closing a second preview, hiding behind an app,
and screen sleep/wake. The wallpaper thread used no CPU during the hidden sample.
Long-term battery use has not been measured.

Version 0.2.0 was checked on a Pixel 10 Pro XL running Android 17 at 1080 × 2404.
SurfaceFlinger recorded consistent 16–17 ms presentation intervals at the default
60 fps settings, with no dropped wallpaper frames in the sample. Pixel Launcher
sends a 0.5 offset step for its three home pages, which drives the panorama.

Version 0.4.5 was checked on the same Pixel with the phone locked: tapping out of
the screen saver refreshes the wallpaper before revealing it, keeping the waves
and sparkles in place. Other wake methods still use Android's normal transition
and may briefly show an old wallpaper frame.

`tests/` contains the simulation comparison and frame timing checks. See
`tests/README.md` to run them. `./gradlew build` also runs Android lint.

## Automatic builds and releases

The [Build APK workflow](https://github.com/Ripthulhu/Android-Waves/actions/workflows/build.yml)
runs the renderer checks, Android lint and APK builds on pushes and pull requests.
Test APKs are available in the run's artifacts.

For a release, update `versionCode` and `versionName` in `app/build.gradle`, write
the changes in `RELEASE_NOTES.md`, and push a matching `vX.Y.Z` tag. The workflow
signs the APK and publishes it under Releases. It uses the notes exactly as written.
The signing key is stored in GitHub secrets, outside the repository.

Release APKs use a different signing key from earlier development APKs. Switching
from a development build requires uninstalling it once, which resets its settings.

## Source and notices

This is a native Java/OpenGL ES 3 port of the renderer in
[LG-XMB](https://github.com/Ripthulhu/LG-XMB). Project code is GPL-3.0-or-later;
see `LICENSE`. The original shaders and their notices ship in
`app/src/main/assets/`.

The initial port uses LG-XMB revision `a2c0602238099696b4e88caf76aa4d772304b488`.
`source-hashes.json` records the exact imported data and shader files.
The bundled `WAVE-PROVENANCE.md` and `WEBGL2-NOTICES.md` are copied from
[that LG-XMB revision](https://github.com/Ripthulhu/LG-XMB/tree/a2c0602238099696b4e88caf76aa4d772304b488).
Their file paths refer to the TV repository; the Android equivalents are the
bundled assets and Java renderer in this project.
The asset exporter adds camera-position uniforms to the wave vertex shader; it does
not change the original wave simulation or lighting normals.

Phone motion uses the recovered PS3 sensor-difference gain for sparkle agitation.
Phone sensor filtering, strength limits and the small force applied to the wave
spring simulation are Android adaptations. The original controller's physical
sensor mapping and its effect on the waves have not been established. Without
phone input, the original simulation path remains unchanged.

Selected seed state and lookup/monthly textures were extracted from PS3 resources.
They retain their original ownership and are not relicensed under GPL. Renderer
provenance, particle-birth attribution and the FXAA notice are included in
`app/src/main/assets/licenses/`. No Sony fonts, audio or firmware executable is
included. This project is not affiliated with Sony or LG.
