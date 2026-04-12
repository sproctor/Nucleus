package systeminfodemo

import androidx.compose.ui.window.application
import io.github.kdroidfilter.nucleus.window.jewel.JewelDecoratedWindow
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import systeminfodemo.ui.AppContent
import systeminfodemo.ui.AppTitleBar
import systeminfodemo.ui.buildIslandsTheme

fun main() {
    io.github.kdroidfilter.nucleus.graalvm.GraalVmInitializer
        .initialize()

    application {
        val (theme, styling) = buildIslandsTheme()

        IntUiTheme(theme = theme, styling = styling) {
            JewelDecoratedWindow(
                onCloseRequest = { exitApplication() },
                title = "Nucleus System Info",
                content = {
                    AppTitleBar()
                    AppContent()
                },
            )
        }
    }
}
