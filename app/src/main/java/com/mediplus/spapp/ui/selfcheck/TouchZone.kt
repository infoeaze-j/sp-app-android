package com.mediplus.spapp.ui.selfcheck

/**
 * The touch test's tap targets, one for each place the app asks an operator to tap: the log out
 * button in the app bar's top-end corner, the sign-in fields and password toggle, the amount keypad
 * on the add-service step, and the full-width primary button along the bottom.
 */
enum class TouchZone {
    LOG_OUT,
    IDENTIFIER,
    PASSWORD,
    SHOW_PASSWORD,
    KEY_1,
    KEY_2,
    KEY_3,
    KEY_4,
    KEY_5,
    KEY_6,
    KEY_7,
    KEY_8,
    KEY_9,
    KEY_DECIMAL,
    KEY_0,
    KEY_BACKSPACE,
    PRIMARY_BUTTON,
}
