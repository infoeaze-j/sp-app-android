package com.mediplus.spapp.ui.navigation

/**
 * The four destinations of the sequential journey (FR-032).
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
}
