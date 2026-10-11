# FilterCam Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A native Android camera app for Samsung Galaxy phones with a real-time GPU filter pipeline, shipping with one hand-tuned preset, "Jade".

**Architecture:** CameraX Preview renders into a SurfaceTexture (external OES); a single-pass GLSL fragment shader grades each frame, driven by a `FilterParams` data class loaded from JSON. Capture decodes the JPEG and runs the *same* shader offscreen (EGL pbuffer + FBO) so the saved photo matches the preview. UI is Jetpack Compose with a live dev panel for tuning.

**Tech Stack:** Kotlin 2.0.20, AGP 8.5.2, Gradle 8.9, JDK 17, compileSdk/targetSdk 34, minSdk 29, CameraX 1.3.4, OpenGL ES 3.0, Jetpack Compose (BOM 2024.09.00), kotlinx-serialization-json 1.7.2, JUnit4.

**Spec:** `docs/superpowers/specs/2026-10-06-filtercam-design.md`

## Global Constraints

- Native Android, Kotlin, package `com.filtercam`, project root `X:\CC\filter-cam`.
- minSdk 29; OpenGL ES 3.0 (`#version 300 es`); portrait-only activity.
- Preview and saved photo MUST use the same `FilterProgram` (WYSIWYG).
- Saved photos go to `Pictures/FilterCam` via MediaStore (no storage permission needed on API 29+).
- v1 scope only: no video, RAW, software bokeh, .cube import, or auto-extraction of filters from photos.
- Every download (JDK, SDK, Gradle) needs the user's explicit confirmation before it runs.
- `git commit` steps run only once the user okays commits (the project gets its own repo, `X:\CC` is not a repo).
- Pinned versions above are known-good; bumping is fine if a build error demands it.
- Windows host: use PowerShell or Git Bash; toolchain lives in `C:\Users\Nikhil\android-dev` (outside the repo).

## File Structure

```
filter-cam/
  settings.gradle.kts, build.gradle.kts, gradle.properties, .gitignore, local.properties
  scripts/setup-toolchain.ps1
  app/build.gradle.kts
  app/src/main/AndroidManifest.xml
  app/src/main/assets/filters/jade.json
  app/src/main/java/com/filtercam/
    MainActivity.kt
    filter/FilterParams.kt        # params data + ParamKey + JSON + clamping
    filter/FilterRepository.kt    # loads presets from assets
    gl/Shaders.kt                 # GLSL source (vertex + fragment)
    gl/FilterProgram.kt           # compile/link, uniforms, draw quad (shared by preview+capture)
    gl/GlFilterRenderer.kt        # GLSurfaceView.Renderer for live preview (OES)
    gl/PhotoProcessor.kt          # offscreen EGL render of a Bitmap
    camera/CameraController.kt    # CameraX preview + capture
    storage/PhotoSaver.kt         # MediaStore JPEG save
    ui/FilterViewModel.kt, ui/CameraScreen.kt, ui/DevPanel.kt
  app/src/test/java/com/filtercam/filter/FilterParamsTest.kt
  app/src/androidTest/java/com/filtercam/PhotoProcessorGoldenTest.kt
```

---

### Task 1: Build toolchain (needs user confirmation per download)

**Files:**
- Create: `scripts/setup-toolchain.ps1`

**Interfaces:**
- Produces: `C:\Users\Nikhil\android-dev\{jdk17, sdk, gradle-8.9}`, env vars `JAVA_HOME`, `ANDROID_HOME`, and `scripts\env.ps1` that sets them + PATH for the current shell.

- [ ] **Step 1: Ask the user to confirm the downloads** (JDK 17 Temurin ~190 MB, Android cmdline-tools ~150 MB, Gradle 8.9 ~130 MB, then platform-34 + build-tools ~1 GB via sdkmanager).

- [ ] **Step 2: Write `scripts/setup-toolchain.ps1`**

```powershell
$ErrorActionPreference = "Stop"
$root = "C:\Users\Nikhil\android-dev"
New-Item -ItemType Directory -Force $root | Out-Null
function Get-Zip($url, $dest) {
  $zip = Join-Path $env:TEMP ([IO.Path]::GetFileName($dest) + ".zip")
  Invoke-WebRequest $url -OutFile $zip
  Expand-Archive $zip -DestinationPath $dest -Force
  Remove-Item $zip
}
if (-not (Test-Path "$root\jdk17")) {
  Get-Zip "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse" "$root\jdk-tmp"
  Move-Item (Get-ChildItem "$root\jdk-tmp" | Select -First 1).FullName "$root\jdk17"; Remove-Item "$root\jdk-tmp"
}
if (-not (Test-Path "$root\gradle-8.9")) {
  Get-Zip "https://services.gradle.org/distributions/gradle-8.9-bin.zip" $root
}
if (-not (Test-Path "$root\sdk\cmdline-tools\latest")) {
  Get-Zip "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" "$root\sdk\ct-tmp"
  New-Item -ItemType Directory -Force "$root\sdk\cmdline-tools" | Out-Null
  Move-Item "$root\sdk\ct-tmp\cmdline-tools" "$root\sdk\cmdline-tools\latest"; Remove-Item "$root\sdk\ct-tmp"
}
@"
`$env:JAVA_HOME = "$root\jdk17"
`$env:ANDROID_HOME = "$root\sdk"
`$env:Path = "$root\jdk17\bin;$root\gradle-8.9\bin;$root\sdk\cmdline-tools\latest\bin;$root\sdk\platform-tools;`$env:Path"
"@ | Set-Content "$PSScriptRoot\env.ps1"
. "$PSScriptRoot\env.ps1"
"y`ny`ny`ny`ny`ny`ny`ny" | sdkmanager --licenses | Out-Null
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

- [ ] **Step 3: Run it** (after confirmation): `powershell -ExecutionPolicy Bypass -File scripts\setup-toolchain.ps1`
Expected: finishes without error.

- [ ] **Step 4: Verify**

Run: `. scripts\env.ps1; java -version; gradle -v; sdkmanager --list_installed`
Expected: Java 17, Gradle 8.9, `platforms;android-34` and `build-tools;34.0.0` listed.

---

### Task 2: Project scaffold that builds and launches

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `.gitignore`, `local.properties`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/filtercam/MainActivity.kt`

**Interfaces:**
- Produces: a Gradle project with Compose, CameraX, serialization and test deps; `MainActivity` showing "FilterCam".

- [ ] **Step 1: `settings.gradle.kts`**

```kotlin
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "filter-cam"
include(":app")
```

- [ ] **Step 2: root `build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20" apply false
}
```

- [ ] **Step 3: `gradle.properties`, `.gitignore`, `local.properties`**

```properties
# gradle.properties
org.gradle.jvmargs=-Xmx2g
android.useAndroidX=true
kotlin.code.style=official
```
```
# .gitignore
.gradle/
build/
local.properties
scripts/env.ps1
*.iml
.idea/
```
```properties
# local.properties
sdk.dir=C\:\\Users\\Nikhil\\android-dev\\sdk
```

- [ ] **Step 4: `app/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}
android {
    namespace = "com.filtercam"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.filtercam"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}
dependencies {
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
```

- [ ] **Step 5: `AndroidManifest.xml`**

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.camera.any" />
    <uses-feature android:glEsVersion="0x00030000" android:required="true" />
    <application android:label="FilterCam" android:theme="@android:style/Theme.Material.NoActionBar">
        <activity android:name=".MainActivity" android:exported="true"
            android:screenOrientation="portrait">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 6: `MainActivity.kt` (placeholder UI replaced in Task 7)**

```kotlin
package com.filtercam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Text("FilterCam") }
    }
}
```

- [ ] **Step 7: Generate the wrapper and build**

Run: `. scripts\env.ps1; gradle wrapper --gradle-version 8.9; .\gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`; `app\build\outputs\apk\debug\app-debug.apk` exists.

- [ ] **Step 8: Install on the phone (USB debugging on)**

Run: `. scripts\env.ps1; adb devices; adb install -r app\build\outputs\apk\debug\app-debug.apk`
Expected: device listed as `device`, install `Success`, app opens showing "FilterCam".

- [ ] **Step 9: Commit** (once the user okays commits)

```bash
git init && git add -A && git commit -m "chore: scaffold FilterCam project"
```

---

### Task 3: FilterParams, ParamKey, JSON, Jade preset (TDD)

**Files:**
- Create: `app/src/main/java/com/filtercam/filter/FilterParams.kt`, `app/src/main/java/com/filtercam/filter/FilterRepository.kt`, `app/src/main/assets/filters/jade.json`
- Test: `app/src/test/java/com/filtercam/filter/FilterParamsTest.kt`

**Interfaces:**
- Produces:
  - `data class FilterParams(name, exposure, temperature, tint, contrast, blackLift, shadowColor: List<Float>, shadowAmount, highlightColor: List<Float>, highlightAmount, saturation, greenDeepen, skinProtect, halation, grain, vignette)`
  - `FilterParams.clamped(): FilterParams`, `FilterParams.get(key: ParamKey): Float`, `FilterParams.with(key: ParamKey, value: Float): FilterParams`, `FilterParams.fromJson(json: String): FilterParams`
  - `enum class ParamKey(val label: String, val range: ClosedFloatingPointRange<Float>)`: EXPOSURE, TEMPERATURE, TINT, CONTRAST, BLACK_LIFT, SHADOW_R, SHADOW_G, SHADOW_B, SHADOW_AMOUNT, HIGHLIGHT_R, HIGHLIGHT_G, HIGHLIGHT_B, HIGHLIGHT_AMOUNT, SATURATION, GREEN_DEEPEN, SKIN_PROTECT, HALATION, GRAIN, VIGNETTE
  - `class FilterRepository(context) { fun loadAll(): List<FilterParams> }` (reads every `assets/filters/*.json`)

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.filtercam.filter

import org.junit.Assert.assertEquals
import org.junit.Test

class FilterParamsTest {
    @Test fun parsesJsonAndAppliesDefaults() {
        val p = FilterParams.fromJson("""{"name":"X","contrast":1.2}""")
        assertEquals("X", p.name)
        assertEquals(1.2f, p.contrast, 0.0001f)
        assertEquals(0f, p.grain, 0.0001f)
        assertEquals(listOf(0f, 0f, 0f), p.shadowColor)
    }

    @Test fun clampedLimitsEveryScalarToItsRange() {
        val p = FilterParams(name = "X", contrast = 9f, grain = -3f, shadowColor = listOf(5f, -1f, 0.5f)).clamped()
        assertEquals(ParamKey.CONTRAST.range.endInclusive, p.contrast, 0.0001f)
        assertEquals(ParamKey.GRAIN.range.start, p.grain, 0.0001f)
        assertEquals(listOf(1f, 0f, 0.5f), p.shadowColor)
    }

    @Test fun withThenGetRoundTripsForEveryKey() {
        var p = FilterParams(name = "X")
        for (k in ParamKey.values()) {
            val v = k.range.endInclusive
            p = p.with(k, v)
            assertEquals(k.name, v, p.get(k), 0.0001f)
        }
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `. scripts\env.ps1; .\gradlew testDebugUnitTest`
Expected: FAIL (compilation: `FilterParams` unresolved).

- [ ] **Step 3: Implement `FilterParams.kt`**

```kotlin
package com.filtercam.filter

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class ParamKey(val label: String, val range: ClosedFloatingPointRange<Float>) {
    EXPOSURE("Exposure", -1.5f..1.5f),
    TEMPERATURE("Temperature", -1f..1f),
    TINT("Tint (+magenta)", -1f..1f),
    CONTRAST("Contrast", 0.5f..1.6f),
    BLACK_LIFT("Black lift", 0f..0.2f),
    SHADOW_R("Shadow R", 0f..1f), SHADOW_G("Shadow G", 0f..1f), SHADOW_B("Shadow B", 0f..1f),
    SHADOW_AMOUNT("Shadow tint", 0f..1f),
    HIGHLIGHT_R("Highlight R", 0f..1f), HIGHLIGHT_G("Highlight G", 0f..1f), HIGHLIGHT_B("Highlight B", 0f..1f),
    HIGHLIGHT_AMOUNT("Highlight tint", 0f..1f),
    SATURATION("Saturation", 0f..1.6f),
    GREEN_DEEPEN("Green deepen", 0f..1f),
    SKIN_PROTECT("Skin protect", 0f..1f),
    HALATION("Halation", 0f..1f),
    GRAIN("Grain", 0f..1f),
    VIGNETTE("Vignette", 0f..1f),
}

@Serializable
data class FilterParams(
    val name: String,
    val exposure: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val contrast: Float = 1f,
    val blackLift: Float = 0f,
    val shadowColor: List<Float> = listOf(0f, 0f, 0f),
    val shadowAmount: Float = 0f,
    val highlightColor: List<Float> = listOf(0f, 0f, 0f),
    val highlightAmount: Float = 0f,
    val saturation: Float = 1f,
    val greenDeepen: Float = 0f,
    val skinProtect: Float = 0f,
    val halation: Float = 0f,
    val grain: Float = 0f,
    val vignette: Float = 0f,
) {
    fun get(key: ParamKey): Float = when (key) {
        ParamKey.EXPOSURE -> exposure
        ParamKey.TEMPERATURE -> temperature
        ParamKey.TINT -> tint
        ParamKey.CONTRAST -> contrast
        ParamKey.BLACK_LIFT -> blackLift
        ParamKey.SHADOW_R -> shadowColor[0]
        ParamKey.SHADOW_G -> shadowColor[1]
        ParamKey.SHADOW_B -> shadowColor[2]
        ParamKey.SHADOW_AMOUNT -> shadowAmount
        ParamKey.HIGHLIGHT_R -> highlightColor[0]
        ParamKey.HIGHLIGHT_G -> highlightColor[1]
        ParamKey.HIGHLIGHT_B -> highlightColor[2]
        ParamKey.HIGHLIGHT_AMOUNT -> highlightAmount
        ParamKey.SATURATION -> saturation
        ParamKey.GREEN_DEEPEN -> greenDeepen
        ParamKey.SKIN_PROTECT -> skinProtect
        ParamKey.HALATION -> halation
        ParamKey.GRAIN -> grain
        ParamKey.VIGNETTE -> vignette
    }

    fun with(key: ParamKey, v: Float): FilterParams = when (key) {
        ParamKey.EXPOSURE -> copy(exposure = v)
        ParamKey.TEMPERATURE -> copy(temperature = v)
        ParamKey.TINT -> copy(tint = v)
        ParamKey.CONTRAST -> copy(contrast = v)
        ParamKey.BLACK_LIFT -> copy(blackLift = v)
        ParamKey.SHADOW_R -> copy(shadowColor = shadowColor.replaced(0, v))
        ParamKey.SHADOW_G -> copy(shadowColor = shadowColor.replaced(1, v))
        ParamKey.SHADOW_B -> copy(shadowColor = shadowColor.replaced(2, v))
        ParamKey.SHADOW_AMOUNT -> copy(shadowAmount = v)
        ParamKey.HIGHLIGHT_R -> copy(highlightColor = highlightColor.replaced(0, v))
        ParamKey.HIGHLIGHT_G -> copy(highlightColor = highlightColor.replaced(1, v))
        ParamKey.HIGHLIGHT_B -> copy(highlightColor = highlightColor.replaced(2, v))
        ParamKey.HIGHLIGHT_AMOUNT -> copy(highlightAmount = v)
        ParamKey.SATURATION -> copy(saturation = v)
        ParamKey.GREEN_DEEPEN -> copy(greenDeepen = v)
        ParamKey.SKIN_PROTECT -> copy(skinProtect = v)
        ParamKey.HALATION -> copy(halation = v)
        ParamKey.GRAIN -> copy(grain = v)
        ParamKey.VIGNETTE -> copy(vignette = v)
    }

    fun clamped(): FilterParams = ParamKey.values().fold(this) { p, k ->
        p.with(k, p.get(k).coerceIn(k.range))
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun fromJson(s: String): FilterParams = json.decodeFromString<FilterParams>(s).clamped()
    }
}

private fun List<Float>.replaced(i: Int, v: Float): List<Float> = toMutableList().also { it[i] = v }
```

- [ ] **Step 4: Implement `FilterRepository.kt`**

```kotlin
package com.filtercam.filter

import android.content.Context

class FilterRepository(private val context: Context) {
    fun loadAll(): List<FilterParams> =
        (context.assets.list("filters") ?: emptyArray())
            .filter { it.endsWith(".json") }
            .sorted()
            .map { f -> FilterParams.fromJson(context.assets.open("filters/$f").bufferedReader().use { it.readText() }) }
}
```

- [ ] **Step 5: Create `assets/filters/jade.json`** (first guess, tuned in Task 9)

```json
{
  "name": "Jade",
  "exposure": -0.15,
  "temperature": 0.0,
  "tint": -0.25,
  "contrast": 1.12,
  "blackLift": 0.05,
  "shadowColor": [0.02, 0.20, 0.15],
  "shadowAmount": 0.6,
  "highlightColor": [0.80, 0.92, 0.88],
  "highlightAmount": 0.25,
  "saturation": 0.92,
  "greenDeepen": 0.7,
  "skinProtect": 0.8,
  "halation": 0.35,
  "grain": 0.5,
  "vignette": 0.55
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `.\gradlew testDebugUnitTest`
Expected: 3 tests PASS.

- [ ] **Step 7: Commit** `git add -A && git commit -m "feat: FilterParams, ParamKey, Jade preset"`

---

### Task 4: Shader and FilterProgram

**Files:**
- Create: `app/src/main/java/com/filtercam/gl/Shaders.kt`, `app/src/main/java/com/filtercam/gl/FilterProgram.kt`

**Interfaces:**
- Consumes: `FilterParams` (Task 3).
- Produces: `class FilterProgram(oes: Boolean)` with
  `fun draw(textureId: Int, texMatrix: FloatArray, params: FilterParams, strength: Float, timeSec: Float, aspect: Float)` and `fun release()`.
  Must be constructed on a thread with a current GL context. `texMatrix` is a 4x4 column-major matrix mapping quad coords (0..1) to texture coords. `aspect` = output width / height.

- [ ] **Step 1: `Shaders.kt`**

```kotlin
package com.filtercam.gl

object Shaders {
    const val VERTEX = """
in vec2 aPos;
uniform mat4 uTexMatrix;
out vec2 vUv;
out vec2 vScreen;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vScreen = aPos * 0.5 + 0.5;
    vUv = (uTexMatrix * vec4(vScreen, 0.0, 1.0)).xy;
}
"""

    const val FRAGMENT = """
precision highp float;
in vec2 vUv;
in vec2 vScreen;
out vec4 fragColor;
#ifdef USE_OES
uniform samplerExternalOES uTex;
#else
uniform sampler2D uTex;
#endif
uniform mat2 uUvLin;
uniform float uTime, uAspect;
uniform float uExposure, uTemperature, uTint, uContrast, uBlackLift;
uniform vec3 uShadowColor, uHighlightColor;
uniform float uShadowAmount, uHighlightAmount, uSaturation, uGreenDeepen,
              uSkinProtect, uHalation, uGrain, uVignette, uStrength;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}
vec3 hsv2rgb(vec3 c) {
    vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}
float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }

void main() {
    vec3 orig = texture(uTex, vUv).rgb;
    vec3 c = orig;

    // 1. exposure + white balance
    c *= exp2(uExposure);
    c.r *= 1.0 + 0.10 * uTemperature;
    c.b *= 1.0 - 0.10 * uTemperature;
    c.g *= 1.0 - 0.06 * uTint;
    c = clamp(c, 0.0, 1.0);

    // 2. hue masks (skin incl. reds/lips, foliage greens)
    vec3 hsv = rgb2hsv(c);
    float h2 = hsv.x > 0.5 ? hsv.x - 1.0 : hsv.x;
    float skin = smoothstep(-0.06, -0.02, h2) * (1.0 - smoothstep(0.08, 0.14, h2))
               * smoothstep(0.10, 0.25, hsv.y) * smoothstep(0.15, 0.35, hsv.z);
    float green = smoothstep(0.17, 0.25, hsv.x) * (1.0 - smoothstep(0.42, 0.50, hsv.x))
                * smoothstep(0.10, 0.30, hsv.y);
    vec3 preTone = c;

    // 3. deepen greens toward teal
    vec3 g = hsv;
    g.z *= 1.0 - 0.35 * uGreenDeepen * green;
    g.x += 0.03 * uGreenDeepen * green;
    g.y *= 1.0 + 0.15 * uGreenDeepen * green;
    c = hsv2rgb(vec3(fract(g.x), clamp(g.y, 0.0, 1.0), clamp(g.z, 0.0, 1.0)));

    // 4. contrast, lifted tinted blacks, split toning
    c = (c - 0.5) * uContrast + 0.5;
    c = c * (1.0 - uBlackLift) + uBlackLift * uShadowColor;
    float l = dot(c, LUMA);
    float sw = 1.0 - smoothstep(0.0, 0.55, l);
    float hw = smoothstep(0.45, 1.0, l);
    c += sw * uShadowAmount * (uShadowColor - dot(uShadowColor, LUMA));
    c += hw * uHighlightAmount * (uHighlightColor - dot(uHighlightColor, LUMA));

    // 5. protect skin and lips: pull the toned result most of the way back to the pre-tone color
    c = mix(c, preTone + (c - preTone) * 0.35, skin * uSkinProtect);

    // 6. saturation
    l = dot(c, LUMA);
    c = mix(vec3(l), c, uSaturation);

    // 7. halation: glow from bright areas, ring sampled in screen space
    vec3 glow = vec3(0.0);
    for (int i = 0; i < 8; i++) {
        float a = 6.2831853 * float(i) / 8.0;
        vec2 off = vec2(cos(a) / uAspect, sin(a)) * 0.012;
        glow += max(texture(uTex, vUv + uUvLin * off).rgb - vec3(0.65), 0.0);
    }
    c += (glow / 8.0) * uHalation * 3.0 * vec3(0.9, 1.0, 0.8);

    // 8. grain (midtone weighted), vignette
    float n = hash(vScreen * vec2(uAspect, 1.0) * 1400.0 + uTime) - 0.5;
    c += n * uGrain * 0.14 * (1.0 - abs(l * 2.0 - 1.0) * 0.5);
    float d = length((vScreen - 0.5) * vec2(uAspect, 1.0));
    c *= 1.0 - uVignette * smoothstep(0.25, 0.85, d);

    fragColor = vec4(mix(orig, clamp(c, 0.0, 1.0), uStrength), 1.0);
}
"""

    fun vertexSource() = "#version 300 es\n$VERTEX"
    fun fragmentSource(oes: Boolean) = buildString {
        append("#version 300 es\n")
        if (oes) append("#define USE_OES\n#extension GL_OES_EGL_image_external_essl3 : require\n")
        append(FRAGMENT)
    }
}
```

- [ ] **Step 2: `FilterProgram.kt`**

```kotlin
package com.filtercam.gl

import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.filtercam.filter.FilterParams
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FilterProgram(private val oes: Boolean) {
    private val program: Int
    private val uniforms = HashMap<String, Int>()
    private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }

    init {
        val vs = compile(GLES30.GL_VERTEX_SHADER, Shaders.vertexSource())
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, Shaders.fragmentSource(oes))
        program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] == GLES30.GL_TRUE) { "Link failed: " + GLES30.glGetProgramInfoLog(program) }
    }

    fun draw(textureId: Int, texMatrix: FloatArray, p: FilterParams, strength: Float, timeSec: Float, aspect: Float) {
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(if (oes) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(u("uTex"), 0)
        GLES30.glUniformMatrix4fv(u("uTexMatrix"), 1, false, texMatrix, 0)
        // 2x2 linear part of the tex matrix (column-major mat4 -> mat2)
        GLES30.glUniformMatrix2fv(u("uUvLin"), 1, false,
            floatArrayOf(texMatrix[0], texMatrix[1], texMatrix[4], texMatrix[5]), 0)
        GLES30.glUniform1f(u("uTime"), timeSec)
        GLES30.glUniform1f(u("uAspect"), aspect)
        GLES30.glUniform1f(u("uExposure"), p.exposure)
        GLES30.glUniform1f(u("uTemperature"), p.temperature)
        GLES30.glUniform1f(u("uTint"), p.tint)
        GLES30.glUniform1f(u("uContrast"), p.contrast)
        GLES30.glUniform1f(u("uBlackLift"), p.blackLift)
        GLES30.glUniform3f(u("uShadowColor"), p.shadowColor[0], p.shadowColor[1], p.shadowColor[2])
        GLES30.glUniform1f(u("uShadowAmount"), p.shadowAmount)
        GLES30.glUniform3f(u("uHighlightColor"), p.highlightColor[0], p.highlightColor[1], p.highlightColor[2])
        GLES30.glUniform1f(u("uHighlightAmount"), p.highlightAmount)
        GLES30.glUniform1f(u("uSaturation"), p.saturation)
        GLES30.glUniform1f(u("uGreenDeepen"), p.greenDeepen)
        GLES30.glUniform1f(u("uSkinProtect"), p.skinProtect)
        GLES30.glUniform1f(u("uHalation"), p.halation)
        GLES30.glUniform1f(u("uGrain"), p.grain)
        GLES30.glUniform1f(u("uVignette"), p.vignette)
        GLES30.glUniform1f(u("uStrength"), strength)
        val pos = GLES30.glGetAttribLocation(program, "aPos")
        GLES30.glEnableVertexAttribArray(pos)
        GLES30.glVertexAttribPointer(pos, 2, GLES30.GL_FLOAT, false, 0, quad)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(pos)
    }

    fun release() = GLES30.glDeleteProgram(program)

    private fun u(name: String) = uniforms.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GLES30.GL_TRUE) { "Shader compile failed: " + GLES30.glGetShaderInfoLog(s) }
        return s
    }
}
```

- [ ] **Step 3: Build**

Run: `.\gradlew assembleDebug`
Expected: BUILD SUCCESSFUL (the shader is first compiled on a device in Task 5/6).

- [ ] **Step 4: Commit** `git add -A && git commit -m "feat: filter shader and FilterProgram"`

---

### Task 5: Live preview (renderer + CameraController + minimal screen)

**Files:**
- Create: `app/src/main/java/com/filtercam/gl/GlFilterRenderer.kt`, `app/src/main/java/com/filtercam/camera/CameraController.kt`
- Modify: `app/src/main/java/com/filtercam/MainActivity.kt`

**Interfaces:**
- Consumes: `FilterProgram` (Task 4), `FilterRepository` (Task 3).
- Produces:
  - `class GlFilterRenderer(context: Context) : GLSurfaceView.Renderer` with `@Volatile var params: FilterParams`, `@Volatile var strength: Float`, `@Volatile var mirror: Boolean`, `lateinit var glView: GLSurfaceView`, `val surfaceProvider: Preview.SurfaceProvider`.
  - `class CameraController(context: Context)` with `fun bind(owner: LifecycleOwner, provider: Preview.SurfaceProvider, front: Boolean)` and `fun capture(onResult: (Result<Bitmap>) -> Unit)`. The returned bitmap is upright (rotation applied), unfiltered.

- [ ] **Step 1: `GlFilterRenderer.kt`**

```kotlin
package com.filtercam.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.core.content.ContextCompat
import com.filtercam.filter.FilterParams
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class GlFilterRenderer(private val context: Context) : GLSurfaceView.Renderer {
    @Volatile var params = FilterParams(name = "none")
    @Volatile var strength = 1f
    @Volatile var mirror = false
    lateinit var glView: GLSurfaceView

    private var program: FilterProgram? = null
    private var oesTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var pending: SurfaceRequest? = null
    @Volatile private var rotation = 0
    private var aspect = 3f / 4f
    private val st = FloatArray(16)
    private val rot = FloatArray(16)
    private val texMatrix = FloatArray(16)
    private val t0 = System.nanoTime()

    val surfaceProvider = Preview.SurfaceProvider { req ->
        synchronized(this) { pending = req }
        glView.queueEvent { fulfil() }
        glView.requestRender()
    }

    private fun fulfil() {
        val tex = surfaceTexture ?: return
        val req = synchronized(this) { pending.also { pending = null } } ?: return
        tex.setDefaultBufferSize(req.resolution.width, req.resolution.height)
        val surface = Surface(tex)
        val executor = ContextCompat.getMainExecutor(context)
        req.setTransformationInfoListener(executor) { rotation = it.rotationDegrees }
        req.provideSurface(surface, executor) { surface.release() }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = FilterProgram(oes = true)
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        oesTex = ids[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        surfaceTexture = SurfaceTexture(oesTex).also { it.setOnFrameAvailableListener { glView.requestRender() } }
        fulfil()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        aspect = width.toFloat() / height
    }

    override fun onDrawFrame(gl: GL10?) {
        val tex = surfaceTexture ?: return
        tex.updateTexImage()
        tex.getTransformMatrix(st)
        Matrix.setIdentityM(rot, 0)
        Matrix.translateM(rot, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(rot, 0, -rotation.toFloat(), 0f, 0f, 1f)
        if (mirror) Matrix.scaleM(rot, 0, -1f, 1f, 1f)
        Matrix.translateM(rot, 0, -0.5f, -0.5f, 0f)
        Matrix.multiplyMM(texMatrix, 0, st, 0, rot, 0)
        val time = ((System.nanoTime() - t0) / 1_000_000L % 100_000L) / 1000f
        program?.draw(oesTex, texMatrix, params, strength, time, aspect)
    }
}
```

- [ ] **Step 2: `CameraController.kt`**

```kotlin
package com.filtercam.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

class CameraController(private val context: Context) {
    private val selector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO))
        .build()
    private val imageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setResolutionSelector(selector)
        .build()

    fun bind(owner: LifecycleOwner, provider: Preview.SurfaceProvider, front: Boolean) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val cp = future.get()
            val preview = Preview.Builder().setResolutionSelector(selector).build()
            preview.setSurfaceProvider(provider)
            cp.unbindAll()
            cp.bindToLifecycle(
                owner,
                if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                preview, imageCapture,
            )
        }, ContextCompat.getMainExecutor(context))
    }

    fun capture(onResult: (Result<Bitmap>) -> Unit) {
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val result = runCatching {
                        val raw = image.toBitmap()
                        val m = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
                        Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                    }
                    image.close()
                    onResult(result)
                }
                override fun onError(e: ImageCaptureException) = onResult(Result.failure(e))
            },
        )
    }
}
```

- [ ] **Step 3: Temporary preview screen in `MainActivity.kt`** (replaced by the full UI in Task 7)

```kotlin
package com.filtercam

import android.Manifest
import android.opengl.GLSurfaceView
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.filtercam.camera.CameraController
import com.filtercam.filter.FilterRepository
import com.filtercam.gl.GlFilterRenderer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val ctx = LocalContext.current
            val owner = LocalLifecycleOwner.current
            var granted by remember { mutableStateOf(false) }
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
            LaunchedEffect(Unit) { launcher.launch(Manifest.permission.CAMERA) }
            if (granted) {
                val renderer = remember { GlFilterRenderer(ctx).apply { params = FilterRepository(ctx).loadAll().first() } }
                val controller = remember { CameraController(ctx) }
                AndroidView(
                    modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
                    factory = { c ->
                        GLSurfaceView(c).apply {
                            setEGLContextClientVersion(3)
                            renderer.glView = this
                            setRenderer(renderer)
                            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                        }
                    },
                )
                LaunchedEffect(Unit) { controller.bind(owner, renderer.surfaceProvider, front = false) }
            }
        }
    }
}
```

- [ ] **Step 4: Build, install, and verify on the phone**

Run: `.\gradlew installDebug; adb logcat -c; adb shell am start -n com.filtercam/.MainActivity`
Expected on the phone: camera permission prompt, then a live Jade-graded preview.
Check `adb logcat *:E | findstr /i "filtercam shader"` shows no shader compile errors.

- [ ] **Step 5: Fix orientation/mirror if needed.** Back camera must be upright and not mirrored. If sideways or upside-down, flip the sign of `rotation` or add 90/180 in `onDrawFrame` (the only line to change is `Matrix.rotateM`). Re-run Step 4 until correct.

- [ ] **Step 6: Commit** `git add -A && git commit -m "feat: live filtered preview"`

---

### Task 6: PhotoProcessor and PhotoSaver (capture path)

**Files:**
- Create: `app/src/main/java/com/filtercam/gl/PhotoProcessor.kt`, `app/src/main/java/com/filtercam/storage/PhotoSaver.kt`

**Interfaces:**
- Consumes: `FilterProgram(oes=false)`, `FilterParams`.
- Produces:
  - `class PhotoProcessor { fun process(src: Bitmap, params: FilterParams, strength: Float): Bitmap }` (self-contained: creates and tears down its own EGL context on the calling thread; time is fixed at 0f so output is deterministic)
  - `object PhotoSaver { fun save(context: Context, bitmap: Bitmap): Uri }`

- [ ] **Step 1: `PhotoProcessor.kt`**

```kotlin
package com.filtercam.gl

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import com.filtercam.filter.FilterParams
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PhotoProcessor {
    fun process(src: Bitmap, params: FilterParams, strength: Float): Bitmap {
        val dpy: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        check(EGL14.eglInitialize(dpy, ver, 0, ver, 1)) { "eglInitialize failed" }
        val cfgAttr = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT_KHR */,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        check(EGL14.eglChooseConfig(dpy, cfgAttr, 0, cfgs, 0, 1, n, 0) && n[0] > 0) { "no EGL config" }
        val ctx: EGLContext = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        val surf: EGLSurface = EGL14.eglCreatePbufferSurface(dpy, cfgs[0],
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) { "eglMakeCurrent failed" }
        try {
            val w = src.width
            val h = src.height
            val ids = IntArray(2)
            GLES30.glGenTextures(2, ids, 0)
            // input texture
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, src, 0)
            // output texture + FBO
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[1])
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            val fbo = IntArray(1)
            GLES30.glGenFramebuffers(1, fbo, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, ids[1], 0)
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "FBO incomplete" }
            GLES30.glViewport(0, 0, w, h)

            val program = FilterProgram(oes = false)
            val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
            // Identity cancels: bitmap row 0 uploads at t=0, readPixels row 0 is the bottom of the frame.
            program.draw(ids[0], identity, params, strength, 0f, w.toFloat() / h)
            program.release()

            val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.copyPixelsFromBuffer(buf)
            GLES30.glDeleteFramebuffers(1, fbo, 0)
            GLES30.glDeleteTextures(2, ids, 0)
            return out
        } finally {
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(dpy, surf)
            EGL14.eglDestroyContext(dpy, ctx)
            EGL14.eglTerminate(dpy)
        }
    }
}
```

- [ ] **Step 2: `PhotoSaver.kt`**

```kotlin
package com.filtercam.storage

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

object PhotoSaver {
    fun save(context: Context, bitmap: Bitmap): Uri {
        val name = "FilterCam_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FilterCam")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) { "MediaStore insert failed" }
        resolver.openOutputStream(uri).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, checkNotNull(out))) { "JPEG compress failed" }
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
```

- [ ] **Step 3: Build** `.\gradlew assembleDebug` — Expected: BUILD SUCCESSFUL. (Behavior is verified end-to-end in Task 7.)

- [ ] **Step 4: Commit** `git add -A && git commit -m "feat: offscreen photo processing and saving"`

---

### Task 7: Full UI (ViewModel, CameraScreen, filter strip, strength, shutter, dev panel)

**Files:**
- Create: `app/src/main/java/com/filtercam/ui/FilterViewModel.kt`, `app/src/main/java/com/filtercam/ui/CameraScreen.kt`, `app/src/main/java/com/filtercam/ui/DevPanel.kt`
- Modify: `app/src/main/java/com/filtercam/MainActivity.kt`

**Interfaces:**
- Consumes: everything above.
- Produces: `FilterViewModel(app: Application) : AndroidViewModel` with `val presets: List<FilterParams>`, `val params: StateFlow<FilterParams>`, `val strength: StateFlow<Float>`, `fun select(i: Int)`, `fun setStrength(v: Float)`, `fun setParam(k: ParamKey, v: Float)`.

- [ ] **Step 1: `FilterViewModel.kt`**

```kotlin
package com.filtercam.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.filtercam.filter.FilterParams
import com.filtercam.filter.FilterRepository
import com.filtercam.filter.ParamKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FilterViewModel(app: Application) : AndroidViewModel(app) {
    val presets: List<FilterParams> = FilterRepository(app).loadAll()
    private val _params = MutableStateFlow(presets.first())
    private val _strength = MutableStateFlow(1f)
    val params: StateFlow<FilterParams> = _params
    val strength: StateFlow<Float> = _strength

    fun select(i: Int) { _params.value = presets[i] }
    fun setStrength(v: Float) { _strength.value = v.coerceIn(0f, 1f) }
    fun setParam(k: ParamKey, v: Float) { _params.value = _params.value.with(k, v.coerceIn(k.range)).clamped() }
}
```

- [ ] **Step 2: `DevPanel.kt`**

```kotlin
package com.filtercam.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.filtercam.filter.FilterParams
import com.filtercam.filter.ParamKey

@Composable
fun DevPanel(params: FilterParams, onChange: (ParamKey, Float) -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
        ParamKey.values().forEach { k ->
            Row {
                Text("${k.label}  %.2f".format(params.get(k)), color = Color.White)
            }
            Slider(value = params.get(k), onValueChange = { onChange(k, it) }, valueRange = k.range)
        }
    }
}
```

- [ ] **Step 3: `CameraScreen.kt`**

```kotlin
package com.filtercam.ui

import android.opengl.GLSurfaceView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.filtercam.camera.CameraController
import com.filtercam.gl.GlFilterRenderer
import com.filtercam.gl.PhotoProcessor
import com.filtercam.storage.PhotoSaver
import java.util.concurrent.Executors

private val worker = Executors.newSingleThreadExecutor()

@Composable
fun CameraScreen(vm: FilterViewModel) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val params by vm.params.collectAsState()
    val strength by vm.strength.collectAsState()
    var front by remember { mutableStateOf(false) }
    var dev by remember { mutableStateOf(false) }
    val renderer = remember { GlFilterRenderer(ctx) }
    val controller = remember { CameraController(ctx) }

    renderer.params = params
    renderer.strength = strength
    renderer.mirror = front
    LaunchedEffect(front) { controller.bind(owner, renderer.surfaceProvider, front) }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
            factory = { c ->
                GLSurfaceView(c).apply {
                    setEGLContextClientVersion(3)
                    renderer.glView = this
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                }
            },
            update = { it.requestRender() },
        )
        LazyRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(vm.presets) { i, p ->
                Text(p.name, color = if (p.name == params.name) Color.White else Color.Gray,
                    modifier = Modifier.clickable { vm.select(i) })
            }
        }
        Slider(value = strength, onValueChange = vm::setStrength, modifier = Modifier.padding(horizontal = 16.dp))
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Flip", color = Color.White, modifier = Modifier.clickable { front = !front })
            Box(Modifier.size(72.dp).clip(CircleShape).background(Color.White).clickable {
                controller.capture { result ->
                    result.onSuccess { bmp ->
                        val p = params; val s = strength
                        worker.execute {
                            runCatching { PhotoSaver.save(ctx, PhotoProcessor().process(bmp, p, s)) }
                                .onSuccess { toast(ctx, "Saved to Pictures/FilterCam") }
                                .onFailure { toast(ctx, "Save failed: ${it.message}") }
                        }
                    }.onFailure { toast(ctx, "Capture failed: ${it.message}") }
                }
            })
            Text(if (dev) "Dev ✓" else "Dev", color = Color.White, modifier = Modifier.clickable { dev = !dev })
        }
        if (dev) DevPanel(params, vm::setParam)
    }
}

private fun toast(ctx: android.content.Context, msg: String) {
    android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
}
```

- [ ] **Step 4: Replace `MainActivity.kt`** with permission handling and rationale

```kotlin
package com.filtercam

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.filtercam.ui.CameraScreen
import com.filtercam.ui.FilterViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var granted by remember { mutableStateOf(false) }
            var asked by remember { mutableStateOf(false) }
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
            LaunchedEffect(Unit) { launcher.launch(Manifest.permission.CAMERA) }
            if (granted) {
                CameraScreen(viewModel<FilterViewModel>())
            } else if (asked) {
                Column {
                    Text("FilterCam needs camera access to show the live preview and take photos.")
                    Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Grant permission") }
                }
            }
        }
    }
}
```

- [ ] **Step 4b: Surface shader failures.** In `GlFilterRenderer.onSurfaceCreated`, wrap `program = FilterProgram(oes = true)` in `try/catch (e: Exception)`; on failure post `Toast "Filters unsupported on this device: ${e.message}"` on the main thread via `Handler(Looper.getMainLooper())` and return, so the app shows the error instead of crashing.

- [ ] **Step 5: Build, install, verify end-to-end on the phone**

Run: `.\gradlew installDebug`
Expected on device: live Jade preview; strength slider fades to the unfiltered image at 0; Dev panel sliders change the preview live; Flip switches cameras; shutter shows a "Saved to Pictures/FilterCam" toast, and the photo in the gallery looks like the preview (same grade, upright). Denying the permission shows the rationale screen with a retry button.

- [ ] **Step 6: Commit** `git add -A && git commit -m "feat: camera UI, presets, strength, dev panel"`

---

### Task 8: Shader golden test

**Files:**
- Create: `app/src/androidTest/java/com/filtercam/PhotoProcessorGoldenTest.kt`, `app/src/androidTest/assets/jade_golden.png` (recorded, Step 3)

**Interfaces:**
- Consumes: `PhotoProcessor.process`, `FilterParams.fromJson`.

- [ ] **Step 1: Write the test.** The input is generated deterministically (green foliage gradient, skin patch, red lips patch, bright highlight). With `-e record true` it writes the output to the app's external files dir instead of comparing.

```kotlin
package com.filtercam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import com.filtercam.filter.FilterParams
import com.filtercam.gl.PhotoProcessor
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhotoProcessorGoldenTest {
    private fun testInput(): Bitmap {
        val b = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint()
        for (y in 0 until 400) { // foliage gradient, dark to mid green
            p.color = Color.rgb(20 + y / 20, 60 + y / 6, 30 + y / 20)
            c.drawLine(0f, y.toFloat(), 300f, y.toFloat(), p)
        }
        p.color = Color.rgb(224, 172, 140); c.drawRect(80f, 80f, 220f, 220f, p) // skin
        p.color = Color.rgb(170, 70, 70); c.drawRect(110f, 240f, 190f, 270f, p) // lips
        p.color = Color.rgb(250, 250, 240); c.drawRect(10f, 10f, 50f, 50f, p) // highlight
        return b
    }

    @Test fun jadeMatchesGolden() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val json = inst.targetContext.assets.open("filters/jade.json").bufferedReader().use { it.readText() }
        val out = PhotoProcessor().process(testInput(), FilterParams.fromJson(json), 1f)

        if (InstrumentationRegistry.getArguments().getString("record") == "true") {
            val f = File(inst.targetContext.getExternalFilesDir(null), "jade_golden.png")
            f.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return
        }
        val golden = BitmapFactory.decodeStream(inst.context.assets.open("jade_golden.png"))
        var sum = 0L
        for (y in 0 until out.height) for (x in 0 until out.width) {
            val a = out.getPixel(x, y); val g = golden.getPixel(x, y)
            sum += Math.abs(Color.red(a) - Color.red(g)) + Math.abs(Color.green(a) - Color.green(g)) + Math.abs(Color.blue(a) - Color.blue(g))
        }
        val meanAbsDiff = sum / (out.width * out.height * 3.0)
        assertTrue("mean abs diff $meanAbsDiff > 2.0", meanAbsDiff <= 2.0)
    }
}
```

- [ ] **Step 2: Record the golden (run after Jade is tuned in Task 9; for now just verify it records)**

Run: `.\gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.record=true"`
then `adb pull /sdcard/Android/data/com.filtercam/files/jade_golden.png app\src\androidTest\assets\jade_golden.png`
Expected: PNG pulled; open it and confirm it looks like a Jade-graded version of the test image (green shadows, warm skin and lips).

- [ ] **Step 3: Run the test for real**

Run: `.\gradlew connectedDebugAndroidTest`
Expected: PASS (mean abs diff ≈ 0 on the same device).

- [ ] **Step 4: Commit** `git add -A && git commit -m "test: Jade shader golden test"`

---

### Task 9: Tune Jade against the reference (interactive with the user)

**Files:**
- Modify: `app/src/main/assets/filters/jade.json`; re-record `app/src/androidTest/assets/jade_golden.png`

- [ ] **Step 1:** User points the phone at a face-and-foliage scene (or displays the reference on a screen), opens the Dev panel, and adjusts sliders until the look matches the reference: emerald shadows, green-lifted blacks, natural warm skin, muted coral lips, fine grain, soft glow, strong vignette.
- [ ] **Step 2:** Add a "Copy params" action in the Dev panel only if values are tedious to read off (otherwise the user reads the slider values aloud/screenshots them). Copy the final values into `jade.json`.
- [ ] **Step 3:** Rebuild, reinstall, and compare the saved photo against the reference side by side.
- [ ] **Step 4:** Re-record the golden (Task 8 Step 2), run `.\gradlew testDebugUnitTest connectedDebugAndroidTest`; all green.
- [ ] **Step 5: Commit** `git add -A && git commit -m "feat: tune Jade preset"`
