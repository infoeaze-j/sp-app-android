package com.mediplus.faceverify.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mediplus.faceverify.R
import com.mediplus.faceverify.domain.model.SessionState
import com.mediplus.faceverify.ui.addservice.AddServiceRoute
import com.mediplus.faceverify.ui.facecheck.FaceCheckRoute
import com.mediplus.faceverify.ui.memberscan.MemberScanRoute
import com.mediplus.faceverify.ui.signin.SignInRoute

/**
 * The single-Activity navigation graph for the sequential journey (FR-032). A global guard forces a
 * return to sign-in whenever the session is not active — discarding progress on the UI side to match
 * the state-side wipe done by [com.mediplus.faceverify.core.session.SessionManager] (FR-004, FR-004a).
 *
 * It also owns the app's only chrome: a top bar carrying the log out action, present on every
 * destination except sign-in. The bar's inner padding is what gives each screen its window insets,
 * so screens below do not handle insets themselves.
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

    val currentRoute by navController.currentBackStackEntryAsState()
    // Sign-in is the one place with nothing to log out of.
    val showAppBar = currentRoute?.destination?.route != AppRoute.SignIn.path
    var confirmingLogOut by remember { mutableStateOf(false) }

    if (confirmingLogOut) {
        LogOutConfirmDialog(
            onConfirm = {
                confirmingLogOut = false
                appViewModel.logOut()
            },
            onDismiss = { confirmingLogOut = false },
        )
    }

    Scaffold(
        topBar = { if (showAppBar) AppBar(onLogOutClick = { confirmingLogOut = true }) },
    ) { innerPadding ->
        NavGraphHost(navController, Modifier.padding(innerPadding))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppBar(onLogOutClick: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.appbar_title)) },
        actions = {
            // Never disabled: log out has to work mid-capture and mid-request alike.
            IconButton(onClick = onLogOutClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Logout,
                    contentDescription = stringResource(R.string.action_log_out),
                )
            }
        },
    )
}

/**
 * Confirmation is unconditional. The wording holds whether or not a patient is mid-verification, so
 * the control never behaves differently based on state the operator cannot see.
 */
@Composable
private fun LogOutConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.logout_confirm_title)) },
        text = { Text(stringResource(R.string.logout_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_log_out)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun NavGraphHost(navController: NavHostController, modifier: Modifier) {
    NavHost(
        navController = navController,
        startDestination = AppRoute.SignIn.path,
        modifier = modifier,
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
