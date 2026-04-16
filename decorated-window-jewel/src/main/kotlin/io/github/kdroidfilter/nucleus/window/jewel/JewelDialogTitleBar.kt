package io.github.kdroidfilter.nucleus.window.jewel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.kdroidfilter.nucleus.window.ControlButtonsDirection
import io.github.kdroidfilter.nucleus.window.DecoratedDialogScope
import io.github.kdroidfilter.nucleus.window.DecoratedDialogState
import io.github.kdroidfilter.nucleus.window.DialogTitleBar
import io.github.kdroidfilter.nucleus.window.TitleBarScope
import io.github.kdroidfilter.nucleus.window.styling.LocalTitleBarStyle
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle
import org.jetbrains.jewel.foundation.theme.LocalContentColor

@Suppress("FunctionNaming")
@Composable
fun DecoratedDialogScope.JewelDialogTitleBar(
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    controlButtonsDirection: ControlButtonsDirection = ControlButtonsDirection.Auto,
    content: @Composable TitleBarScope.(DecoratedDialogState) -> Unit = {},
) {
    DialogTitleBar(
        modifier = modifier,
        gradientStartColor = gradientStartColor,
        style = style,
        controlButtonsDirection = controlButtonsDirection,
    ) { state ->
        CompositionLocalProvider(LocalContentColor provides style.colors.content) {
            content(state)
        }
    }
}
