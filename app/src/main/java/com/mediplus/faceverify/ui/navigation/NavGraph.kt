package com.mediplus.faceverify.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mediplus.faceverify.domain.model.SessionState
import com.mediplus.faceverify.ui.addservice.AddServiceRoute
import com.mediplus.faceverify.ui.facecheck.FaceCheckRoute
import com.mediplus.faceverify.ui.memberscan.MemberScanRoute
import com.mediplus.faceverify.ui.signin.SignInRoute

/**
 * The single-Activity navigation graph for the sequential journey (FR-032). A global guard forces a
 * return to sign-in whenever the session is not active — discarding progress on the UI side to match
 * the state-side wipe done by [com.mediplus.faceverify.core.session.SessionManager] (FR-004, FR-004a).
 * */
@Composable
fun NavGraph(
    navController: NavHostController = rememberNavController(),
    appViewModel: AppViewModel = hiltViewModel(),
) {
    val sessionState by appViewModel.sessionState.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(sessionState) {
        if (sessionState != SessionState.Active) {
            navController.navigate(AppRoute.SignIn.path) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = AppRoute.SignIn.path,
    ) {
        composable(AppRoute.SignIn.path) {
            SignInRoute(
                onSignedIn = {
                    navController.navigate(AppRoute.MemberScan.path) {
                        popUpTo(AppRoute.SignIn.path) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(AppRoute.MemberScan.path) {
            MemberScanRoute(
                onVerified = {
                    navController.navigate(AppRoute.FaceCheck.path) {
                        popUpTo(AppRoute.MemberScan.path) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(AppRoute.FaceCheck.path) {
            FaceCheckRoute(
                onVerified = {
                    navController.navigate(AppRoute.AddService.path) {
                        popUpTo(AppRoute.FaceCheck.path) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(AppRoute.AddService.path) {
            AddServiceRoute(
                onDone = {
                    // Journey complete: return to the card step to process the next patient.
                    navController.navigate(AppRoute.MemberScan.path) {
                        popUpTo(AppRoute.AddService.path) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
    }
}
