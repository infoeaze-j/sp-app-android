package com.mediplus.spapp.core.ui.components

/**
 * One of an [ActionDrawer]'s two actions. [labelRes] is a string resource because no user-facing
 * text in this app is free text.
 */
data class DrawerAction(
    val labelRes: Int,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
)
