package app.wortschatz

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wortschatz.ui.DoneScreen
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
                    Screen.HOME -> HomeScreen(state.home, viewModel)
                    Screen.STUDY -> StudyScreen(state, viewModel)
                    Screen.DONE -> DoneScreen(state, viewModel)
                }
            }
        }
    }
}
