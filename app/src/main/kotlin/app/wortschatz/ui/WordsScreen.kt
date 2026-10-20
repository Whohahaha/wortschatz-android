package app.wortschatz.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.wortschatz.StudyViewModel
import app.wortschatz.WordFilter
import app.wortschatz.WordsState
import app.wortschatz.core.WordProgress
import app.wortschatz.core.WordStatus

/** Every word of the book, searchable and filtered by progress; known words can be brought back here. */
@Composable
fun WordsScreen(words: WordsState, vm: StudyViewModel) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = vm::goHome) { Text("‹ 返回") }
                Text("词表", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            OutlinedTextField(
                value = words.query,
                onValueChange = vm::setWordQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("搜索德语或中文") },
            )
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WordFilter.entries.forEach { f ->
                    FilterChip(
                        selected = f == words.filter,
                        onClick = { vm.setWordFilter(f) },
                        label = { Text("${f.label} ${words.counts[f] ?: 0}") },
                    )
                }
            }
            if (words.filter == WordFilter.LEECH) {
                Text(
                    "同一张卡忘了 ${WordProgress.LEECH_LAPSES} 次以上的词。多看例句、跟读，或者先斩掉，过一阵再恢复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (words.rows.isEmpty()) {
                Text("没有符合的词", Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(words.rows, key = { it.entry.id }) { row ->
                    WordRow(row, onSpeak = { vm.speakText(row.entry.spoken) }, onRestore = { vm.restoreWord(row.entry.id) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun WordRow(row: WordProgress, onSpeak: () -> Unit, onRestore: () -> Unit) {
    val e = row.entry
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    e.gender?.let { g -> withStyle(SpanStyle(color = genderColor(g), fontWeight = FontWeight.Bold)) { append(g.article + " ") } }
                    append(e.display.removePrefix(e.gender?.article?.plus(" ") ?: ""))
                },
                fontWeight = FontWeight.Medium,
            )
            Text(e.meaning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (row.lapses > 0) {
                Text(
                    "${row.hardestType?.label ?: ""}忘了 ${row.lapses} 次",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (row.isLeech) CountColors.Learning else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            row.status.label,
            style = MaterialTheme.typography.labelMedium,
            color = when (row.status) {
                WordStatus.MASTERED -> CountColors.Review
                WordStatus.LEARNING -> CountColors.Learning
                WordStatus.NEW -> CountColors.New
                WordStatus.KNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.padding(4.dp))
        if (row.status == WordStatus.KNOWN) {
            OutlinedButton(onClick = onRestore) { Text("恢复") }
        } else {
            TextButton(onClick = onSpeak) { Text("🔊") }
        }
    }
}
