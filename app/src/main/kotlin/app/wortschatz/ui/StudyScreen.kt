package app.wortschatz.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wortschatz.StudyCard
import app.wortschatz.StudyViewModel
import app.wortschatz.UiState
import app.wortschatz.core.CardType
import app.wortschatz.core.Command
import app.wortschatz.core.Gesture
import app.wortschatz.core.GestureAction
import app.wortschatz.core.GestureScheme
import app.wortschatz.core.resolve
import app.wortschatz.core.Entry
import app.wortschatz.core.Gender
import app.wortschatz.core.PartOfSpeech
import app.wortschatz.core.fsrs.Rating
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

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

            val answered = if (type == CardType.ARTICLE) state.articleChoice != null else state.revealed
            GestureCard(
                key = current.card.id + current.card.memory.reps,
                scheme = state.scheme,
                sensitivity = state.swipeSensitivity,
                commandFor = { g -> resolve(state.scheme.actionFor(g), type, answered) },
                onGesture = vm::onGesture,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            ) {
                CardFace(current, state.revealed, state.articleChoice, onSpeak = vm::speakCurrent, onSpeakText = vm::speakText)
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

private val swipeGestures = setOf(Gesture.SWIPE_LEFT, Gesture.SWIPE_RIGHT, Gesture.SWIPE_UP, Gesture.SWIPE_DOWN)

private fun Command.color(): Color = when (this) {
    is Command.Rate -> when (rating) {
        Rating.AGAIN -> Color(0xFFE5484D)
        Rating.HARD -> Color(0xFFE09B2D)
        Rating.GOOD -> Color(0xFF30A46C)
        Rating.EASY -> Color(0xFF3B82F6)
    }
    else -> Color(0xFF8B8D98)
}

private fun Command.label(): String = when (this) {
    is Command.Rate -> when (rating) {
        Rating.AGAIN -> "不认识"
        Rating.HARD -> "模糊"
        Rating.GOOD -> "认识"
        Rating.EASY -> "太简单"
    }
    Command.Reveal -> "显示答案"
    Command.Undo -> "撤销"
    Command.Speak -> "发音"
    Command.Nothing -> ""
}

/**
 * The card surface. Taps are split into left / centre / right thirds; double tap, long press
 * and the four swipe directions are only listened for when the scheme gives them an action,
 * so an unused double tap never delays a single tap.
 */
@Composable
private fun GestureCard(
    key: Any,
    scheme: GestureScheme,
    sensitivity: SwipeSensitivity,
    commandFor: (Gesture) -> Command,
    onGesture: (Gesture) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val x = remember(key) { Animatable(0f) }
    val y = remember(key) { Animatable(0f) }
    // The pointer handlers outlive recompositions, so they must read the latest callbacks.
    val latestCommandFor by rememberUpdatedState(commandFor)
    val latestOnGesture by rememberUpdatedState(onGesture)
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val threshold = with(density) { sensitivity.distance.toPx() }
    // A quick flick commits after a short distance, the way Tinder-style cards feel.
    val flickDistance = with(density) { 20.dp.toPx() }
    val flickSpeed = with(density) { 500.dp.toPx() }
    val flyOut = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val mapped = Gesture.entries.filter { scheme.actionFor(it) != GestureAction.NONE }.toSet()
    val swipeEnabled = mapped.any { it in swipeGestures }

    fun gestureFor(horizontal: Boolean, d: Float): Gesture =
        if (horizontal) (if (d > 0) Gesture.SWIPE_RIGHT else Gesture.SWIPE_LEFT)
        else (if (d < 0) Gesture.SWIPE_UP else Gesture.SWIPE_DOWN)

    fun settle() {
        scope.launch {
            launch { x.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 600f)) }
            y.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 600f))
        }
    }

    val tapTargets = remember { TapTargets() }

    Box(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { tapTargets.origin = it.positionInRoot() }
            .graphicsLayer {
                translationX = x.value
                translationY = y.value
                rotationZ = x.value / 40f
            }
            .pointerInput(key, scheme) {
                detectTapGestures(
                    onTap = { offset ->
                        tapTargets.find(tapTargets.origin + offset)?.let { return@detectTapGestures it() }
                        val third = size.width / 3f
                        latestOnGesture(
                            when {
                                offset.x < third -> Gesture.TAP_LEFT
                                offset.x > 2 * third -> Gesture.TAP_RIGHT
                                else -> Gesture.TAP_CENTER
                            }
                        )
                    },
                    onDoubleTap = if (Gesture.DOUBLE_TAP in mapped) ({ latestOnGesture(Gesture.DOUBLE_TAP) }) else null,
                    onLongPress = if (Gesture.LONG_PRESS in mapped) ({
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        latestOnGesture(Gesture.LONG_PRESS)
                    }) else null,
                )
            }
            .pointerInput(key, scheme, sensitivity) {
                if (!swipeEnabled) return@pointerInput
                val tracker = VelocityTracker()
                // Direction is judged from this drag's own movement, never from where the card
                // still is after the last swipe sprang back, and it is re-judged on every move:
                // a drag that starts slightly off still goes where the finger ends up.
                var totalX = 0f
                var totalY = 0f
                var horizontal = true
                var armed = false
                detectDragGestures(
                    onDragStart = {
                        tracker.resetTracking()
                        totalX = 0f
                        totalY = 0f
                        armed = false
                        scope.launch { x.stop(); y.stop() }
                    },
                    onDragEnd = {
                        val v = tracker.calculateVelocity()
                        val d = if (horizontal) totalX else totalY
                        val speed = if (horizontal) v.x else v.y
                        val committed = abs(d) >= threshold ||
                            (abs(d) >= flickDistance && abs(speed) >= flickSpeed && sign(speed) == sign(d))
                        val gesture = gestureFor(horizontal, d)
                        val command = if (committed) latestCommandFor(gesture) else Command.Nothing
                        when (command) {
                            Command.Nothing -> settle()
                            is Command.Rate -> {
                                if (!armed) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                scope.launch {
                                    when (gesture) {
                                        Gesture.SWIPE_RIGHT -> x.animateTo(flyOut, tween(120))
                                        Gesture.SWIPE_LEFT -> x.animateTo(-flyOut, tween(120))
                                        Gesture.SWIPE_UP -> y.animateTo(-flyOut * 2, tween(120))
                                        else -> y.animateTo(flyOut * 2, tween(120))
                                    }
                                    latestOnGesture(gesture)
                                }
                            }
                            else -> {
                                if (!armed) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                settle()
                                latestOnGesture(gesture)
                            }
                        }
                    },
                    onDragCancel = { settle() },
                    onDrag = { change, drag ->
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        totalX += drag.x
                        totalY += drag.y
                        horizontal = abs(totalX) >= abs(totalY)
                        val along = if (horizontal) totalX else totalY
                        // Directions without an action resist, so the card shows it will not go there.
                        val resist = latestCommandFor(gestureFor(horizontal, along)) == Command.Nothing
                        val shown = along * (if (resist) 0.25f else 1f)
                        scope.launch {
                            // The card moves along one axis only; the other snaps back to rest.
                            if (horizontal) { x.snapTo(shown); y.snapTo(0f) } else { y.snapTo(shown); x.snapTo(0f) }
                        }
                        val nowArmed = !resist && abs(along) >= threshold
                        if (nowArmed && !armed) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        armed = nowArmed
                    },
                )
            },
    ) {
        ElevatedCard(Modifier.fillMaxSize(), shape = RoundedCornerShape(24.dp)) {
            CompositionLocalProvider(LocalTapTargets provides tapTargets) { content() }
        }
        val horizontal = abs(x.value) >= abs(y.value)
        val d = if (horizontal) x.value else y.value
        val progress = abs(d) / threshold
        if (swipeEnabled && progress > 0.1f) {
            val command = commandFor(gestureFor(horizontal, d))
            if (command != Command.Nothing) {
                val ready = progress >= 1f
                Text(
                    command.label(),
                    color = command.color(),
                    fontSize = if (ready) 32.sp else 26.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(24.dp)
                        .alpha((progress * 1.5f).coerceAtMost(1f)),
                )
            }
        }
    }
}

/**
 * Spots on the card that do something when tapped, such as the example sentence. They are
 * resolved by the card's own tap handler instead of a clickable child, so a swipe that starts
 * on them still moves the card: nothing inside the card ever swallows a drag.
 */
private class TapTargets {
    var origin = Offset.Zero
    val targets = HashMap<Any, Pair<Rect, () -> Unit>>()

    fun find(point: Offset): (() -> Unit)? = targets.values.firstOrNull { it.first.contains(point) }?.second
}

private val LocalTapTargets = staticCompositionLocalOf<TapTargets?> { null }

@Composable
private fun Modifier.tapTarget(onTap: () -> Unit): Modifier {
    val targets = LocalTapTargets.current ?: return clickable(onClick = onTap)
    val key = remember { Any() }
    val latest by rememberUpdatedState(onTap)
    DisposableEffect(targets) { onDispose { targets.targets.remove(key) } }
    return onGloballyPositioned { targets.targets[key] = it.boundsInRoot() to { latest() } }
}

/** How far a swipe must travel before it counts; a quick flick always counts after 20 dp. */
enum class SwipeSensitivity(val label: String, val distance: Dp) {
    HIGH("灵敏", 44.dp),
    MEDIUM("适中", 64.dp),
    LOW("稳重（第一版）", 110.dp),
}

@Composable
private fun CardFace(
    current: StudyCard,
    revealed: Boolean,
    articleChoice: Gender?,
    onSpeak: () -> Unit,
    onSpeakText: (String) -> Unit,
) {
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
                    Details(entry, onSpeakText)
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
private fun Details(entry: Entry, onSpeakText: (String) -> Unit) {
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
        Text(
            "🔊 ${entry.example}",
            Modifier.tapTarget { onSpeakText(entry.example) }.padding(4.dp),
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Medium,
        )
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
