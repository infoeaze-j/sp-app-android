package com.mediplus.faceverify.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mediplus.faceverify.domain.model.SessionState
import com.mediplus.faceverify.ui.signin.SignInRoute

/**
 * The single-Activity navigation graph for the sequential journey (FR-032). A global guard forces a
 * return to sign-in whenever the session is not active — discarding progress on the UI side to match
 * the state-side wipe done by [com.mediplus.faceverify.core.session.SessionManager] (FR-004, FR-004a).
 *
 * Screens are wired in per user story (T024/T035/T047/T057); placeholders stand in until then.
 */
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
                    navController.navigate(AppRoute.NfcScan.path) {
                        popUpTo(AppRoute.SignIn.path) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(AppRoute.NfcScan.path) { PlaceholderDestination("Scan document") }
        composable(AppRoute.FaceCheck.path) { PlaceholderDestination("Face check") }
        composable(AppRoute.AddService.path) { PlaceholderDestination("Add service") }
    }
}

@Composable
private fun PlaceholderDestination(label: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(label)
    }
}
