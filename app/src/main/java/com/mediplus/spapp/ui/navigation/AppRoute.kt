package com.mediplus.spapp.ui.navigation

/**
 * The four destinations of the sequential journey (FR-032), plus [SelfCheck], the operator's
 * troubleshooting screen. [SelfCheck] sits outside the journey: it is reached only from [SignIn],
 * pushed on top of it rather than replacing it, so back returns to sign-in.
 *
 * A route carries nothing but its path: there is no per-destination reachability check. Order is
 * enforced by how the graph is driven rather than by a guard on arrival — every forward navigation
 * uses `popUpTo(...) { inclusive = true }`, leaving a single-entry back stack with nowhere to jump
 * back to, and `NavGraph`'s session guard pops the lot to [SignIn] whenever the session stops being
 * active. Whether enrollment may actually proceed is re-decided at submit time by
 * [com.mediplus.spapp.domain.usecase.AddServiceUseCase], not by having arrived here.
 */
enum class AppRoute(val path: String) {
    SignIn("signin"),
    MemberScan("memberscan"),
    FaceCheck("face"),
    AddService("addservice"),
    SelfCheck("selfcheck"),
}
