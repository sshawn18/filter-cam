# FilterCam UI Redesign — Design Spec

Date: 2026-10-11
Builds on: `2026-10-06-filtercam-design.md` (filter engine, capture path: unchanged).

## Intent
A personal-use camera app that looks and behaves like a normal stock camera app, and lets the
user bring in filters designed together with Claude (import) and make/tune/save their own on the phone.
Success: the user is comfortable using it as their everyday camera and can add a new filter without a rebuild.

## Scope
In: dark Material 3 theme; polished camera screen; filter strip with strength; tune sheet (old dev panel)
with Save-as-new; import / export / rename / delete of user filters; in-app gallery + viewer; settings;
app icon and name; camera-permission screen; flash, grid, tap-to-focus, pinch-to-zoom.
Out (v2): video, live filter thumbnails in chips, light theme, onboarding tour, RAW, software bokeh.
Unchanged: shader, `FilterProgram`, `PhotoProcessor`, capture resolution cap, `FilterParams` JSON schema.

## Architecture
Camera is the home screen. Gallery and Settings are full screens; Tune, Strength and Manage Filters are
bottom sheets. Navigation is a simple `sealed interface Screen` held in state (no Navigation library).
Single `Activity`, Compose UI, one `FilterViewModel` (extended) plus a `SettingsRepository`.

Units (one purpose each):
- `filter/FilterStore` — loads bundled presets (`assets/filters/*.json`, read-only) and user presets
  (`filesDir/filters/*.json`); `save(params)`, `import(uri): Result<FilterParams>`, `rename`, `delete`,
  `exportFile(name): Uri` (via FileProvider). Depends on `FilterParams`. Replaces `FilterRepository`.
- `filter/FilterParams` — add validation: `fromJson` returns a clear error (not a crash) for malformed JSON,
  color lists that are not exactly 3 elements, or non-finite numbers; add `isBuiltIn` handled by the store, not the JSON.
- `settings/SettingsRepository` — DataStore-free: `SharedPreferences` wrapper for grid default, flash default, JPEG quality, last-used filter.
- `gallery/GalleryRepository` — queries MediaStore for images in `Pictures/FilterCam`; delete via MediaStore.
- `ui/camera/CameraScreen` — viewfinder host, top bar, filter strip, shutter row, gestures (tap-to-focus via
  `CameraControl.startFocusAndMetering`, pinch via `setZoomRatio`), flash via `ImageCapture.flashMode`.
- `ui/camera/TuneSheet`, `ui/camera/StrengthSheet`, `ui/filters/ManageFiltersSheet`, `ui/gallery/GalleryScreen`,
  `ui/gallery/PhotoViewer`, `ui/settings/SettingsScreen`, `ui/theme/Theme.kt` (colors, typography, shapes).
- `camera/CameraController` — extend with `cameraControl`/`cameraInfo` access, flash mode, zoom (existing bind/capture unchanged).

Data flow: selecting a filter sets `FilterViewModel.params`; the renderer reads it as today. Save-as-new copies the
current tuned params under a user-entered name into `FilterStore`. Import copies a validated file into `filesDir/filters`.

## Behavior details
- Filter names are unique, 1-24 chars; saving over an existing user filter asks to replace; built-in names cannot be reused.
- Import: Android document picker (`application/json`); a bad file shows a snackbar naming the problem; a good file is added and selected.
- Export: Android share sheet with the `.json` file.
- Delete/rename only for user filters; built-in Jade is read-only (UI hides those actions).
- Flash: off/on/auto on back camera; hidden on front camera. Grid: rule-of-thirds overlay. Zoom resets on camera flip.
- Gallery shows newest first; viewer supports share and delete (with confirmation).
- Permission denied: full-screen rationale with a button to retry, and "Open settings" when the system will no longer prompt.
- The front-camera saved-photo mirroring decision is a setting in Settings (default: not mirrored, like stock camera apps).

## Carry-in fixes (from earlier reviews, in scope because this work touches them)
- Shutter busy guard and recycling stay; the capture path keeps its resolution cap.
- `fromJson` hardening (above) replaces the deferred "short color list crashes" minor.
- Permanently-denied permission handling (deferred I8) is implemented here.
- Camera flip robustness (deferred I7: fresh SurfaceTexture per request) is verified on the device first; implemented only if flipping shows a black preview.

## Error handling
Import/validation errors -> snackbar with a specific message. Storage write failures -> snackbar, no partial files (write to temp, then rename).
Gallery query/delete failures -> snackbar. Existing capture/save failure toasts become snackbars.

## Testing
- Unit (JVM): `FilterParams.fromJson` validation cases; `FilterStore` name rules and import/save/rename/delete on a temp dir
  (store takes a `File` directory so it is testable without Android); `SettingsRepository` defaults via an in-memory fake interface.
- Instrumented (on the Galaxy): import a good and a bad filter file, save-as-new, rename, delete; gallery lists a new photo;
  permission denied flow. UI checked on the phone by screenshot against this spec after each task.
- Existing tests (`FilterParamsTest`, golden test) must keep passing.

## Build/tooling
Same toolchain as the original spec. New dependencies are limited to: `androidx.compose.material:material-icons-extended`
(icons) and `androidx.activity:activity-compose` (already present); no image-loading library is added (gallery thumbnails
decoded with `ContentResolver.loadThumbnail`).
