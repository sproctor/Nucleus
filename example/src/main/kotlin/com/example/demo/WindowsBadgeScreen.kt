package com.example.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.nucleus.launcher.windows.BadgeGlyph
import io.github.kdroidfilter.nucleus.launcher.windows.WindowsBadgeManager

private const val EVENT_LOG_MAX = 20

@OptIn(ExperimentalLayoutApi::class)
@Suppress("FunctionNaming", "LongMethod")
@Composable
fun WindowsBadgeScreen() {
    val events = remember { mutableStateListOf<String>() }
    var statusText by remember { mutableStateOf("") }
    var initialized by remember { mutableStateOf(false) }
    var badgeCount by remember { mutableStateOf("3") }
    var selectedGlyph by remember { mutableStateOf(BadgeGlyph.ALERT) }

    DisposableEffect(Unit) {
        if (WindowsBadgeManager.isAvailable) {
            initialized = WindowsBadgeManager.initialize()
            val err = WindowsBadgeManager.lastError
            statusText = if (initialized) "Initialized" else "Failed: $err"
            events.add(0, "init: ok=$initialized${if (err != null) " error=$err" else ""}")
        } else {
            statusText = "Native library not available"
            events.add(0, "Badge native library not available")
        }

        onDispose {
            WindowsBadgeManager.uninitialize()
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Windows Badge Notifications", style = MaterialTheme.typography.headlineSmall)
            Text("Status: $statusText", style = MaterialTheme.typography.bodyMedium)

            HorizontalDivider()

            // -- Numeric badge --
            Text("Numeric Badge", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    badgeCount,
                    { badgeCount = it },
                    label = { Text("Count") },
                    modifier = Modifier.width(120.dp),
                )
                Button(
                    onClick = {
                        val count = badgeCount.toIntOrNull() ?: 0
                        val ok = WindowsBadgeManager.setCount(count)
                        events.add(0, if (ok) "Badge set: $count" else "Badge FAILED: ${WindowsBadgeManager.lastError}")
                        if (events.size > EVENT_LOG_MAX) events.removeLast()
                    },
                    enabled = initialized,
                ) {
                    Text("Set Count")
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 1, 3, 5, 10, 42, 99, 100).forEach { n ->
                    OutlinedButton(
                        onClick = {
                            val ok = WindowsBadgeManager.setCount(n)
                            events.add(0, if (ok) "Badge set: $n" else "Badge set FAILED: $n")
                            if (events.size > EVENT_LOG_MAX) events.removeLast()
                        },
                        enabled = initialized,
                    ) {
                        Text("$n")
                    }
                }
            }

            HorizontalDivider()

            // -- Glyph badge --
            Text("Glyph Badge", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                BadgeGlyph.entries.filter { it != BadgeGlyph.NONE }.forEach { glyph ->
                    val isSelected = glyph == selectedGlyph
                    val colors =
                        if (isSelected) {
                            ButtonDefaults.buttonColors()
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        }
                    Button(
                        onClick = { selectedGlyph = glyph },
                        colors = colors,
                    ) {
                        Text(glyph.value, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = {
                    val ok = WindowsBadgeManager.setGlyph(selectedGlyph)
                    val msg =
                        if (ok) {
                            "Glyph set: ${selectedGlyph.value}"
                        } else {
                            "Glyph FAILED: ${WindowsBadgeManager.lastError}"
                        }
                    events.add(0, msg)
                    if (events.size > EVENT_LOG_MAX) events.removeLast()
                },
                enabled = initialized,
            ) {
                Text("Set Glyph (${selectedGlyph.value})")
            }

            HorizontalDivider()

            // -- Clear --
            OutlinedButton(
                onClick = {
                    val ok = WindowsBadgeManager.clear()
                    events.add(0, if (ok) "Badge cleared" else "Clear FAILED: ${WindowsBadgeManager.lastError}")
                    if (events.size > EVENT_LOG_MAX) events.removeLast()
                },
                enabled = initialized,
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                Text("Clear Badge")
            }

            HorizontalDivider()

            // -- Event log --
            Text("Event Log", style = MaterialTheme.typography.titleMedium)
            val cardColors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            Card(
                modifier = Modifier.fillMaxWidth().height(200.dp),
                colors = cardColors,
            ) {
                Column(
                    modifier = Modifier.padding(8.dp).verticalScroll(rememberScrollState()),
                ) {
                    if (events.isEmpty()) {
                        Text("No events yet", style = MaterialTheme.typography.bodySmall)
                    } else {
                        events.forEach { event ->
                            Text(
                                event,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}
