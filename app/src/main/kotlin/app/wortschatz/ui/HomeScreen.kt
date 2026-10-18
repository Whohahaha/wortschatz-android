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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wortschatz.HomeState
import app.wortschatz.StudyViewModel
import app.wortschatz.UiState

@Composable
fun HomeScreen(home: HomeState?, vm: StudyViewModel) {
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
            Text(home.wordBookTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)

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
                "已学 ${home.learnedWords} / ${home.totalWords} 词 · 今天答题 ${home.reviewsToday} 次",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Forecast(home.forecast)

            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("每天新词", Modifier.weight(1f))
                    OutlinedButton(onClick = { vm.setNewWordsPerDay(home.newWordsPerDay - 5) }) { Text("−") }
                    Text("${home.newWordsPerDay}", Modifier.padding(horizontal = 16.dp), fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = { vm.setNewWordsPerDay(home.newWordsPerDay + 5) }) { Text("+") }
                }
            }

            Text(
                "手势：点卡片看答案，右滑认识，左滑不认识，上滑太简单。冠词卡直接点 der / die / das。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
