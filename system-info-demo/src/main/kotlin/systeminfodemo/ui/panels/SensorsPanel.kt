@file:Suppress("MagicNumber")

package systeminfodemo.ui.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.ui.component.Text
import systeminfodemo.ui.BarChart
import systeminfodemo.ui.SectionCard
import systeminfodemo.viewmodel.SystemInfoState

@Composable
fun SensorsPanel(state: SystemInfoState) {
    val sensors = state.components.filter { it.temperature != null }

    if (sensors.isNotEmpty()) {
        val maxTemp = sensors.mapNotNull { it.critical ?: it.max ?: it.temperature }.maxOrNull() ?: 100f
        SectionCard("Temperature Overview") {
            BarChart(
                xData = sensors.indices.map { it.toFloat() },
                yData = sensors.map { it.temperature ?: 0f },
                barColor = Color(0xFFFF7043),
                maxY = maxTemp.coerceAtLeast(50f),
            )
        }
    }

    SectionCard("All Sensors") {
        // Header
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sensor", fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.4f))
            Text("Temp", fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.2f))
            Text("Max", fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.2f))
            Text("Critical", fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.2f))
        }

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.components.forEach { comp ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(comp.label, modifier = Modifier.weight(0.4f))
                    Text(
                        comp.temperature?.let { "%.1f\u00B0C".format(it) } ?: "N/A",
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(0.2f),
                    )
                    Text(
                        comp.max?.let { "%.1f\u00B0C".format(it) } ?: "N/A",
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(0.2f),
                    )
                    Text(
                        comp.critical?.let { "%.1f\u00B0C".format(it) } ?: "N/A",
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(0.2f),
                    )
                }
            }
        }
    }

    if (state.components.isEmpty()) {
        Text("No temperature sensors detected")
    }
}
