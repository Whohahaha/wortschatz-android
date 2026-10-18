package app.wortschatz.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.wortschatz.StudyViewModel
import app.wortschatz.core.Gesture
import app.wortschatz.core.GestureAction
import app.wortschatz.core.GestureScheme
import app.wortschatz.core.GestureSchemes

@Composable
fun GestureSettingsScreen(scheme: GestureScheme, vm: StudyViewModel) {
    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = vm::goHome) { Text("‹ 返回") }
                Text("手势设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }

            Text("预设", style = MaterialTheme.typography.titleMedium)
            GestureSchemes.presets.forEach { preset ->
                SchemeOption(preset.name, preset.description, selected = scheme.id == preset.id) {
                    vm.selectGestureScheme(preset.id)
                }
            }
            SchemeOption(
                "自定义",
                "在下面改任意一个手势，就会切换到自定义，并以当前方案为起点。",
                selected = scheme.id == GestureSchemes.CUSTOM_ID,
            ) { vm.selectGestureScheme(GestureSchemes.CUSTOM_ID) }

            Text("当前方案：${scheme.name}", style = MaterialTheme.typography.titleMedium)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    Gesture.entries.forEach { gesture ->
                        GestureRow(gesture, scheme.actionFor(gesture)) { vm.setGestureAction(gesture, it) }
                    }
                }
            }
            Text(
                "冠词卡由 App 判对错，所以评分类手势在冠词卡上不起作用；冠词卡答完之前也不会发音。音量键只在学习页生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SchemeOption(name: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Column(Modifier.padding(start = 4.dp)) {
                Text(name, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun GestureRow(gesture: Gesture, action: GestureAction, onChange: (GestureAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(gesture.label, Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }) { Text(action.label) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                GestureAction.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            open = false
                            if (option != action) onChange(option)
                        },
                    )
                }
            }
        }
    }
}
