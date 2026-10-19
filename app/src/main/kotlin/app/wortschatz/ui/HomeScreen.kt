package app.wortschatz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wortschatz.HomeState
import app.wortschatz.StudyViewModel
import app.wortschatz.UiState
import app.wortschatz.core.GestureScheme

@Composable
fun HomeScreen(home: HomeState?, scheme: GestureScheme, vm: StudyViewModel, onReminderToggle: (Boolean) -> Unit) {
    Scaffold { padding ->
        if (home == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("德语单词", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(home.wordBookTitle, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = vm::openBooks) { Text("词书") }
            }

            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    CountTile("新词", home.counts.new, CountColors.New)
                    CountTile("学习中", home.counts.learning, CountColors.Learning)
                    CountTile("待复习", home.counts.review, CountColors.Review)
                }
            }

            Button(
                onClick = vm::startStudy,
                enabled = home.counts.total > 0,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(if (home.counts.total > 0) "开始学习" else "今天的任务完成了", fontSize = 18.sp)
            }

            Text(
                buildString {
                    if (home.streak > 0) append("🔥 连续 ${home.streak} 天 · ")
                    append("已学 ${home.learnedWords} / ${home.totalWords} 词")
                    if (home.knownWords > 0) append("（斩 ${home.knownWords}）")
                    append(" · 今天答题 ${home.reviewsToday} 次")
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (home.backlog > 0) {
                Text(
                    "积压了 ${home.backlog} 个复习。每天先复习最容易忘的，积压会在接下来几天慢慢消化，不用一次补完。",
                    style = MaterialTheme.typography.bodySmall,
                    color = CountColors.Learning,
                )
            }

            FilledTonalButton(onClick = vm::startDrill, modifier = Modifier.fillMaxWidth()) {
                Text("冠词快练：der / die / das 三色速答")
            }

            Forecast(home.forecast)

            NewWordsPerDay(home, vm::setNewWordsPerDay)

            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("每日提醒")
                        Text(
                            "只在有词要学时提醒，并告诉你还剩多少。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = home.reminderEnabled, onCheckedChange = onReminderToggle)
                }
                if (home.reminderEnabled) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("提醒时间", Modifier.weight(1f))
                        OutlinedButton(onClick = { vm.changeReminderHour(-1) }) { Text("−") }
                        Text("%02d:00".format(home.reminderHour), Modifier.padding(horizontal = 12.dp), fontWeight = FontWeight.Bold)
                        OutlinedButton(onClick = { vm.changeReminderHour(1) }) { Text("+") }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("手势：${scheme.name}")
                        Text(
                            scheme.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = vm::openGestureSettings) { Text("设置") }
                }
            }
        }
    }
}

/** Slider in steps of 5, with how long the rest of the book takes at that pace. */
@Composable
private fun NewWordsPerDay(home: HomeState, onChange: (Int) -> Unit) {
    var value by remember(home.newWordsPerDay) { mutableFloatStateOf(home.newWordsPerDay.toFloat()) }
    val perDay = value.roundToInt()
    val remaining = (home.totalWords - home.learnedWords).coerceAtLeast(0)
    val days = (remaining + perDay - 1) / perDay
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("每天新词 ", style = MaterialTheme.typography.bodyLarge)
                Text("$perDay", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(
                    if (remaining == 0) "这本词书已学完" else "约 $days 天学完剩下的 $remaining 词",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = value,
                onValueChange = { value = (it / 5).roundToInt() * 5f },
                onValueChangeFinished = { onChange(perDay) },
                valueRange = 5f..100f,
                steps = 18,
            )
        }
    }
}

@Composable
private fun CountTile(label: String, value: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Forecast(days: List<Int>) {
    val max = (days.maxOrNull() ?: 0).coerceAtLeast(1)
    val labels = listOf("今天", "明天") + (2 until days.size).map { "+$it" }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("未来 7 天复习量", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().height(110.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                days.forEachIndexed { i, n ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$n", style = MaterialTheme.typography.labelSmall)
                        Box(
                            Modifier
                                .width(22.dp)
                                .height((4 + 60 * n / max).dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                        )
                        Text(labels[i], style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
fun DoneScreen(state: UiState, vm: StudyViewModel) {
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("🎉", fontSize = 56.sp)
            Spacer(Modifier.height(12.dp))
            Text("今天的任务完成了", style = MaterialTheme.typography.headlineSmall)
            state.home?.let {
                Spacer(Modifier.height(8.dp))
                Text("今天答题 ${it.reviewsToday} 次，已学 ${it.learnedWords} 词", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(32.dp))
            Button(onClick = vm::goHome, modifier = Modifier.fillMaxWidth()) { Text("返回首页") }
            if (state.canUndo) {
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = vm::undo, modifier = Modifier.fillMaxWidth()) { Text("撤销上一题") }
            }
        }
    }
}
