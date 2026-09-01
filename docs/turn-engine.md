# Turn Engine Pipeline

Status: Task 4 (Page Curl Lab). The math kernel is stable and fully tested.
The GL/touch layers compile and pass JVM contract tests after the review-fix
round (shader uniform declaration, VBO wiring, params plumbing, projection
coverage, thread ownership); they still await on-device validation — the
page-curl render path has never executed against real GLES/EGL.

## Modules

```
reader/turn    pure Kotlin/JVM — no Android deps
  CurlSolver   developable-curl math: solve / constrainTarget / deformPoint / decideRelease
  TurnGesture  cancellable gesture reducer state machine (Press/Move/Armed/Progress/Release/...)
  CurlMesh     seam-aligned mesh generator (clips every triangle at d=0 and d=PI*r)
  Vec2, CurlState, DeformedPoint, VelocitySample

reader/turngl  Android library — GL plumbing, no touch handling
  CurlShaderProgram   embedded GLSL ES sources (vertex + fragment)
  CurlShaderMath      JVM mirror of the shader's piecewise deformation
  CurlProjection      pure-Kotlin frustum/MVP math (JVM-testable; C-4 coverage)
  CurlMeshBuffers     VBO staging + capacity math (proxy-driven, GL-tested)
  CurlGLRenderer      GLES-free lifecycle state machine (NEW -> READY -> ERROR/RELEASED)
  CurlTextureHost     frame-pacing brain (request coalescing, dirty mode, stop)
  CurlTextureViewHost TextureView.SurfaceTextureListener + EGL14 + render thread
  CurlEglFrame        actual draw path (program setup, textures, two draw passes)
  CurlTextureView     TextureView subclass; touch intentionally NOT handled here

app/lab        Page Curl Lab diagnostic activity
  CurlLabActivity     full-screen TextureView + status bar + touch pipeline
  CurlLabPipeline     pure JVM coordinator: events -> reducer -> solver -> mesh -> params
  CheckerPageTextures code-generated front/back checker textures
```

## Pipeline (one drag frame)

```
MotionEvent (UI thread)
  -> TurnGesture.clientToCanonical   screen px -> canonical (hinge x=0, free edge x=W, y centered)
  -> TurnGesture.resolveDragDirection locked dir after SLOP; blocked turns rejected
  -> TurnGesture.reduce              Press/Move/Armed/Release; generation token guards Armed
  -> CurlSolver.constrainTarget      Q clamped to hinge-reach disks and 2*allowance
  -> CurlSolver.solve                axis point/normal, radius cap, progress, phase
  -> CurlMesh.build                  seam-aligned grid into a REUSED MeshOutput (zero alloc on drag)
  -> CurlTextureViewHost.uploadMesh  render thread streams the VBO (build + upload both on the
                                     render thread; the UI thread only feeds the reducer)
  -> CurlEglFrame.draw               front pass (uIsBack=0, +halfThickness), back pass (uIsBack=1, -halfThickness)
```

Settle: `decideRelease` (via reducer outcome) -> Commit or Cancel -> 300 ms
ease animation interpolating axisPoint/radius to the flat end state.

## Shader <-> solver contract

The vertex shader reproduces `CurlSolver.deformPoint` exactly:

| branch | condition | lateral (along n) | z | normal |
|---|---|---|---|---|
| flat front | d <= 0 | d | 0 | +z |
| cylindrical | 0 < d < PI*r, r >= 1e-4 | r*sin(d/r) | r*(1-cos(d/r)) | (-sin*n.x, -sin*n.y, cos) |
| flat back | d >= PI*r | -(d - PI*r) | 2r | -z |

`d = dot(p - uAxisPoint, n)` is the signed distance in material space.
**`uAxisNormal` must be non-zero**: the vertex shader normalizes it, and
GLSL `normalize(vec2(0))` is undefined — a zero normal would poison the
whole deformation. CurlMesh.build already guards this with a (1, 0)
fallback; any new producer of frame params must do the same.
`CurlShaderMath.deform` is a JVM mirror of this mapping;
`CurlShaderMathTest` fails if it drifts from `CurlSolver.deformPoint`, and
`CurlShaderProgramSourceTest` locks the GLSL source contract (uniforms,
branches, back-face UV mirror `vec2(1-uv.x, uv.y)`, half-thickness offset).

Back/face materials: two draw calls over the same VBO. Front samples `uFront`
at `vUv`; back samples `uBack` at mirrored UV. The two sheets are separated
along the deformed normal by `uOffset` = 0.35 px (sub-pixel paper thickness)
so they never fight for the same depth.

## Performance invariants

Drag path must never: re-paginate, rebuild Bitmaps, re-upload full textures,
or query a database (the reader's page source is consulted only on commit,
never mid-drag; the lab has no database at all).

- Textures: two checker Bitmaps generated once at `configure()` and uploaded
  to GL once via `CurlEglFrame.uploadTexture`. Drags reuse them as-is.
- Mesh: `CurlMesh.allocOutput` reused across frames; VBO updated with
  `glBufferSubData`; no per-drag allocation of GPU memory.
- Idle: `CurlTextureHost` coalesces requests; with no pending request and no
  dirty flag the render thread sleeps (24 ms poll), drawing nothing.
- `release()` paths are idempotent (`CurlGLRenderer.release`,
  `CurlTextureHost.stop`, `CurlTextureViewHost.stop`).

## Threading contract

- UI thread: touch events -> `TurnGesture` reducer; pushes nothing into GL.
- Render thread ("curl-render"): owns EGL + all GLES calls; runs solve ->
  `CurlMesh.build` (shared `MeshOutput`) -> VBO upload -> draw, and draws the
  params returned by the frame listener THIS tick.
- Shared state between them is limited to immutable snapshots: gesture state
  (`TurnGestureState` data class), `CurlFrameParams`, and settle endpoints
  (volatile fields). The `MeshOutput` arrays are render-thread-private.

## Known limitations

- Single-page forward drag only in the lab (dir is resolved but backward
  turns mirror through the same math; the lab wires dir=1 presses).
- The lab does not implement the 52-96 dp edge-band grab restriction; the
  whole page is draggable. That policy belongs to the reader integration.
- Hover fold preview (API 26+ OnHoverListener) is deferred; the pipeline is
  ready for it (feed hover points through `move()` with a synthetic pointer).
- Debug visualizations (normal RGB / UV overlay) are deferred.
- Commit settle animates geometry only; no page-swap occurs (next task).

## Device verification

After the review-fix round the following still needs a real device:

- Shader actually compiles/links on real GLES drivers (JVM tests lock the
  source contract but cannot run the GLSL compiler).
- Curl visible and unclipped at max radius (C-4 projection coverage).
- Checkerboard on both faces with correct mirroring; crease shading.
- Settle animation renders from the returned params (C-3) — no one-frame lag.
- destroy -> re-available cycle keeps rendering (I-6).
- No texture upload from the UI thread (I-1): texture appears after EGL init,
  no GL warnings.

```bash
# build + install
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# launch the lab directly
adb shell am start -n com.lfq06.arknightsreader/.lab.CurlLabActivity

# scripted drag (forward turn): press at right edge, drag to hinge, release
adb shell input swipe 900 1400 100 1400 400

# slow drag (cancel): short distance, slow
adb shell input swipe 900 1400 800 1400 800

# watch the status bar (phase/progress/radius/axis) via logcat while dragging
adb logcat -c && adb logcat | grep -i curl

# unit tests
./gradlew :reader:turn:test :reader:turngl:testDebugUnitTest :app:testDebugUnitTest
```

Expected: forward swipe curls the page along a diagonal-free axis with the
checkerboard visible on both faces (BACK 2 mirrored), crease shadow at the
fold, release past mid-page commits (page flattens), slow short drags snap
back. The status bar shows phase=DRAGGING with progress climbing toward 1.
