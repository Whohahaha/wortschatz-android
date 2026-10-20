package app.wortschatz

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wortschatz.core.Gesture
import app.wortschatz.ui.BooksScreen
import app.wortschatz.ui.DoneScreen
import app.wortschatz.ui.DrillScreen
import app.wortschatz.ui.ImportScreen
import app.wortschatz.ui.GestureSettingsScreen
import app.wortschatz.ui.HomeScreen
import app.wortschatz.ui.NumbersScreen
import app.wortschatz.ui.StatsScreen
import app.wortschatz.ui.WordsScreen
import app.wortschatz.ui.SettingsScreen
import app.wortschatz.ui.StudyScreen
import app.wortschatz.ui.WortschatzTheme

class MainActivity : ComponentActivity() {
    private val viewModel: StudyViewModel by viewModels()

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::beginImport)
    }

    private val exportBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let(viewModel::exportBackup)
    }

    private val restoreBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::restoreBackup)
    }

    private fun pickBackup() = restoreBackup.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))

    private fun exportBackupFile() =
        exportBackup.launch("wortschatz-backup-" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(java.util.Date()) + ".zip")

    // Shadowing records the learner's voice; the microphone is asked for on first use.
    private var pendingShadow: String? = null
    private val askMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingShadow?.let { if (granted) viewModel.toggleShadow(it) }
        pendingShadow = null
    }

    private fun shadow(text: String) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.toggleShadow(text)
        } else {
            pendingShadow = text
            askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // The reminder is only switched on once the notification permission is granted.
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.setReminderEnabled(granted)
    }

    private fun toggleReminder(enabled: Boolean) {
        if (enabled && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.setReminderEnabled(enabled)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShared(intent)
        setContent {
            WortschatzTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                BackHandler(enabled = state.screen != Screen.HOME) {
                    when (state.screen) {
                        Screen.IMPORT -> viewModel.openBooks()
                        Screen.GESTURES -> viewModel.openSettings()
                        else -> viewModel.goHome()
                    }
                }
                when (state.screen) {
                    Screen.HOME -> HomeScreen(state.home, viewModel, onRestore = ::pickBackup)
                    Screen.SETTINGS -> SettingsScreen(
                        state.home, state.scheme, viewModel,
                        onReminderToggle = ::toggleReminder, onExport = ::exportBackupFile, onRestore = ::pickBackup,
                    )
                    Screen.STUDY -> StudyScreen(state, viewModel, onShadow = ::shadow)
                    Screen.DONE -> DoneScreen(state, viewModel)
                    Screen.GESTURES -> GestureSettingsScreen(state.scheme, state.swipeSensitivity, viewModel)
                    Screen.BOOKS -> BooksScreen(state.books, viewModel, onPickFile = { pickFile.launch(arrayOf("*/*")) }, onInstallVoice = ::installVoice)
                    Screen.IMPORT -> state.import?.let { ImportScreen(it, viewModel) }
                    Screen.DRILL -> state.drill?.let { DrillScreen(it, viewModel) }
                    Screen.NUMBERS -> state.numbers?.let { NumbersScreen(it, viewModel) }
                    Screen.WORDS -> state.words?.let { WordsScreen(it, viewModel) }
                    Screen.STATS -> state.stats?.let { StatsScreen(it, viewModel) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShared(intent)
    }

    /** A word list opened with, or shared to, the app from a file manager, chat app or browser. */
    private fun handleShared(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let(viewModel::beginImport)
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                when {
                    stream != null -> viewModel.beginImport(stream)
                    !text.isNullOrBlank() -> viewModel.beginImportText(text)
                }
            }
        }
    }

    private fun installVoice() {
        try {
            startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
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
