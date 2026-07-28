package com.mediplus.spapp.dev

/**
 * The independently switchable fake seams. Each `Switching*` router owns exactly one seam and asks
 * [DevSettings.isFakeActive]; the master [DevSettings.fakeEnabled] toggle AND-gates all of them, so
 * a seam is faked only when the master toggle *and* its own toggle are on.
 *
 * Grouping follows what can usefully move independently, not the Dev UI's scenario rows:
 * [ENROLLMENT] covers the services list, currencies and enrollment because one repository serves
 * all three, and [UPDATE] covers both the update repository and the APK installer because a faked
 * APK cannot be handed to the real `PackageInstaller`.
 *
 * Constant names are persisted verbatim in the DataStore key (see [DevPrefKeys.seam]), so renaming
 * one silently resets that seam to its default of fake-on.
 */
enum class FakeSeam {
    /** Sign-in and sign-out against the fake back office. */
    AUTH,

    /** Device registration — turn this off to register this install with the real back office. */
    DEVICE,

    /** The emulated member card tap, in place of real NFC hardware. */
    CARD,

    /** The emulated camera, in place of real CameraX. */
    CAMERA,

    /** Member-number verification against the fake back office. */
    MEMBER,

    /** Face verification against the fake back office. */
    FACE,

    /** Services list, currencies and enrollment against the fake back office. */
    ENROLLMENT,

    /** The self-update version check, download and (faked) install. */
    UPDATE,

    /** The diagnostics poll/report endpoints on the fake back office. */
    DIAGNOSTICS,

    /** The device-state snapshot itself — turn this off to read real sensors. */
    DEVICE_STATE,
}
