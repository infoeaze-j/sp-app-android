# Performance & Leak Detection

Constitution Principle IV — off-main-thread work, bounded memory, <16ms frames, <2s cold start.

## LeakCanary (T060)

LeakCanary 2.x is a `debugImplementation` dependency and **auto-installs** via its own
`ContentProvider` — no per-screen wiring is required. It watches every destroyed `Activity`,
`Fragment`, `View`, and `ViewModel`, which covers the camera (`FaceCheckScreen`) and NFC
(`NfcScanScreen`) verification screens.

Bounded-memory measures already in code:

- **CameraX** is lifecycle-bound (`bindToLifecycle`); the single-thread analysis executor is
  shut down in the screen's `onDispose`.
- Every `ImageProxy` is closed promptly (`addOnCompleteListener { imageProxy.close() }` in
  `FaceFramingAnalyzer`; `image.close()` after capture in `CameraController`).
- The captured face frame is a `TransientFrame` that is **zeroed and dropped** immediately after the
  decision returns or aborts (`FaceRepositoryImpl.verify` `finally`), verified by
  `FaceFrameDisposalTest`.

**How to verify:** run a debug build through the face-check and NFC flows on a device; LeakCanary
must report **no leaks**.

## Performance measurement (T062)

Target device: **Google Pixel 6a** (or an equivalent ~2022 mid-range device — record the actual
device used).

| Metric | Budget | How to measure |
|--------|--------|----------------|
| Cold start → interactive | < 2s | `adb shell am start -W -n com.mediplus.faceverify/.MainActivity` → `TotalTime` |
| Per-attempt end-to-end (capture → decision) | network-bound; record on-device portion | app trace / Perfetto around the face submit |
| Full sign-in → enroll journey | < 5 min for a cooperative subject (SC-001) | manual walk-through timing |
| Framing evaluation | < 500ms/frame budget; UI thread never blocked | Perfetto; ML Kit runs on the analysis executor, off-main |

> **Status:** these measurements must be taken on the reference device. They have **not** been
> captured in the current build environment (no physical device available), and are the one part of
> Phase 7 that remains device-gated. Record the actual numbers and device here once run.
