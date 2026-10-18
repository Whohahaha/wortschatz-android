package app.wortschatz.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wortschatz.StudyCard
import app.wortschatz.StudyViewModel
import app.wortschatz.UiState
import app.wortschatz.core.CardType
import app.wortschatz.core.Entry
import app.wortschatz.core.Gender
import app.wortschatz.core.PartOfSpeech
import app.wortschatz.core.fsrs.Rating
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun StudyScreen(state: UiState, vm: StudyViewModel) {
    val current = state.current ?: return
    val type = current.card.type
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = vm::goHome) { Text("‹ 返回") }
                Spacer(Modifier.weight(1f))
                Text("${state.counts.new}", color = CountColors.New, fontWeight = FontWeight.Bold)
                Text("  ${state.counts.learning}", color = CountColors.Learning, fontWeight = FontWeight.Bold)
                Text("  ${state.counts.review}", color = CountColors.Review, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = vm::undo, enabled = state.canUndo) { Text("↶ 撤销") }
            }

            SwipeCard(
                key = current.card.id + current.card.memory.reps,
                enabled = type != CardType.ARTICLE,
                onSwipe = vm::rate,
                onTap = vm::reveal,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            ) {
                CardFace(current, state.revealed, state.articleChoice, onSpeak = vm::speakCurrent)
            }

            when {
                type == CardType.ARTICLE -> ArticleButtons(state.articleChoice, current.entry.gender, vm::chooseArticle)
                !state.revealed -> Button(onClick = vm::reveal, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                    Text("显示答案", fontSize = 18.sp)
                }
                else -> RatingButtons(current, vm::rate)
            }
        }
    }
}

private enum class Swipe(val rating: Rating, val label: String, val color: Color) {
    LEFT(Rating.AGAIN, "不认识", Color(0xFFE5484D)),
    RIGHT(Rating.GOOD, "认识", Color(0xFF30A46C)),
    UP(Rating.EASY, "太简单", Color(0xFF3B82F6)),
}

/** Tap to flip; drag left / right / up past the threshold to answer. */
@Composable
private fun SwipeCard(
    key: Any,
    enabled: Boolean,
    onSwipe: (Rating) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val x = remember(key) { Animatable(0f) }
    val y = remember(key) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val threshold = with(density) { 110.dp.toPx() }
    val flyOut = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }

    fun direction(): Swipe? = when {
        x.value > threshold && abs(x.value) > abs(y.value) -> Swipe.RIGHT
        x.value < -threshold && abs(x.value) > abs(y.value) -> Swipe.LEFT
        y.value < -threshold -> Swipe.UP
        else -> null
    }

    Box(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationX = x.value
                translationY = y.value
                rotationZ = x.value / 40f
            }
            .pointerInput(key) { detectTapGestures(onTap = { onTap() }) }
            .pointerInput(key, enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragEnd = {
                        val dir = direction()
                        scope.launch {
                            if (dir == null) {
                                launch { x.animateTo(0f) }
                                y.animateTo(0f)
                            } else {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                when (dir) {
                                    Swipe.RIGHT -> x.animateTo(flyOut, tween(150))
                                    Swipe.LEFT -> x.animateTo(-flyOut, tween(150))
                                    Swipe.UP -> y.animateTo(-flyOut * 2, tween(150))
                                }
                                onSwipe(dir.rating)
                            }
                        }
                    },
                    onDragCancel = {
                        scope.launch {
                            launch { x.animateTo(0f) }
                            y.animateTo(0f)
                        }
                    },
                    onDrag = { change, drag ->
                        change.consume()
                        scope.launch {
                            x.snapTo(x.value + drag.x)
                            y.snapTo((y.value + drag.y).coerceAtMost(0f))
                        }
                    },
                )
            },
    ) {
        ElevatedCard(Modifier.fillMaxSize(), shape = RoundedCornerShape(24.dp)) { content() }
        val dir = direction()
        val progress = maxOf(abs(x.value), -y.value) / threshold
        if (progress > 0.2f) {
            val shown = dir ?: when {
                abs(x.value) >= -y.value -> if (x.value > 0) Swipe.RIGHT else Swipe.LEFT
                else -> Swipe.UP
            }
            Text(
                shown.label,
                color = shown.color,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).padding(24.dp).alpha(progress.coerceAtMost(1f)),
            )
        }
    }
}

@Composable
private fun CardFace(current: StudyCard, revealed: Boolean, articleChoice: Gender?, onSpeak: () -> Unit) {
    val entry = current.entry
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "${current.card.type.label} · ${entry.level} · ${entry.pos.label}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        when (current.card.type) {
            CardType.MEANING -> {
                Headword(entry)
                SpeakButton(onSpeak)
                if (revealed) {
                    Answer(entry.meaning)
                    Details(entry)
                }
            }
            CardType.ARTICLE -> {
                if (articleChoice == null) {
                    Text("___ ${entry.lemma}", fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    Text(entry.meaning, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Headword(entry)
                    val correct = articleChoice == entry.gender
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (correct) "✓ 正确" else "✗ 你选了 ${articleChoice.article}",
                        color = if (correct) CountColors.Review else CountColors.Learning,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(entry.meaning, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            CardType.PLURAL -> {
                Headword(entry)
                Spacer(Modifier.height(16.dp))
                Text("复数是？", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (revealed) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = genderColor(Gender.DIE))) { append("die ") }
                            append(entry.plural.orEmpty())
                        },
                        fontSize = 36.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(entry.meaning, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            CardType.VERB_FORMS -> {
                Headword(entry)
                SpeakButton(onSpeak)
                Text(entry.meaning, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                val forms = entry.verbForms
                if (!revealed || forms == null) {
                    Text("说出：er/sie/es 现在时 · 过去式 · 完成时", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    FormRow("er/sie/es", forms.present3)
                    FormRow("过去式", forms.preterite)
                    FormRow("完成时", perfect(forms.auxiliary, forms.participle))
                }
            }
        }
    }
}

/** The word as it should be learned: article coloured by gender, separable prefix marked. */
@Composable
private fun Headword(entry: Entry) {
    Text(
        buildAnnotatedString {
            entry.gender?.let { g ->
                withStyle(SpanStyle(color = genderColor(g), fontWeight = FontWeight.Bold)) { append(g.article) }
                append(" ")
            }
            val prefix = entry.separablePrefix
            if (prefix != null) {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(prefix) }
                append("·")
                append(entry.lemma.removePrefix(prefix))
            } else {
                append(entry.lemma)
            }
        },
        fontSize = 40.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun SpeakButton(onSpeak: () -> Unit) {
    TextButton(onClick = onSpeak) { Text("🔊 发音") }
}

@Composable
private fun Answer(text: String) {
    Spacer(Modifier.height(12.dp))
    Text(text, fontSize = 26.sp, textAlign = TextAlign.Center)
}

@Composable
private fun Details(entry: Entry) {
    Spacer(Modifier.height(16.dp))
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (entry.pos == PartOfSpeech.NOUN) {
        Text(entry.plural?.let { "复数：die $it" } ?: "无复数", color = muted)
    }
    entry.verbForms?.let {
        Text("${it.present3} · ${it.preterite} · ${perfect(it.auxiliary, it.participle)}", color = muted)
    }
    if (entry.example.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text(entry.example, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
        Text(entry.exampleZh, textAlign = TextAlign.Center, color = muted)
    }
}

@Composable
private fun FormRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1.4f), fontSize = 20.sp, fontWeight = FontWeight.Medium)
    }
}

private fun perfect(auxiliary: String, participle: String) =
    "${if (auxiliary == "sein") "ist" else "hat"} $participle"

@Composable
private fun ArticleButtons(choice: Gender?, correct: Gender?, onChoose: (Gender) -> Unit) {
    Row(Modifier.fillMaxWidth().height(72.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Gender.entries.forEach { g ->
            val dimmed = choice != null && g != correct && g != choice
            Button(
                onClick = { onChoose(g) },
                modifier = Modifier.weight(1f).fillMaxSize().alpha(if (dimmed) 0.35f else 1f),
                colors = ButtonDefaults.buttonColors(containerColor = genderColor(g)),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(g.article, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RatingButtons(current: StudyCard, onRate: (Rating) -> Unit) {
    Row(Modifier.fillMaxWidth().height(72.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RateButton("不认识", current.intervals[Rating.AGAIN], CountColors.Learning) { onRate(Rating.AGAIN) }
        RateButton("模糊", current.intervals[Rating.HARD], Color(0xFFE09B2D)) { onRate(Rating.HARD) }
        RateButton("认识", current.intervals[Rating.GOOD], CountColors.Review) { onRate(Rating.GOOD) }
    }
}

@Composable
private fun RowScope.RateButton(label: String, interval: String?, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.weight(1f).fillMaxSize(),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            interval?.let { Text(it, fontSize = 12.sp) }
        }
    }
}
