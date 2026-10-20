package app.wortschatz.core

import app.wortschatz.core.fsrs.Rating

enum class Gesture(val label: String) {
    TAP_LEFT("轻点左侧"),
    TAP_CENTER("轻点中间"),
    TAP_RIGHT("轻点右侧"),
    DOUBLE_TAP("双击"),
    LONG_PRESS("长按"),
    SWIPE_LEFT("左滑"),
    SWIPE_RIGHT("右滑"),
    SWIPE_UP("上滑"),
    SWIPE_DOWN("下滑"),
    VOLUME_UP("音量 +"),
    VOLUME_DOWN("音量 −"),
}

enum class GestureAction(val label: String) {
    NONE("无"),
    REVEAL("显示答案"),
    AGAIN("不认识"),
    HARD("模糊"),
    GOOD("认识"),
    EASY("太简单"),
    /** Shows the answer first; the same gesture again then answers "认识". */
    REVEAL_OR_GOOD("先显示答案，再按认识"),
    REVEAL_OR_AGAIN("先显示答案，再按不认识"),
    UNDO("撤销上一题"),
    SPEAK("播放发音"),
    /** "斩": the word is already known and never comes up again (undo brings it back). */
    MASTER("斩（已会，不再出现）"),
    ;

    /** Whether the card should fly off screen when this action is triggered by a swipe. */
    val answers: Boolean get() = this in setOf(AGAIN, HARD, GOOD, EASY, REVEAL_OR_GOOD, REVEAL_OR_AGAIN, MASTER)
}

/** What the study screen should do after resolving a gesture against the current card. */
sealed interface Command {
    data object Nothing : Command
    data object Reveal : Command
    data class Rate(val rating: Rating) : Command
    data object Undo : Command
    data object Speak : Command
    data object Master : Command
}

data class GestureScheme(
    val id: String,
    val name: String,
    val description: String,
    val actions: Map<Gesture, GestureAction>,
) {
    fun actionFor(gesture: Gesture): GestureAction = actions[gesture] ?: GestureAction.NONE

    fun with(gesture: Gesture, action: GestureAction) = copy(actions = actions + (gesture to action))

    /** Serialized form for settings storage: "SWIPE_LEFT=AGAIN;SWIPE_RIGHT=GOOD". */
    fun encode(): String = Gesture.entries.joinToString(";") { "${it.name}=${actionFor(it).name}" }
}

object GestureSchemes {
    const val CUSTOM_ID = "custom"

    private fun scheme(id: String, name: String, description: String, vararg pairs: Pair<Gesture, GestureAction>) =
        GestureScheme(id, name, description, Gesture.entries.associateWith { GestureAction.NONE } + pairs)

    private val tapAll = arrayOf(
        Gesture.TAP_LEFT to GestureAction.REVEAL,
        Gesture.TAP_CENTER to GestureAction.REVEAL,
        Gesture.TAP_RIGHT to GestureAction.REVEAL,
    )

    /** Default: card swipes for answering, plus long press to undo and volume keys for one-handed use. */
    val ONE_HAND = scheme(
        "one_hand", "单手速刷（推荐）",
        "点卡片看答案，右滑认识、左滑不认识、上滑太简单、下滑模糊。双击发音，长按撤销，音量键也能答题。",
        *tapAll,
        Gesture.SWIPE_RIGHT to GestureAction.GOOD,
        Gesture.SWIPE_LEFT to GestureAction.AGAIN,
        Gesture.SWIPE_UP to GestureAction.EASY,
        Gesture.SWIPE_DOWN to GestureAction.HARD,
        Gesture.DOUBLE_TAP to GestureAction.SPEAK,
        Gesture.LONG_PRESS to GestureAction.UNDO,
        Gesture.VOLUME_UP to GestureAction.REVEAL_OR_GOOD,
        Gesture.VOLUME_DOWN to GestureAction.REVEAL_OR_AGAIN,
    )

    /** The first prototype's behaviour, kept unchanged. */
    val CARD_SWIPE = scheme(
        "card_swipe", "卡片滑动（Quizlet 式）",
        "第一版的手势：点卡片看答案，右滑认识、左滑不认识、上滑太简单。其他手势不做任何事。",
        *tapAll,
        Gesture.SWIPE_RIGHT to GestureAction.GOOD,
        Gesture.SWIPE_LEFT to GestureAction.AGAIN,
        Gesture.SWIPE_UP to GestureAction.EASY,
    )

    /** AnkiDroid/AnkiMobile style tap zones: no swiping needed, the thumb just taps a side. */
    val TAP_ZONES = scheme(
        "tap_zones", "点击分区（Anki 式）",
        "不用滑动：点中间看答案，再点右侧认识、左侧不认识。上滑太简单，下滑撤销，双击发音。",
        Gesture.TAP_CENTER to GestureAction.REVEAL,
        Gesture.TAP_RIGHT to GestureAction.REVEAL_OR_GOOD,
        Gesture.TAP_LEFT to GestureAction.REVEAL_OR_AGAIN,
        Gesture.SWIPE_UP to GestureAction.EASY,
        Gesture.SWIPE_DOWN to GestureAction.UNDO,
        Gesture.DOUBLE_TAP to GestureAction.SPEAK,
        Gesture.LONG_PRESS to GestureAction.HARD,
        Gesture.VOLUME_UP to GestureAction.REVEAL_OR_GOOD,
        Gesture.VOLUME_DOWN to GestureAction.REVEAL_OR_AGAIN,
    )

    /** 国产背单词 App 式: 左右滑 = 认识/不认识, 上滑“斩”掉. Swipe directions mirror 不背单词/墨墨 habits. */
    val CN_STYLE = scheme(
        "cn_style", "国产 App 式（墨墨、不背单词）",
        "点卡片看答案，左滑认识、右滑不认识，上滑“斩”掉已经会的词，下滑撤销，长按发音。",
        *tapAll,
        Gesture.SWIPE_LEFT to GestureAction.GOOD,
        Gesture.SWIPE_RIGHT to GestureAction.AGAIN,
        Gesture.SWIPE_UP to GestureAction.MASTER,
        Gesture.SWIPE_DOWN to GestureAction.UNDO,
        Gesture.LONG_PRESS to GestureAction.SPEAK,
        Gesture.DOUBLE_TAP to GestureAction.HARD,
    )

    /** For reading in bed or on a crowded train: everything on the volume keys. */
    val HANDS_FREE = scheme(
        "hands_free", "音量键（不看屏幕也能刷）",
        "音量 + 先显示答案、再按一次算认识；音量 − 先显示答案、再按一次算不认识。屏幕上点击看答案，双击发音。",
        *tapAll,
        Gesture.VOLUME_UP to GestureAction.REVEAL_OR_GOOD,
        Gesture.VOLUME_DOWN to GestureAction.REVEAL_OR_AGAIN,
        Gesture.DOUBLE_TAP to GestureAction.SPEAK,
        Gesture.LONG_PRESS to GestureAction.UNDO,
    )

    val presets = listOf(ONE_HAND, CARD_SWIPE, TAP_ZONES, CN_STYLE, HANDS_FREE)

    val DEFAULT = ONE_HAND

    fun preset(id: String): GestureScheme? = presets.firstOrNull { it.id == id }

    fun custom(encoded: String?, base: GestureScheme = DEFAULT): GestureScheme {
        val parsed = encoded.orEmpty().split(';').mapNotNull { part ->
            val (g, a) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            val gesture = Gesture.entries.firstOrNull { it.name == g } ?: return@mapNotNull null
            val action = GestureAction.entries.firstOrNull { it.name == a } ?: return@mapNotNull null
            gesture to action
        }.toMap()
        return GestureScheme(CUSTOM_ID, "自定义", "每个手势做什么由你决定。", base.actions + parsed)
    }
}

/**
 * Turns a gesture's action into a command for the current card.
 *
 * Article cards are graded by the app, so gestures cannot rate them, and pronunciation stays
 * silent until the article has been chosen, since hearing "der Tisch" gives the answer away.
 */
fun resolve(action: GestureAction, type: CardType, revealed: Boolean, objective: Boolean = type.isObjective): Command {
    if (objective) {
        return when (action) {
            GestureAction.UNDO -> Command.Undo
            GestureAction.SPEAK -> if (revealed) Command.Speak else Command.Nothing
            GestureAction.MASTER -> Command.Master
            else -> Command.Nothing
        }
    }
    return when (action) {
        GestureAction.NONE -> Command.Nothing
        GestureAction.REVEAL -> if (revealed) Command.Nothing else Command.Reveal
        GestureAction.AGAIN -> Command.Rate(Rating.AGAIN)
        GestureAction.HARD -> Command.Rate(Rating.HARD)
        GestureAction.GOOD -> Command.Rate(Rating.GOOD)
        GestureAction.EASY -> Command.Rate(Rating.EASY)
        GestureAction.REVEAL_OR_GOOD -> if (revealed) Command.Rate(Rating.GOOD) else Command.Reveal
        GestureAction.REVEAL_OR_AGAIN -> if (revealed) Command.Rate(Rating.AGAIN) else Command.Reveal
        GestureAction.UNDO -> Command.Undo
        GestureAction.SPEAK -> Command.Speak
        GestureAction.MASTER -> Command.Master
    }
}
