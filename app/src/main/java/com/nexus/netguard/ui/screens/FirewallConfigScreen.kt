package com.nexus.netguard.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.netguard.ui.theme.NexusColors
import com.nexus.netguard.viewmodel.UiState

@Composable
fun FirewallConfigScreen(
    state: UiState,
    onToggle: (String) -> Unit,
    onBlockHogs: () -> Unit,
    onClear: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(state.apps, query) {
        state.apps.filter { it.label.contains(query, true) || it.pkg.contains(query, true) }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Firewall", fontSize = 24.sp, fontWeight = FontWeight.Black, color = NexusColors.Neon, modifier = Modifier.padding(top = 16.dp))
        Text(
            "Switched-on apps lose internet while the firewall is active. PUBG Mobile is never blocked.",
            fontSize = 12.sp, color = NexusColors.TextDim, modifier = Modifier.padding(bottom = 10.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search apps") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onBlockHogs) { Text("Block social & sync hogs") }
            OutlinedButton(onClick = onClear) { Text("Clear") }
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(shown, key = { it.pkg }) { app ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(app.label, color = NexusColors.Text, fontWeight = FontWeight.SemiBold)
                        Text(app.pkg, fontSize = 11.sp, color = NexusColors.TextDim)
                    }
                    Switch(
                        checked = app.pkg in state.blocked,
                        onCheckedChange = { onToggle(app.pkg) },
                        colors = SwitchDefaults.colors(checkedTrackColor = NexusColors.Danger),
                    )
                }
            }
        }
    }
}
