package com.mediplus.spapp.ui.navigation

import com.mediplus.spapp.domain.model.JourneyStep

/**
 * The four destinations of the enforced sequential journey (FR-032). Each maps to the
 * [JourneyStep] a user must have reached to be allowed here; nav guards enforce reachability.
 */
enum class AppRoute(val path: String, val requiredStep: JourneyStep) {
    SignIn("signin", JourneyStep.NOT_SIGNED_IN),
    MemberScan("memberscan", JourneyStep.MEMBER_SCAN),
    FaceCheck("face", JourneyStep.FACE_CHECK),
    AddService("addservice", JourneyStep.ENROLLMENT),
}
