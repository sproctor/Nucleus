package io.github.kdroidfilter.nucleus.window.jewel

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import io.github.kdroidfilter.nucleus.window.DecoratedWindow
import io.github.kdroidfilter.nucleus.window.DecoratedWindowScope
import io.github.kdroidfilter.nucleus.window.NucleusDecoratedWindowTheme
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle
import org.jetbrains.jewel.foundation.theme.JewelTheme

@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun JewelDecoratedWindow(
    onCloseRequest: () -> Unit,
    state: WindowState = rememberWindowState(),
    visible: Boolean = true,
    title: String = "",
    icon: Painter? = null,
    resizable: Boolean = true,
    enabled: Boolean = true,
    focusable: Boolean = true,
    alwaysOnTop: Boolean = false,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    titleBarStyle: TitleBarStyle? = null,
    content: @Composable DecoratedWindowScope.() -> Unit,
) {
    val windowStyle = rememberJewelWindowStyle()
    val jewelTitleBarStyle = rememberJewelTitleBarStyle()

    NucleusDecoratedWindowTheme(
        isDark = JewelTheme.isDark,
        windowStyle = windowStyle,
        titleBarStyle = titleBarStyle ?: jewelTitleBarStyle,
    ) {
        DecoratedWindow(
            onCloseRequest = onCloseRequest,
            state = state,
            visible = visible,
            title = title,
            icon = icon,
            resizable = resizable,
            enabled = enabled,
            focusable = focusable,
            alwaysOnTop = alwaysOnTop,
            onPreviewKeyEvent = onPreviewKeyEvent,
            onKeyEvent = onKeyEvent,
            content = content,
        )
    }
}
