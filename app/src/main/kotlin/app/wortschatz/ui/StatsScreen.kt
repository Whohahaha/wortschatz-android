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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wortschatz.StatsState
import app.wortschatz.StudyViewModel
import app.wortschatz.core.WordStatus
import kotlin.math.roundToInt

/** Study calendar, recall rate, how far the book has come, and milestones. */
@Composable
fun StatsScreen(state: StatsState, vm: StudyViewModel) {
    val stats = state.stats
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = vm::goHome) { Text("‹ 返回") }
                Text("统计", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }

            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Figure(stats.retention?.let { "${(it * 100).roundToInt()}%" } ?: "–", "近 30 天答对率")
                    Figure("${stats.daily.count { it > 0 }}", "近 17 周学习天数")
                    Figure("${stats.longestStreak}", "最长连续天数")
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("学习日历", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Calendar(stats.daily)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "每格一天，颜色越深答题越多；最右下是今天。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("掌握程度", style = MaterialTheme.typography.titleSmall)
                    val total = state.totalWords.coerceAtLeast(1)
                    listOf(
                        WordStatus.MASTERED to CountColors.Review,
                        WordStatus.LEARNING to CountColors.Learning,
                        WordStatus.KNOWN to Color.Gray,
                        WordStatus.NEW to CountColors.New,
                    ).forEach { (status, color) ->
                        val n = stats.byStatus[status] ?: 0
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(status.label, Modifier.width(56.dp))
                            Box(Modifier.weight(1f).height(10.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(5.dp))) {
                                Box(Modifier.fillMaxWidth(n.toFloat() / total).height(10.dp).background(color, RoundedCornerShape(5.dp)))
                            }
                            Text("$n", Modifier.width(48.dp).padding(start = 8.dp))
                        }
                    }
                    Text(
                        "已掌握：释义卡的记忆稳定在 3 周以上。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("里程碑", style = MaterialTheme.typography.titleSmall)
                    state.milestones.forEach { m ->
                        Text(
                            (if (m.reached) "🏅 " else "○ ") + m.title,
                            color = if (m.reached) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            }
            Text("一共答题 ${stats.totalAnswers} 次", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Figure(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Weeks as columns, days as rows, like a contribution calendar. */
@Composable
private fun Calendar(daily: IntArray) {
    val max = (daily.maxOrNull() ?: 0).coerceAtLeast(1)
    val weeks = (daily.size + 6) / 7
    val base = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (w in 0 until weeks) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                for (d in 0 until 7) {
                    val i = w * 7 + d
                    val n = daily.getOrNull(i)
                    Box(
                        Modifier.size(14.dp).background(
                            when {
                                n == null -> Color.Transparent
                                n == 0 -> empty
                                else -> base.copy(alpha = 0.25f + 0.75f * n / max)
                            },
                            RoundedCornerShape(3.dp),
                        )
                    )
                }
            }
        }
    }
}
