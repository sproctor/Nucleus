package io.github.kdroidfilter.nucleus.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import com.jetbrains.JBR
import io.github.kdroidfilter.nucleus.core.runtime.Platform

@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun DecoratedWindow(
    onCloseRequest: () -> Unit,
    state: WindowState = rememberWindowState(),
    visible: Boolean = true,
    title: String = "",
    icon: Painter? = null,
    resizable: Boolean = true,
    enabled: Boolean = true,
    focusable: Boolean = true,
    alwaysOnTop: Boolean = false,
    minimumSize: DpSize? = null,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable DecoratedWindowScope.() -> Unit,
) {
    remember {
        check(JBR.isAvailable()) {
            "DecoratedWindow requires JetBrains Runtime (JBR). " +
                "Please run your application on JBR."
        }
    }

    // Inflate state.size to at least minimumSize BEFORE Compose centers the
    // window; applying window.minimumSize afterwards would re-anchor the
    // frame at its bottom-left corner and visibly shift the window.
    remember(state, minimumSize) {
        if (minimumSize != null) {
            val current = state.size
            if (current.width < minimumSize.width || current.height < minimumSize.height) {
                state.size =
                    DpSize(
                        maxOf(current.width, minimumSize.width),
                        maxOf(current.height, minimumSize.height),
                    )
            }
        }
    }

    val undecorated = Platform.Linux == Platform.Current

    Window(
        onCloseRequest,
        state,
        visible,
        title,
        icon,
        undecorated,
        transparent = false,
        resizable,
        enabled,
        focusable,
        alwaysOnTop,
        onPreviewKeyEvent,
        onKeyEvent,
    ) {
        if (minimumSize != null) {
            LaunchedEffect(window, minimumSize) {
                // Yield so Compose Desktop's internal `update` block commits
                // state.size/position first — otherwise setMinimumSize runs on
                // the unsized AWT frame and AWT re-anchors it at (0, 0) when
                // it later grows to state.size.
                kotlinx.coroutines.yield()
                // AWT window bounds are in logical pixels (= Dp). Do NOT convert
                // via Density.roundToPx() — that applies the screen scale factor
                // and would double the size on Retina/HiDPI displays.
                window.minimumSize =
                    java.awt.Dimension(minimumSize.width.value.toInt(), minimumSize.height.value.toInt())
            }
        }

        DecoratedWindowBody(
            title = title,
            icon = icon,
            undecorated = undecorated,
            onCloseRequest = onCloseRequest,
            content = content,
        )
    }
}
