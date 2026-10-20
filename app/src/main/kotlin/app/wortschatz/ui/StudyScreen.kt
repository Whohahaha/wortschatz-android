package app.wortschatz.ui

import androidx.compose.animation.core.Animatable
import app.wortschatz.core.Conjugation
import app.wortschatz.core.QuizMode
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import app.wortschatz.TypedAnswer
import app.wortschatz.core.Cloze
import app.wortschatz.core.Lookup
import app.wortschatz.core.LookupResult
import app.wortschatz.core.Verdict
import androidx.compose.animation.core.spring
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
import app.wortschatz.FamilyMember
import app.wortschatz.core.Swap
import app.wortschatz.core.ConfusableGroup
import app.wortschatz.core.Hints
import app.wortschatz.core.PartOfSpeech
import app.wortschatz.core.fsrs.Rating
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

@Composable
fun StudyScreen(state: UiState, vm: StudyViewModel, onShadow: (String) -> Unit) {
    val current = state.current ?: return
    val type = current.card.type
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = vm::goHome) { Text("‹ 返回") }
                Spacer(Modifier.weight(1f))
                Text("${state.counts.new}", color = CountColors.New, fontWeight = FontWeight.Bold)
                Text("  ${state.counts.learning}", color = CountColors.Learning, fontWeight = FontWeight.Bold)
                Text("  ${state.counts.review}", color = CountColors.Review, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = vm::markKnown) { Text("斩") }
                TextButton(onClick = vm::undo, enabled = state.canUndo) { Text("↶ 撤销") }
            }

            val answered = when {
                type == CardType.ARTICLE -> state.articleChoice != null
                type.isTyped -> state.typed != null
                current.mode.isChoice -> state.picked != null
                else -> state.revealed
            }
            GestureCard(
                key = current.card.id + current.card.memory.reps,
                scheme = state.scheme,
                sensitivity = state.swipeSensitivity,
                commandFor = { g -> resolve(state.scheme.actionFor(g), type, answered, current.objective) },
                onGesture = vm::onGesture,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            ) { dragProgress ->
                CardFace(
                    current, state.revealed || state.picked != null, state.articleChoice, state.typed, state.recording, dragProgress,
                    onSpeak = vm::speakCurrent, onSpeakText = vm::speakText, onLookup = vm::lookupWord, onShadow = onShadow,
                )
            }

            when {
                type == CardType.ARTICLE -> ArticleButtons(state.articleChoice, current.entry.gender, vm::chooseArticle)
                type.isTyped -> TypedAnswerBar(current.card.id + current.card.memory.reps, state.typed, vm)
                current.mode.isChoice -> ChoiceButtons(current, state.picked, vm::pick)
                !state.revealed -> Button(onClick = vm::reveal, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                    Text("显示答案", fontSize = 18.sp)
                }
                else -> RatingButtons(current, vm::rate)
            }
        }
    }
    state.lookup?.let { LookupDialog(it, onSpeak = vm::speakText, onDismiss = vm::closeLookup) }
}

private val swipeGestures = setOf(Gesture.SWIPE_LEFT, Gesture.SWIPE_RIGHT, Gesture.SWIPE_UP, Gesture.SWIPE_DOWN)

private fun Command.color(): Color = when (this) {
    is Command.Rate -> when (rating) {
        Rating.AGAIN -> Color(0xFFE5484D)
        Rating.HARD -> Color(0xFFE09B2D)
        Rating.GOOD -> Color(0xFF30A46C)
        Rating.EASY -> Color(0xFF3B82F6)
    }
    Command.Master -> Color(0xFF8E4EC6)
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
    Command.Master -> "斩：已会"
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
    /** Gets how far the card is dragged, 0 at rest and 1 at the commit distance. */
    content: @Composable (dragProgress: () -> Float) -> Unit,
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
                // The card follows the finger freely in both directions, diagonals included, so
                // turning from up/down to left/right is one continuous movement. Which way the
                // swipe counts is only decided on release, by the larger of the two components.
                // It is judged from this drag's own movement, never from where the card still is
                // after the last swipe sprang back.
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
                            is Command.Rate, Command.Master -> {
                                if (!armed) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                scope.launch {
                                    // Fly out along the way the finger was going, diagonal or not.
                                    val len = kotlin.math.hypot(totalX, totalY).coerceAtLeast(1f)
                                    val target = if (horizontal) flyOut * 1.5f else flyOut * 2.5f
                                    launch { x.animateTo(totalX / len * target, tween(140)) }
                                    y.animateTo(totalY / len * target, tween(140))
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
                        // Each direction without an action resists on its own axis, so the card
                        // shows it will not go there while still following the finger.
                        val resistX = latestCommandFor(gestureFor(true, totalX)) == Command.Nothing
                        val resistY = latestCommandFor(gestureFor(false, totalY)) == Command.Nothing
                        scope.launch {
                            x.snapTo(totalX * (if (resistX) 0.25f else 1f))
                            y.snapTo(totalY * (if (resistY) 0.25f else 1f))
                        }
                        val resist = if (horizontal) resistX else resistY
                        val nowArmed = !resist && abs(along) >= threshold
                        if (nowArmed && !armed) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        armed = nowArmed
                    },
                )
            },
    ) {
        ElevatedCard(Modifier.fillMaxSize(), shape = RoundedCornerShape(24.dp)) {
            CompositionLocalProvider(LocalTapTargets provides tapTargets) {
                content { (kotlin.math.hypot(x.value, y.value) / threshold).coerceIn(0f, 1f) }
            }
        }
        val ax = abs(x.value)
        val ay = abs(y.value)
        val horizontal = ax >= ay
        val d = if (horizontal) x.value else y.value
        val progress = abs(d) / threshold
        if (swipeEnabled && progress > 0.1f) {
            val command = commandFor(gestureFor(horizontal, d))
            if (command != Command.Nothing) {
                val ready = progress >= 1f
                // Near the diagonal the label fades, so switching between the two directions
                // is a crossfade rather than a jump.
                val dominance = ((maxOf(ax, ay) - minOf(ax, ay)) / maxOf(ax, ay).coerceAtLeast(1f) * 2.5f).coerceIn(0f, 1f)
                Text(
                    command.label(),
                    color = command.color(),
                    fontSize = if (ready) 32.sp else 26.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(24.dp)
                        .alpha((progress * 1.5f).coerceAtMost(1f) * dominance),
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
    val targets = HashMap<Any, Pair<Rect, (Offset) -> Unit>>()

    /** The handler under [point], called with the point relative to that target. */
    fun find(point: Offset): (() -> Unit)? =
        targets.values.firstOrNull { it.first.contains(point) }?.let { (rect, onTap) -> { onTap(point - rect.topLeft) } }
}

private val LocalTapTargets = staticCompositionLocalOf<TapTargets?> { null }

@Composable
private fun Modifier.tapTarget(onTap: () -> Unit): Modifier = tapTargetAt { onTap() }

/** Like [tapTarget], with where on the target the tap landed. */
@Composable
private fun Modifier.tapTargetAt(onTap: (Offset) -> Unit): Modifier {
    val targets = LocalTapTargets.current ?: return pointerInput(Unit) { detectTapGestures(onTap = onTap) }
    val key = remember { Any() }
    val latest by rememberUpdatedState(onTap)
    DisposableEffect(targets) { onDispose { targets.targets.remove(key) } }
    return onGloballyPositioned { targets.targets[key] = it.boundsInRoot() to { offset -> latest(offset) } }
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
    typed: TypedAnswer?,
    recording: Boolean,
    dragProgress: () -> Float,
    onSpeak: () -> Unit,
    onSpeakText: (String) -> Unit,
    onLookup: (String) -> Unit,
    onShadow: (String) -> Unit,
) {
    val entry = current.entry
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        QuestionTag(current)
        Spacer(Modifier.height(24.dp))
        when (current.card.type) {
            CardType.MEANING -> if (current.mode == QuizMode.PICK_WORD) {
                // Chinese shown; the German is picked below and revealed once answered.
                if (!revealed) {
                    Text(entry.meaning, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text("选出对应的德语", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Headword(entry)
                    SpeakButton(onSpeak)
                    Answer(entry.meaning)
                }
            } else if (current.mode == QuizMode.PICK_MEANING) {
                Headword(entry)
                SpeakButton(onSpeak)
                if (!revealed) {
                    Spacer(Modifier.height(12.dp))
                    Text("选出它的中文意思", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Answer(entry.meaning)
                    Details(entry, onSpeakText, onLookup)
                }
            } else {
                Headword(entry)
                if (revealed) SpeakRow(onSpeak, recording) { onShadow(entry.spoken) } else SpeakButton(onSpeak)
                if (revealed) {
                    Answer(entry.meaning)
                    Details(entry, onSpeakText, onLookup)
                    Confusables(current.confusables, current.notes, onSpeakText)
                    Family(entry, current.family, onSpeakText)
                } else {
                    // While the card is being swiped the meaning fades in, so the learner sees
                    // whether they remembered it before letting go.
                    Box(Modifier.graphicsLayer { alpha = fadeIn(dragProgress()) }) { Answer(entry.meaning) }
                }
            }
            CardType.ARTICLE -> {
                if (articleChoice == null) {
                    FitText("___ ${entry.lemma}", Modifier.fillMaxWidth())
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
                    GenderTip(entry)
                }
            }
            CardType.PLURAL -> {
                Headword(entry)
                Spacer(Modifier.height(16.dp))
                Text("复数是？", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!revealed && !entry.plural.isNullOrEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Box(Modifier.graphicsLayer { alpha = fadeIn(dragProgress()) }) {
                        FitText(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = genderColor(Gender.DIE))) { append("die ") }
                                append(entry.plural.orEmpty())
                            },
                            Modifier.fillMaxWidth(),
                            maxSize = 36.sp,
                        )
                    }
                }
                if (revealed) {
                    Spacer(Modifier.height(16.dp))
                    FitText(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = genderColor(Gender.DIE))) { append("die ") }
                            append(entry.plural.orEmpty())
                        },
                        Modifier.fillMaxWidth(),
                        maxSize = 36.sp,
                    )
                    if (entry.pluralSameAsSingular) {
                        Text("单复同形：复数只把冠词换成 die", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                    }
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
                if (forms == null) {
                    Text("说出：er/sie/es 现在时 · 过去式 · 完成时", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (!revealed) {
                    Text("说出：er/sie/es 现在时 · 过去式 · 完成时", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.graphicsLayer { alpha = fadeIn(dragProgress()) }) {
                        FormRow("er/sie/es", forms.present3)
                        FormRow("过去式", forms.preterite)
                        FormRow("完成时", perfect(forms.auxiliary, forms.participle))
                    }
                } else {
                    FormRow("er/sie/es", forms.present3)
                    FormRow("过去式", forms.preterite)
                    FormRow("完成时", perfect(forms.auxiliary, forms.participle))
                    ConjugationTable(entry)
                }
            }
            CardType.SPELLING -> SpellingFace(entry, typed, onSpeak, onSpeakText, onLookup)
            CardType.CLOZE -> ClozeFace(entry, typed, onSpeakText, onLookup)
        }
    }
}

/** Present tense for all six persons, ich/du/er on the left and wir/ihr/sie on the right. */
@Composable
private fun ConjugationTable(entry: Entry) {
    val forms = remember(entry) { Conjugation.present(entry) } ?: return
    Spacer(Modifier.height(10.dp))
    Text("现在时变位", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        for (half in listOf(0, 3)) {
            Column {
                for (i in half until half + 3) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(Conjugation.persons[i] + " ") }
                            append(forms[i])
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/** What kind of question this is, as a coloured tag, so a choice question never looks like a flip card. */
@Composable
private fun QuestionTag(current: StudyCard) {
    val entry = current.entry
    val kind = when (current.card.type) {
        CardType.MEANING -> current.mode.label
        CardType.SPELLING -> "拼写 · 中译德"
        CardType.CLOZE -> "填空 · 例句"
        CardType.ARTICLE -> "选冠词"
        CardType.PLURAL -> "说出复数"
        CardType.VERB_FORMS -> "说出三形"
    }
    val strong = current.objective
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            kind,
            Modifier
                .background(
                    if (strong) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (strong) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Text(
            "  ${entry.level} · ${entry.pos.label}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Four options under a multiple-choice card; after a tap the right one turns green, a wrong pick red. */
@Composable
private fun ChoiceButtons(current: StudyCard, picked: Int?, onPick: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        current.options.forEachIndexed { i, option ->
            val right = option.id == current.entry.id
            val container = when {
                picked == null -> MaterialTheme.colorScheme.surfaceVariant
                right -> CountColors.Review
                i == picked -> CountColors.Learning
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            val content = if (picked != null && (right || i == picked)) Color.White else MaterialTheme.colorScheme.onSurface
            Button(
                onClick = { onPick(i) },
                modifier = Modifier.fillMaxWidth().height(52.dp).alpha(if (picked != null && !right && i != picked) 0.5f else 1f),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
            ) {
                Text(
                    if (current.mode == QuizMode.PICK_WORD) option.spoken else option.meaning,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Answer opacity for a drag: hidden for small accidental moves, fully shown at the commit distance. */
private fun fadeIn(progress: Float): Float = ((progress - 0.15f) / 0.85f).coerceIn(0f, 1f)

/** The word as it should be learned: article coloured by gender, separable prefix marked. */
@Composable
private fun Headword(entry: Entry) {
    FitText(
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
        Modifier.fillMaxWidth(),
    )
}

/** Listen, plus shadowing: record yourself, then hear your take and the reference. */
@Composable
private fun SpeakRow(onSpeak: () -> Unit, recording: Boolean, onShadow: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SpeakButton(onSpeak)
        Text(
            if (recording) "⏺ 录音中，点此停止" else "🎙 跟读",
            Modifier.tapTarget(onShadow).padding(horizontal = 16.dp, vertical = 10.dp),
            color = if (recording) CountColors.Learning else MaterialTheme.colorScheme.primary,
            fontWeight = if (recording) FontWeight.Bold else null,
        )
    }
}

/**
 * Notes on the words this one is easily mixed up with (tap one for what tells them apart and
 * whether they can replace each other), then other words of the book with the same meaning.
 */
@Composable
private fun Confusables(words: List<Entry>, notes: List<ConfusableGroup>, onSpeakText: (String) -> Unit) {
    var open by remember(notes) { mutableStateOf<ConfusableGroup?>(null) }
    notes.forEach { group ->
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = { open = group }) {
            Text("易混：" + group.words.joinToString(" / "), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            SwapTag(group.swap)
            Text("  ›")
        }
    }
    if (words.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(
            "易混：" + words.joinToString("；") { "${it.spoken} ${it.meaning}" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
    open?.let { ConfusableDialog(it, onSpeakText) { open = null } }
}

/** The word's family (fahren, abfahren, Fahrer …); tap to see every member and what its prefix adds. */
@Composable
private fun Family(entry: Entry, family: List<FamilyMember>, onSpeakText: (String) -> Unit) {
    if (family.isEmpty()) return
    var open by remember(family) { mutableStateOf(false) }
    Spacer(Modifier.height(10.dp))
    OutlinedButton(onClick = { open = true }) {
        Text(
            "同根词：" + family.filter { it.entry.id != entry.id }.joinToString(" · ") { it.entry.lemma },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text("  ›")
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { open = false }) { Text("好") } },
            title = { Text("同根词 · ${family.size} 个") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    family.forEach { m ->
                        Column(Modifier.fillMaxWidth().tapTarget { onSpeakText(m.entry.spoken) }.padding(vertical = 4.dp)) {
                            Text(
                                "🔊 " + m.entry.display,
                                fontWeight = if (m.entry.id == entry.id) FontWeight.Bold else FontWeight.Medium,
                                color = if (m.entry.id == entry.id) MaterialTheme.colorScheme.primary else Color.Unspecified,
                            )
                            Text(m.entry.meaning, style = MaterialTheme.typography.bodyMedium)
                            m.prefixMeaning?.let {
                                Text(
                                    "${m.prefix}-：$it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Text(
                        "同根词按拼写自动归组，元音变化的词（kommen / Ankunft）暂时找不出来；前缀含义是常见规律，不是每个词都适用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }
}

@Composable
private fun SwapTag(swap: Swap) {
    val color = when (swap) {
        Swap.YES -> Color(0xFF2E7D32)
        Swap.PARTLY -> Color(0xFFE09B2D)
        Swap.NO -> Color(0xFFC62828)
    }
    Text(
        swap.label,
        Modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
        color = color,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
    )
}

/** What tells the words of a group apart, with examples that are read aloud when tapped. */
@Composable
private fun ConfusableDialog(group: ConfusableGroup, onSpeakText: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("好") } },
        title = { Text(group.words.joinToString(" / ")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SwapTag(group.swap)
                Text(group.difference)
                group.examples.forEach { ex ->
                    Column(Modifier.fillMaxWidth().tapTarget { onSpeakText(ex.german) }.padding(vertical = 4.dp)) {
                        Text("🔊 " + ex.german, fontWeight = FontWeight.Medium)
                        Text(ex.chinese, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
    )
}

@Composable
private fun SpeakButton(onSpeak: () -> Unit) {
    Text(
        "🔊 发音",
        Modifier.tapTarget(onSpeak).padding(horizontal = 16.dp, vertical = 10.dp),
        color = MaterialTheme.colorScheme.primary,
    )
}

/** Rule of thumb for the article, or a warning when this noun breaks it. */
@Composable
private fun GenderTip(entry: Entry) {
    val hint = Hints.gender(entry) ?: return
    Spacer(Modifier.height(12.dp))
    Text(
        if (hint.follows) "💡 ${hint.rule}" else "⚠️ ${hint.rule}，但它是例外",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Answer(text: String) {
    Spacer(Modifier.height(12.dp))
    Text(text, fontSize = 26.sp, textAlign = TextAlign.Center)
}

@Composable
private fun Details(entry: Entry, onSpeakText: (String) -> Unit, onLookup: (String) -> Unit) {
    Spacer(Modifier.height(16.dp))
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (entry.pos == PartOfSpeech.NOUN) {
        entry.pluralLabel?.let { Text(it, color = muted) }
        GenderTip(entry)
    }
    entry.verbForms?.let {
        Text("${it.present3} · ${it.preterite} · ${perfect(it.auxiliary, it.participle)}", color = muted)
    }
    ConjugationTable(entry)
    if (entry.example.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        // A split separable verb is easy to miss when reading: highlight both of its parts.
        val highlight = SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Example(entry.example, Hints.separableParts(entry.example, entry), highlight, onSpeakText, onLookup)
        Text(entry.exampleZh, textAlign = TextAlign.Center, color = muted)
    }
}

/**
 * An example sentence: tapping 🔊 reads it aloud, tapping a word looks it up.
 * [marked] ranges of the sentence are drawn in [markStyle].
 */
@Composable
private fun Example(
    sentence: String,
    marked: List<IntRange>,
    markStyle: SpanStyle,
    onSpeakText: (String) -> Unit,
    onLookup: (String) -> Unit,
) {
    val speaker = "🔊 "
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val pad = with(LocalDensity.current) { 4.dp.toPx() }
    Text(
        buildAnnotatedString {
            append(speaker)
            var at = 0
            for (range in marked) {
                append(sentence.substring(at, range.first))
                withStyle(markStyle) { append(sentence.substring(range)) }
                at = range.last + 1
            }
            append(sentence.substring(at))
        },
        Modifier
            .tapTargetAt { offset ->
                val index = layout?.getOffsetForPosition(offset - Offset(pad, pad)) ?: return@tapTargetAt
                val word = if (index < speaker.length) null else Lookup.wordAt(sentence, index - speaker.length)
                if (word == null) onSpeakText(sentence) else onLookup(word)
            }
            .padding(4.dp),
        textAlign = TextAlign.Center,
        fontWeight = FontWeight.Medium,
        onTextLayout = { layout = it },
    )
    Text(
        "点句中的词可以查词",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
    )
}

/** Spelling card: the meaning is shown, the German word is typed below the card. */
@Composable
private fun SpellingFace(
    entry: Entry,
    typed: TypedAnswer?,
    onSpeak: () -> Unit,
    onSpeakText: (String) -> Unit,
    onLookup: (String) -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (typed == null) {
        Text(entry.meaning, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(
            buildAnnotatedString {
                entry.gender?.let { g -> withStyle(SpanStyle(color = genderColor(g), fontWeight = FontWeight.Bold)) { append(g.article + " ") } }
                append("_".repeat(entry.lemma.length.coerceIn(3, 12)))
            },
            fontSize = 28.sp,
            color = muted,
        )
        Spacer(Modifier.height(12.dp))
        Text("用德语拼出来", color = muted)
    } else {
        Headword(entry)
        SpeakButton(onSpeak)
        VerdictLine(typed)
        Text(entry.meaning, color = muted)
        if (entry.example.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Example(entry.example, emptyList(), SpanStyle(), onSpeakText, onLookup)
        }
    }
}

/** Gap-fill card: the example sentence with the word left out, to be typed in its right form. */
@Composable
private fun ClozeFace(entry: Entry, typed: TypedAnswer?, onSpeakText: (String) -> Unit, onLookup: (String) -> Unit) {
    val cloze = remember(entry) { Cloze.of(entry) } ?: return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (typed == null) {
        Text(cloze.prompt, fontSize = 24.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(entry.exampleZh, color = muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Text("提示：${entry.meaning}", color = muted, textAlign = TextAlign.Center)
        Text("按句子里的形式填写", style = MaterialTheme.typography.bodySmall, color = muted)
    } else {
        val mark = SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Example(entry.example, cloze.gaps, mark, onSpeakText, onLookup)
        Text(entry.exampleZh, color = muted, textAlign = TextAlign.Center)
        VerdictLine(typed)
        Spacer(Modifier.height(8.dp))
        Headword(entry)
        Text(entry.meaning, color = muted)
    }
}

@Composable
private fun VerdictLine(typed: TypedAnswer) {
    Spacer(Modifier.height(12.dp))
    val (text, color) = when (typed.verdict) {
        Verdict.CORRECT -> "✓ 正确" to CountColors.Review
        Verdict.CLOSE -> "差一点：你写的是 ${typed.input}" to Color(0xFFE09B2D)
        Verdict.WRONG -> (if (typed.input.isBlank()) "答案是 ${typed.expected}" else "✗ 你写的是 ${typed.input}") to CountColors.Learning
    }
    Text(text, color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    if (typed.verdict != Verdict.CORRECT && typed.input.isNotBlank()) {
        Text("正确：${typed.expected}", fontSize = 18.sp, textAlign = TextAlign.Center)
    }
    Spacer(Modifier.height(8.dp))
}

/** Input under a spelling or gap-fill card, with keys for the letters most keyboards hide. */
@Composable
private fun TypedAnswerBar(key: String, typed: TypedAnswer?, vm: StudyViewModel) {
    if (typed != null) {
        if (typed.verdict != Verdict.CORRECT) {
            Button(onClick = vm::continueTyped, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Text(if (typed.verdict == Verdict.CLOSE) "继续（记为模糊）" else "继续", fontSize = 18.sp)
            }
        } else {
            Spacer(Modifier.height(64.dp))
        }
        return
    }
    var value by remember(key) { mutableStateOf(TextFieldValue("")) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(key) { focus.requestFocus() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge,
            placeholder = { Text("输入德语") },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { vm.submitTyped(value.text) }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("ä", "ö", "ü", "ß", "Ä", "Ö", "Ü").forEach { letter ->
                OutlinedButton(
                    onClick = {
                        val sel = value.selection
                        val text = value.text.replaceRange(sel.start, sel.end, letter)
                        value = TextFieldValue(text, TextRange(sel.start + letter.length))
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(0.dp),
                ) { Text(letter, fontSize = 18.sp) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = vm::giveUpTyped, modifier = Modifier.weight(1f).height(52.dp)) { Text("不会") }
            Button(onClick = { vm.submitTyped(value.text) }, modifier = Modifier.weight(2f).height(52.dp)) { Text("检查", fontSize = 18.sp) }
        }
    }
}

/** What the offline dictionary knows about a tapped word. */
@Composable
private fun LookupDialog(result: LookupResult, onSpeak: (String) -> Unit, onDismiss: () -> Unit) {
    val headword = result.entry?.spoken
        ?: result.noun?.let { "${it.first.article} ${result.nounLemma}" }
        ?: if (result.pluralOnly) "die ${result.word.replaceFirstChar { it.uppercase() }}" else null
        ?: result.verbLemma
        ?: result.word
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("好") } },
        dismissButton = { TextButton(onClick = { onSpeak(headword) }) { Text("🔊 发音") } },
        title = { Text(headword) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!headword.endsWith(result.word) && !headword.equals(result.word, ignoreCase = true)) {
                    Text("句中形式：${result.word}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                result.entry?.let { Text(it.meaning, fontSize = 20.sp) }
                if (result.pluralOnly) Text("只有复数形式", color = MaterialTheme.colorScheme.onSurfaceVariant)
                result.noun?.let { (gender, plural) ->
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = genderColor(gender), fontWeight = FontWeight.Bold)) { append(gender.article) }
                            append(" ${result.nounLemma}")
                            append(if (plural.isNotEmpty()) " · 复数 die $plural" else " · 无复数")
                        }
                    )
                }
                result.verb?.let { Text("${it.present3} · ${it.preterite} · ${perfect(it.auxiliary, it.participle)}") }
                if (!result.found) Text("离线词典里没有这个词。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else if (result.entry == null) Text("不在当前词书里，只有语法信息。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
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
