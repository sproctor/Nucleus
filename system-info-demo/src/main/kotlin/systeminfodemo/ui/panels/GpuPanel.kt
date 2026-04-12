@file:Suppress("MagicNumber")

package systeminfodemo.ui.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import systeminfodemo.ui.InfoRow
import systeminfodemo.ui.ProgressBar
import systeminfodemo.ui.SectionCard
import systeminfodemo.ui.formatBytes
import systeminfodemo.viewmodel.SystemInfoState

private fun vendorName(vendorId: Long): String =
    when (vendorId.toInt()) {
        0x10DE -> "NVIDIA"
        0x1002 -> "AMD"
        0x8086 -> "Intel"
        0x1414 -> "Microsoft"
        else -> "0x%04X".format(vendorId)
    }

@Composable
fun GpuPanel(state: SystemInfoState) {
    if (state.gpus.isEmpty()) {
        SectionCard("GPU") {
            InfoRow("Status", "No GPU detected")
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        state.gpus.forEachIndexed { index, gpu ->
            SectionCard(if (state.gpus.size > 1) "GPU $index — ${gpu.name}" else gpu.name) {
                InfoRow("Vendor", vendorName(gpu.vendorId))
                InfoRow("Device ID", "0x%04X".format(gpu.deviceId))

                if (gpu.dedicatedVideoMemory > 0) {
                    InfoRow("Dedicated VRAM", formatBytes(gpu.dedicatedVideoMemory))
                }
                if (gpu.dedicatedSystemMemory > 0) {
                    InfoRow("Dedicated System Memory", formatBytes(gpu.dedicatedSystemMemory))
                }
                if (gpu.sharedSystemMemory > 0) {
                    InfoRow("Shared System Memory", formatBytes(gpu.sharedSystemMemory))
                }

                val totalMemory = gpu.dedicatedVideoMemory + gpu.dedicatedSystemMemory + gpu.sharedSystemMemory
                if (totalMemory > 0) {
                    InfoRow("Total Memory", formatBytes(totalMemory))
                    val dedicatedFraction = gpu.dedicatedVideoMemory.toFloat() / totalMemory
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ProgressBar(
                            progress = dedicatedFraction,
                            color = Color(0xFF9C6ADE),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                gpu.driverVersion?.let { InfoRow("Driver Version", it) }
            }
        }
    }
}
