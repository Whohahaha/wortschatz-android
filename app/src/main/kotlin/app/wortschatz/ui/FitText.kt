package app.wortschatz.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * A headword on one line: long compounds such as "die Sehenswürdigkeit" shrink to fit the width
 * instead of breaking mid-word onto a second line.
 */
@Composable
fun FitText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    maxSize: TextUnit = 40.sp,
    minSize: TextUnit = 16.sp,
    fontWeight: FontWeight = FontWeight.SemiBold,
) {
    val color = LocalContentColor.current
    BasicText(
        text = text,
        modifier = modifier,
        style = LocalTextStyle.current.copy(
            color = color,
            fontWeight = fontWeight,
            textAlign = TextAlign.Center,
            lineHeight = 1.2.em,
        ),
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = minSize, maxFontSize = maxSize, stepSize = 1.sp),
    )
}

@Composable
fun FitText(text: String, modifier: Modifier = Modifier, maxSize: TextUnit = 40.sp, minSize: TextUnit = 16.sp) =
    FitText(AnnotatedString(text), modifier, maxSize, minSize)
