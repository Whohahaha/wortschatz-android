package app.wortschatz.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wortschatz.NumberDrillState
import app.wortschatz.NumberRange
import app.wortschatz.StudyViewModel

/**
 * Listening drill for numbers, which German reads units before tens (einundzwanzig = 21):
 * a number is read out, the thumb types the digits on a keypad in the bottom half.
 */
@Composable
fun NumbersScreen(n: NumberDrillState, vm: StudyViewModel) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = vm::goHome) { Text("‹ 返回") }
                Text("听数字", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (n.total > 0) Text("${n.correct} / ${n.total}")
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberRange.entries.forEach { r ->
                    FilterChip(selected = r == n.range, onClick = { vm.setNumberRange(r) }, label = { Text(r.label) })
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (n.combo >= 2) "🔥 连对 ${n.combo}" else " ",
                        color = CountColors.Learning,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        n.input.ifEmpty { "?" },
                        fontSize = 56.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (n.right) {
                            true -> CountColors.Review
                            false -> CountColors.Learning
                            null -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                    when (n.right) {
                        null -> Text("听到的是几？", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        true -> Text(n.spoken, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        false -> {
                            Text("✗ 是 ${n.target}", color = CountColors.Learning, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Text(n.spoken, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onClick = vm::replayNumber) { Text("🔊 再听一遍") }
                }
            }

            if (n.right == false) {
                Button(onClick = vm::nextNumberAndSpeak, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                    Text("下一个", fontSize = 18.sp)
                }
            } else {
                Keypad(onKey = vm::numberKey, onSubmit = vm::submitNumber)
            }
        }
    }
}

@Composable
private fun Keypad(onKey: (Char) -> Unit, onSubmit: () -> Unit) {
    val rows = listOf("123", "456", "789", "⌫0✓")
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.45f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    val submit = key == '✓'
                    val modifier = Modifier.weight(1f).fillMaxHeight()
                    if (submit) {
                        Button(onClick = onSubmit, modifier = modifier, shape = RoundedCornerShape(16.dp)) { Text("✓", fontSize = 24.sp) }
                    } else {
                        FilledTonalButton(onClick = { onKey(key) }, modifier = modifier, shape = RoundedCornerShape(16.dp)) {
                            Text(key.toString(), fontSize = 24.sp)
                        }
                    }
                }
            }
        }
    }
}
