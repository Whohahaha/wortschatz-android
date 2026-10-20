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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wortschatz.DrillState
import app.wortschatz.StudyViewModel
import app.wortschatz.core.Gender

/**
 * Article speed drill: a noun appears, the thumb taps der / die / das in the bottom third.
 * Twenty words a round, with a streak counter; it is practice and leaves the schedule alone.
 */
@Composable
fun DrillScreen(drill: DrillState, vm: StudyViewModel) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = vm::goHome) { Text("‹ 返回") }
                Text("冠词快练", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (!drill.finished) Text("${drill.index + 1} / ${drill.words.size}")
            }
            LinearProgressIndicator(
                progress = { drill.index.toFloat() / drill.words.size },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            if (drill.finished) Summary(drill, vm) else Question(drill, vm)
        }
    }
}

@Composable
private fun Question(drill: DrillState, vm: StudyViewModel) {
    val word = drill.current ?: return
    Column(Modifier.fillMaxSize()) {
        Text(
            if (drill.combo >= 2) "🔥 连对 ${drill.combo}" else " ",
            Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            color = CountColors.Learning,
            fontWeight = FontWeight.Bold,
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val choice = drill.choice
                FitText(
                    buildAnnotatedString {
                        if (choice == null) append("___ ") else {
                            val gender = word.gender!!
                            withStyle(SpanStyle(color = genderColor(gender), fontWeight = FontWeight.Bold)) {
                                append(gender.article)
                            }
                            append(" ")
                        }
                        append(word.lemma)
                    },
                    Modifier.fillMaxWidth(),
                    maxSize = 44.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(word.meaning, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (choice != null) {
                    val right = choice == word.gender
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (right) "✓" else "✗ 不是 ${choice.article}",
                        color = if (right) CountColors.Review else CountColors.Learning,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        // The bottom third of the screen, split into the three article colours.
        Row(Modifier.fillMaxWidth().fillMaxHeight(0.33f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Gender.entries.forEach { g ->
                Button(
                    onClick = { vm.drillAnswer(g) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = genderColor(g), contentColor = Color.White),
                ) { Text(g.article, fontSize = 28.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun Summary(drill: DrillState, vm: StudyViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("${drill.correct} / ${drill.words.size}", fontSize = 48.sp, fontWeight = FontWeight.Bold)
        Text("最长连对 ${drill.bestCombo} 个", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (drill.mistakes.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("答错的词", style = MaterialTheme.typography.titleSmall)
                    drill.mistakes.forEach { e ->
                        Text(
                            buildAnnotatedString {
                                val gender = e.gender!!
                                withStyle(SpanStyle(color = genderColor(gender), fontWeight = FontWeight.Bold)) {
                                    append(gender.article)
                                }
                                append(" ${e.lemma}  ")
                                withStyle(SpanStyle(color = Color.Gray)) { append(e.meaning) }
                            }
                        )
                    }
                }
            }
        }
        Button(onClick = vm::startDrill, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("再来一轮", fontSize = 17.sp) }
        OutlinedButton(onClick = vm::goHome, modifier = Modifier.fillMaxWidth()) { Text("回到首页") }
        Text(
            "快练只是练习，不会改变复习计划。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
