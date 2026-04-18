package com.example.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.nucleus.autolaunch.AutoLaunch
import io.github.kdroidfilter.nucleus.autolaunch.AutoLaunchResult
import io.github.kdroidfilter.nucleus.autolaunch.AutoLaunchState
import io.github.kdroidfilter.nucleus.core.runtime.Platform

@Composable
fun AutoLaunchScreen() {
    var state by remember { mutableStateOf(AutoLaunch.state()) }
    var lastResult by remember { mutableStateOf<AutoLaunchResult?>(null) }
    var diag by remember { mutableStateOf(AutoLaunch.diagnostic()) }

    fun refresh() {
        state = AutoLaunch.state()
        diag = AutoLaunch.diagnostic()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Auto-Launch", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text("Platform: ${Platform.Current}")
            Text("State: $state")
            lastResult?.let { Text("Last result: $it", style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(24.dp))

            val isOn = state == AutoLaunchState.ENABLED || state == AutoLaunchState.ENABLED_BY_POLICY
            val locked =
                state == AutoLaunchState.DISABLED_BY_USER ||
                    state == AutoLaunchState.DISABLED_BY_POLICY ||
                    state == AutoLaunchState.ENABLED_BY_POLICY ||
                    state == AutoLaunchState.UNSUPPORTED

            Switch(
                checked = isOn,
                enabled = !locked,
                onCheckedChange = { checked ->
                    lastResult = if (checked) AutoLaunch.enable() else AutoLaunch.disable()
                    refresh()
                },
            )

            Spacer(Modifier.height(16.dp))

            when (state) {
                AutoLaunchState.DISABLED_BY_USER -> {
                    Text(
                        "Disabled via system settings. Programmatic re-enable is blocked by the OS.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { AutoLaunch.openSystemSettings() }) {
                        Text("Open system settings")
                    }
                }
                AutoLaunchState.UNSUPPORTED -> {
                    Text(
                        "Auto-launch is not supported in this build. " +
                            "Sandboxed macOS (PKG / Mac App Store) and Linux are not covered yet.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                else -> {}
            }

            Spacer(Modifier.height(16.dp))
            Button(onClick = { refresh() }) { Text("Refresh") }

            Spacer(Modifier.height(24.dp))
            Text("Diagnostic:", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                text = diag.ifBlank { "(no diagnostic info yet)" },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
