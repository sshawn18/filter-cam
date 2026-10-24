# FilterCam: Video and Aspect Ratios — Design Spec

Date: 2026-10-24
Builds on: `2026-10-06-filtercam-design.md` (engine) and `2026-10-11-ui-redesign-design.md` (UI). Those remain in force.

## Intent
Make FilterCam a complete everyday camera for the owner: filtered video with sound, and photo/video framing in
9:16, 3:4, 1:1, Full and two wide cinema frames. Success: the owner can record a filtered video, pick a frame,
and see both photos and videos in the in-app gallery without surprises.

## Scope
In:
- Frame selector (chips): `9:16`, `3:4` (default), `1:1`, `Full`, `2.39:1 Cinema`, `1.90:1 IMAX`. Applies to preview, photos and videos.
- Photo/Video mode switch above the shutter; video: 1080p, 30 fps, AAC audio, H.264, saved to `Movies/FilterCam` via MediaStore.
- Recording UI: red shutter, running timer (mm:ss), flip disabled while recording, zoom and filter changes allowed.
- Microphone permission requested at first video use; denial leaves photo mode working and shows an explanation.
- Gallery lists photos and videos together (newest first, video badge with duration); viewer plays videos; share/delete for both.
- Safe stop/finalize on: leaving the app, storage full, encoder/camera error. A failed recording leaves no partial file.
Out (later): 4K/60 fps, pause, slow motion, landscape-oriented UI, HDR video, per-lens selection.

## Framing rules
- Frame aspect = width/height as shown in the portrait view: 9:16 = 0.5625, 3:4 = 0.75, 1:1 = 1.0, Full = screen aspect (width/height of the app area, ~0.46), Cinema = 2.39 and IMAX = 1.90 (wide, low bands: full width, height = width/2.39 or width/1.90).
- Source is the 3:4 sensor stream. Narrower or wider frames are center crops of it (field of view shrinks; same as the stock Samsung camera). The viewfinder is shown at the frame's aspect, centered, black elsewhere.
- Photos: the filtered full-resolution bitmap is center-cropped to the frame after processing; photos keep the device-orientation rotation already implemented.
- Video output size: if aspect <= 1, width = 1080 and height = round(1080 / aspect) rounded down to an even number (9:16 → 1080x1920, 3:4 → 1080x1440, 1:1 → 1080x1080, Full → 1080x(even)); if aspect > 1, width = 1920 and height = round(1920 / aspect) rounded down to an even number (Cinema → 1920x804, IMAX → 1920x1010). Both dimensions are always even.
- The selected frame is remembered in settings.

## Architecture
Photo path is unchanged except for the final crop (`FrameCrop` pure function, unit-tested).
Video, preferred approach (Approach C): a CameraX `CameraEffect` with a custom `SurfaceProcessor` targeting `VIDEO_CAPTURE`
(and `PREVIEW` if the spike shows it is needed for parity) draws each camera frame through the existing `FilterProgram`
shader into the output surfaces CameraX provides; CameraX `Recorder`/`VideoCapture` does audio, encoding, muxing and
orientation. The existing GLSurfaceView preview keeps working unchanged unless the spike requires moving preview into the same processor.
**Spike first (Task 1):** prove on the owner's phone that a filtered frame reaches a recorded file with Approach C.
**Fallback (Approach B):** if the spike fails, a hand-built recorder: second EGL surface from the renderer's GL context into a
`MediaCodec` input surface (recordable EGL config), `AudioRecord` → AAC `MediaCodec`, `MediaMuxer`, same UI and storage. The fallback
is chosen by the spike result and recorded in the ledger; downstream tasks only depend on the interface below.

Interface (both approaches): `VideoRecorder { start(frame: Frame, filter: FilterParams, strength: () -> Float, onEvent: (RecEvent) -> Unit); stop(); val state: StateFlow<RecState> }`
with `RecState = Idle | Recording(elapsedMs) | Finalizing` and `RecEvent = Saved(uri) | Failed(message)`.

Units: `frame/Frame.kt` (enum + `aspect`, `videoSize()`, `cropRect(bitmapW, bitmapH)`), `video/VideoRecorder.kt` (+ processor/effect classes),
`gallery/GalleryRepository` extended with `MediaItem(id, uri, isVideo, durationMs)`, UI: `FrameSelector`, `ModeSwitch`, recording shutter/timer,
video viewer (`VideoView`), mic permission flow.

## Error handling
Mic denied → snackbar, stay in photo mode. Recording failure → delete the partial MediaStore row, snackbar with reason.
App paused during recording → stop and finalize, snackbar "Recording saved" (or failure). Storage low (<100 MB at start) → refuse to start with a message.
Gallery: unreadable video thumbnail → placeholder; deleted-outside-app items disappear on refresh.

## Testing
- Unit (JVM): `Frame` aspects, `videoSize()` (even dimensions, 1080 short side), `cropRect()` for every frame on 3000x4000 and 4000x3000 bitmaps (centered, within bounds, correct ratio ±1 px).
- Device (owner's phone): record 10 s in each frame, verify with `adb pull` + `ffprobe`-free checks (file exists, duration, size) and by playing it in the in-app viewer; confirm the filter is visible in the recorded video; flip/zoom/filter change during recording; background during recording; deny mic.
- Existing tests keep passing.

## Open risks
Approach C's `SurfaceProcessor` API behavior on this phone (spike); GL cost of the filter at 1080p30 on the video output while also drawing the preview; Full-frame aspect depends on screen insets.
