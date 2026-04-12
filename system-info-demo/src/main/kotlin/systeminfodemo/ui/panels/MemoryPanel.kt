@file:Suppress("MagicNumber")

package systeminfodemo.ui.panels

import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.koalaplot.core.animation.StartAnimationUseCase
import io.github.koalaplot.core.pie.DefaultSlice
import io.github.koalaplot.core.pie.PieChart
import io.github.koalaplot.core.util.ExperimentalKoalaPlotApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.typography
import systeminfodemo.ui.InfoRow
import systeminfodemo.ui.LineChart
import systeminfodemo.ui.SectionCard
import systeminfodemo.ui.formatBytes
import systeminfodemo.viewmodel.SystemInfoState

@Composable
fun MemoryPanel(state: SystemInfoState) {
    val mem = state.memoryInfo

    SectionCard("Memory Usage History") {
        if (state.memoryHistory.size >= 2) {
            LineChart(data = state.memoryHistory, lineColor = Color(0xFF9C27B0))
        } else {
            Text("Collecting data...")
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            @OptIn(ExperimentalKoalaPlotApi::class)
            SectionCard("RAM Distribution") {
                if (mem != null && mem.totalMemory > 0) {
                    val used = mem.usedMemory.toFloat()
                    val available = mem.availableMemory.toFloat()
                    val values = listOf(used, available)
                    val colors = listOf(Color(0xFF2196F3), Color(0xFFE0E0E0))

                    PieChart(
                        values = values,
                        labelSpacing = 1.0f,
                        modifier = Modifier.fillMaxWidth().height(250.dp),
                        slice = { i -> DefaultSlice(color = colors[i]) },
                        label = {},
                        labelConnector = {},
                        holeSize = 0.65f,
                        holeContent = { padding ->
                            androidx.compose.foundation.layout.Box(
                                modifier = Modifier.fillMaxSize().padding(padding),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        "%.0f%%".format(used / mem.totalMemory * 100f),
                                        style = JewelTheme.typography.h1TextStyle,
                                    )
                                    Text("Used")
                                }
                            }
                        },
                        startAnimationUseCase =
                            StartAnimationUseCase(
                                StartAnimationUseCase.ExecutionType.None,
                                snap(),
                                snap(),
                            ),
                    )
                }
            }
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionCard("RAM Details") {
                InfoRow("Total", mem?.let { formatBytes(it.totalMemory) })
                InfoRow("Used", mem?.let { formatBytes(it.usedMemory) })
                InfoRow("Free", mem?.let { formatBytes(it.freeMemory) })
                InfoRow("Available", mem?.let { formatBytes(it.availableMemory) })
            }

            SectionCard("Swap") {
                InfoRow("Total", mem?.let { formatBytes(it.totalSwap) })
                InfoRow("Used", mem?.let { formatBytes(it.usedSwap) })
                InfoRow("Free", mem?.let { formatBytes(it.freeSwap) })
            }
        }
    }
}
