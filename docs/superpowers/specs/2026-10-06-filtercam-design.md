# FilterCam — Design Spec

Date: 2026-10-06

## Goal
A native Android camera app for Samsung Galaxy phones with real-time, hand-tuned
film-style filters. Filters are co-designed with the user from reference images
(not auto-extracted). First preset: **Jade**.

## Scope (v1)
- Live camera preview with the active filter applied in real time (front + back camera).
- Shutter saves a filtered JPEG to `Pictures/FilterCam` via MediaStore.
- Filter strip to switch presets; strength slider (0-100%).
- Dev panel (toggle) with sliders for every filter parameter, for live tuning on-device.
- One preset: Jade. Adding presets = adding a parameter JSON (and optionally shader code).

Out of scope for v1: video, RAW, software portrait blur / bokeh, .cube LUT import,
auto-extracting a filter from a photo.

## Architecture
Kotlin, minSdk 29, CameraX, OpenGL ES 3.0.

Units (each with one purpose):
- `CameraController` — CameraX Preview + ImageCapture use cases, camera switching. Depends on CameraX only.
- `GlFilterRenderer` — GLSurfaceView renderer. Takes the camera SurfaceTexture (external OES), draws it through the filter shader to the screen. Depends on `FilterParams`.
- `FilterShader` (GLSL fragment shader) — single-pass: white balance/tint, tone curve (lifted tinted blacks, soft shoulder), split-toning (green-teal shadows, cool highlights), selective hue handling (greens deepen, skin hues protected/warm), saturation, halation (thresholded blur-ish glow approximated in-pass), grain (animated noise), vignette, then `mix(original, graded, strength)`.
- `FilterParams` — data class of all parameters; loaded from `assets/filters/*.json`. Single source of truth for preview and capture.
- `PhotoProcessor` — applies the same shader offscreen (FBO) to the captured full-res bitmap and writes JPEG via MediaStore, so saved photo matches preview.
- `UI` (Jetpack Compose) — preview surface, shutter, filter strip, strength slider, dev panel.

Data flow: camera frame → OES texture → FilterShader(params) → screen.
Capture: ImageCapture JPEG → decode → PhotoProcessor (same shader, FBO) → MediaStore.

## Jade look (from reference)
- Deep emerald/teal shadows with green-tinted lifted blacks; no neutral black.
- Skin stays natural: slightly desaturated peach-rose, lips muted coral — warm vs green separation is the core of the look.
- Low-key, moody, gentle filmic contrast; soft cool highlights.
- Fine film grain, soft halation around bright edges, strong vignette.
Initial parameter values are a first guess; tuned live with the dev panel against the reference.

## Error handling
- Camera permission denied: show rationale screen with retry.
- GL/shader init failure: show a visible "filters unsupported on this device" error (no unfiltered fallback in v1; OpenGL ES 3.0 is required by the manifest).
- Capture or save failure: error toast; the user simply retakes the photo (no in-memory retry in v1).

## Testing
- Unit tests: `FilterParams` JSON parsing; parameter clamping.
- Shader reference test: run the shader offscreen on a fixed test image and compare to a golden image (tolerance) to catch regressions.
- On-device manual check against the reference image on a real Galaxy via `adb install`.

## Build/tooling
JDK 17 + Android SDK command-line tools, Gradle from the CLI (no Android Studio).
Each download is confirmed with the user first. Project lives in `X:\CC\filter-cam`.
