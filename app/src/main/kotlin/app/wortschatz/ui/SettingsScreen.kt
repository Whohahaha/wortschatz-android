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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wortschatz.HomeState
import app.wortschatz.StudyViewModel
import app.wortschatz.core.CardType
import app.wortschatz.core.GestureScheme
import app.wortschatz.core.NewWordOrder
import kotlin.math.roundToInt

/** Everything that is set once and then left alone, so the home screen only has to show today. */
@Composable
fun SettingsScreen(
    home: HomeState?,
    scheme: GestureScheme,
    vm: StudyViewModel,
    onReminderToggle: (Boolean) -> Unit,
    onExport: () -> Unit,
    onRestore: () -> Unit,
) {
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = vm::goHome) { Text("‹ 返回") }
                Text("设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }

            Section("学习计划")
            NewWordsPerDay(home, vm::setNewWordsPerDay)
            NewWordOrderPicker(home.newWordOrder, vm::setNewWordOrder)
            Forecast(home.forecast)
            Text(
                buildString {
                    append("今天答题 ${home.reviewsToday} 次")
                    if (home.knownWords > 0) append(" · 已斩 ${home.knownWords} 词")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Section("卡片类型")
            Card(Modifier.fillMaxWidth()) {
                CardTypeSwitch("拼写卡", "看中文，打出德语单词", CardType.SPELLING, home, vm)
                CardTypeSwitch("填空卡", "例句挖空，按句子里的形式填写", CardType.CLOZE, home, vm)
            }

            Section("词书")
            SettingRow(home.wordBookTitle, "切换、导入 Anki / CSV 词表") {
                OutlinedButton(onClick = vm::openBooks) { Text("管理") }
            }

            Section("提醒")
            Card(Modifier.fillMaxWidth()) {
                SettingRow("每日提醒", "只在有词要学时提醒，并告诉你还剩多少。", card = false) {
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

            Section("备份与恢复")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("进度、设置和导入的词书每天自动备份到「下载/Wortschatz」，卸载 App 也不会删掉。")
                    Text(
                        if (home.lastBackup == 0L) "还没有自动备份过" else "上次自动备份：" + java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(home.lastBackup)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = vm::backupNow) { Text("立即备份") }
                        OutlinedButton(onClick = onExport) { Text("导出到…") }
                    }
                    OutlinedButton(onClick = onRestore) { Text("从备份恢复（替换当前进度）") }
                }
            }

            Section("手势")
            SettingRow("当前：${scheme.name}", scheme.description) {
                OutlinedButton(onClick = vm::openGestureSettings) { Text("修改") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CardTypeSwitch(title: String, subtitle: String, type: CardType, home: HomeState, vm: StudyViewModel) {
    SettingRow(title, subtitle, card = false) {
        Switch(checked = type !in home.disabledCardTypes, onCheckedChange = { vm.setCardTypeEnabled(type, it) })
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        Modifier.padding(top = 12.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SettingRow(title: String, subtitle: String, card: Boolean = true, trailing: @Composable () -> Unit) {
    val row = @Composable {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            trailing()
        }
    }
    if (card) Card(Modifier.fillMaxWidth()) { row() } else row()
}

@Composable
private fun NewWordOrderPicker(selected: NewWordOrder, onSelect: (NewWordOrder) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("新词顺序")
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NewWordOrder.entries.forEach { order ->
                    FilterChip(selected = order == selected, onClick = { onSelect(order) }, label = { Text(order.label) })
                }
            }
            Text(
                "只影响还没学的新词，已学的词照常按记忆曲线复习。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

