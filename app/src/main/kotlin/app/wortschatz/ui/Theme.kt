package app.wortschatz.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.wortschatz.core.Gender

/** der = blue, die = red, das = green, everywhere in the app. */
@Composable
fun genderColor(gender: Gender): Color {
    val dark = isSystemInDarkTheme()
    return when (gender) {
        Gender.DER -> if (dark) Color(0xFF8AB4FF) else Color(0xFF2F6FDE)
        Gender.DIE -> if (dark) Color(0xFFFF8A80) else Color(0xFFD9443A)
        Gender.DAS -> if (dark) Color(0xFF7DDBA3) else Color(0xFF23915A)
    }
}

/** Queue counter colours, following Anki: new blue, learning red, review green. */
object CountColors {
    val New = Color(0xFF3B82F6)
    val Learning = Color(0xFFE5484D)
    val Review = Color(0xFF30A46C)
}

@Composable
fun WortschatzTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
