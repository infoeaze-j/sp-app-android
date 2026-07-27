package com.mediplus.spapp.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.mediplus.spapp.R
import com.mediplus.spapp.core.ui.components.ActionDrawer
import com.mediplus.spapp.core.ui.components.DrawerAction
import com.mediplus.spapp.domain.model.SessionState
import com.mediplus.spapp.ui.addservice.AddServiceRoute
import com.mediplus.spapp.ui.facecheck.FaceCheckRoute
import com.mediplus.spapp.ui.memberscan.MemberScanRoute
import com.mediplus.spapp.ui.signin.SignInRoute
import com.mediplus.spapp.ui.update.UpdateHost

/**
 * The single-Activity navigation graph for the sequential journey (FR-032). A global guard forces a
 * return to sign-in whenever the session is not active — discarding progress on the UI side to match
 * the state-side wipe done by [com.mediplus.spapp.core.session.SessionManager] (FR-004, FR-004a).
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
    val providerName by appViewModel.providerName.collectAsStateWithLifecycle()

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
        LogOutConfirmDrawer(
            onConfirm = {
                confirmingLogOut = false
                appViewModel.logOut()
            },
            onDismiss = { confirmingLogOut = false },
        )
    }

    Box {
        Scaffold(
            topBar = {
                if (showAppBar) AppBar(providerName = providerName, onLogOutClick = { confirmingLogOut = true })
            },
        ) { innerPadding ->
            NavGraphHost(navController, Modifier.padding(innerPadding))
        }
        // Drawn last so a forced-update overlay covers the chrome too — log out included: with the
        // build unusable there is nothing meaningful to log out of, and the journey is void anyway.
        UpdateHost()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppBar(providerName: String?, onLogOutClick: () -> Unit) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = stringResource(R.string.appbar_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (providerName != null) {
                    Text(
                        text = providerName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
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
private fun LogOutConfirmDrawer(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ActionDrawer(
        title = stringResource(R.string.logout_confirm_title),
        confirm = DrawerAction(labelRes = R.string.action_log_out, onClick = onConfirm),
        dismiss = DrawerAction(labelRes = R.string.action_cancel, onClick = onDismiss),
    ) {
        Text(
            text = stringResource(R.string.logout_confirm_body),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
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
