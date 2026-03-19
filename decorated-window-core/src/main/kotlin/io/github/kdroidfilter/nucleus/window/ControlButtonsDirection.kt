package io.github.kdroidfilter.nucleus.window

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import java.awt.ComponentOrientation

/**
 * Controls the placement direction of window control buttons (close, minimize, maximize)
 * independently of the title bar content direction.
 *
 * - [Auto]: follows `LocalLayoutDirection` from Compose (previous default behavior).
 * - [System]: follows the JVM platform locale (`Locale.getDefault()`).
 * - [SystemNative]: follows the native OS locale detected at JVM startup, ignoring any
 *   `Locale.setDefault()` override applied at runtime.
 * - [Ltr]: always place buttons as in a left-to-right layout (trailing = right side).
 * - [Rtl]: always place buttons as in a right-to-left layout (trailing = left side).
 */
enum class ControlButtonsDirection {
    Auto,
    System,
    SystemNative,
    Ltr,
    Rtl,
    ;

    @Composable
    fun resolve(): LayoutDirection =
        when (this) {
            Auto -> LocalLayoutDirection.current
            System ->
                if (ComponentOrientation.getOrientation(java.util.Locale.getDefault()).isLeftToRight) {
                    LayoutDirection.Ltr
                } else {
                    LayoutDirection.Rtl
                }

            SystemNative -> nativeSystemLayoutDirection()
            Ltr -> LayoutDirection.Ltr
            Rtl -> LayoutDirection.Rtl
        }
}

/**
 * Returns the layout direction from the native OS locale as detected by the JVM at startup.
 *
 * Unlike `Locale.getDefault()`, the system properties `user.language` / `user.country` /
 * `user.variant` are set once during JVM bootstrap from the OS settings and are **not**
 * affected by `Locale.setDefault()`. This ensures the direction always reflects the real
 * operating system configuration.
 */
internal fun nativeSystemLayoutDirection(): LayoutDirection {
    val language = System.getProperty("user.language") ?: return LayoutDirection.Ltr
    val country = System.getProperty("user.country").orEmpty()
    val variant = System.getProperty("user.variant").orEmpty()

    @Suppress("DEPRECATION")
    val nativeLocale = java.util.Locale(language, country, variant)
    return if (ComponentOrientation.getOrientation(nativeLocale).isLeftToRight) {
        LayoutDirection.Ltr
    } else {
        LayoutDirection.Rtl
    }
}
