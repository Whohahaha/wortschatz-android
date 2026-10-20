package app.wortschatz.ui

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
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
fun HomeScreen(home: HomeState?, vm: StudyViewModel, onRestore: () -> Unit) {
    Scaffold { padding ->
        if (home == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("德语单词", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = vm::openSettings) { Text("⚙ 设置") }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = vm::openBooks) { Text("📚 ${home.wordBookTitle}") }
            }
            if (home.offerRestore) {
                Spacer(Modifier.height(16.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("以前用过？可以从备份恢复进度", fontWeight = FontWeight.Medium)
                        Text(
                            "旧版本会自动备份到「下载/Wortschatz」文件夹。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onRestore) { Text("从备份恢复") }
                            TextButton(onClick = vm::dismissRestore) { Text("不用了") }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                CountTile("新词", home.counts.new, CountColors.New)
                CountTile("学习中", home.counts.learning, CountColors.Learning)
                CountTile("待复习", home.counts.review, CountColors.Review)
            }
            Spacer(Modifier.height(40.dp))
            Button(
                onClick = vm::startStudy,
                enabled = home.counts.total > 0,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(if (home.counts.total > 0) "开始学习" else "今天的任务完成了", fontSize = 18.sp)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                buildString {
                    if (home.streak > 0) append("🔥 连续 ${home.streak} 天 · ")
                    append("已学 ${home.learnedWords} / ${home.totalWords}")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (home.backlog > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "积压 ${home.backlog} 个复习，会在接下来几天慢慢消化",
                    style = MaterialTheme.typography.bodySmall,
                    color = CountColors.Learning,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.weight(1.3f))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = vm::startDrill, modifier = Modifier.weight(1f).height(52.dp)) { Text("冠词快练") }
                FilledTonalButton(onClick = vm::openNumbers, modifier = Modifier.weight(1f).height(52.dp)) { Text("听数字") }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = vm::openWords, modifier = Modifier.weight(1f).height(52.dp)) { Text("词表") }
                FilledTonalButton(onClick = vm::openStats, modifier = Modifier.weight(1f).height(52.dp)) { Text("统计") }
            }
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
