package io.github.kdroidfilter.nucleus.window

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.debugInspectorInfo
import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.window.styling.LocalTitleBarStyle
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle

@Suppress("FunctionNaming")
@Composable
fun DecoratedWindowScope.TitleBar(
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    content: @Composable TitleBarScope.(DecoratedWindowState) -> Unit = {},
) {
    when (Platform.Current) {
        Platform.Linux -> LinuxTitleBar(modifier, gradientStartColor, style, content)
        Platform.Windows -> WindowsTitleBar(modifier, gradientStartColor, style, content)
        Platform.MacOS -> MacOSTitleBar(modifier, gradientStartColor, style, content)
        Platform.Unknown ->
            error("TitleBar is not supported on this platform(${System.getProperty("os.name")})")
    }
}

fun Modifier.newFullscreenControls(newControls: Boolean = true): Modifier =
    this then
        NewFullscreenControlsElement(
            newControls,
            debugInspectorInfo {
                name = "newFullscreenControls"
                value = newControls
            },
        )

internal class NewFullscreenControlsElement(
    val newControls: Boolean,
    val inspectorInfo: InspectorInfo.() -> Unit,
) : ModifierNodeElement<NewFullscreenControlsNode>() {
    override fun create(): NewFullscreenControlsNode = NewFullscreenControlsNode(newControls)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val otherModifier = other as? NewFullscreenControlsElement ?: return false
        return newControls == otherModifier.newControls
    }

    override fun hashCode(): Int = newControls.hashCode()

    override fun InspectorInfo.inspectableProperties() {
        inspectorInfo()
    }

    override fun update(node: NewFullscreenControlsNode) {
        node.newControls = newControls
    }
}

internal class NewFullscreenControlsNode(
    var newControls: Boolean,
) : Modifier.Node()

internal fun Modifier.hasNewFullscreenControls(): Boolean =
    foldOut(false) { e, r ->
        if (e is NewFullscreenControlsElement) e.newControls else r
    }
