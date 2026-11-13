#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
upstream=${1:?Usage: bash tools/check.sh /path/to/LG-XMB}
android_jar="${ANDROID_HOME:?Set ANDROID_HOME}/platforms/android-37.0/android.jar"
test -f "$android_jar"
checks=.build/checks
mkdir -p "$checks/classes"
curl -fsSL --retry 3 https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar \
  -o "$checks/json.jar"
echo "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed  $checks/json.jar" | sha256sum -c -
node tests/export-simulation-reference.cjs "$upstream" "$checks/simulation.json"
node tests/export-navigation-reference.cjs "$upstream" "$checks/navigation.json"
classpath="$checks/json.jar:$android_jar"
javac -cp "$classpath" -d "$checks/classes" \
  app/src/main/java/org/lgxmb/wallpaper/{BackgroundClock,FrameTiming,NavigationResponse,ParticleSimulation,PhoneMotion,PhoneMotionResponse,SceneProjection,ScreensaverFade,ScrollMotion,SystemPalette,WallpaperSettings,WaveScene,WaveSimulation}.java \
  tests/*Check.java
for file in tests/*Check.java; do
  name=$(basename "$file" .java)
  args=()
  case "$name" in
    SimulationCheck) args=(app/src/main/assets/reference.json "$checks/simulation.json") ;;
    NavigationResponseCheck) args=(app/src/main/assets/reference.json "$checks/navigation.json") ;;
    PhoneMotionResponseCheck|PortraitParticleCheck|WaveSceneCheck) args=(app/src/main/assets/reference.json) ;;
  esac
  java -ea -cp "$checks/classes:$classpath" "org.lgxmb.wallpaper.$name" "${args[@]}"
done
