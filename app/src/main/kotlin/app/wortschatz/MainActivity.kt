package app.wortschatz

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wortschatz.core.Gesture
import app.wortschatz.ui.DoneScreen
import app.wortschatz.ui.GestureSettingsScreen
import app.wortschatz.ui.HomeScreen
import app.wortschatz.ui.StudyScreen
import app.wortschatz.ui.WortschatzTheme

class MainActivity : ComponentActivity() {
    private val viewModel: StudyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WortschatzTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                BackHandler(enabled = state.screen != Screen.HOME) { viewModel.goHome() }
                when (state.screen) {
                    Screen.HOME -> HomeScreen(state.home, state.scheme, viewModel)
                    Screen.STUDY -> StudyScreen(state, viewModel)
                    Screen.DONE -> DoneScreen(state, viewModel)
                    Screen.GESTURES -> GestureSettingsScreen(state.scheme, viewModel)
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val gesture = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> Gesture.VOLUME_UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> Gesture.VOLUME_DOWN
            else -> null
        }
        if (gesture != null && viewModel.handlesKey(gesture)) {
            // Holding the key down must not answer a run of cards.
            if (event.repeatCount == 0) viewModel.onGesture(gesture)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
