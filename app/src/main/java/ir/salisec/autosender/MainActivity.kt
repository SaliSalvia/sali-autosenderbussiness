package ir.salisec.autosender

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import ir.salisec.autosender.ui.GenerateScreen
import ir.salisec.autosender.ui.HomeScreen
import ir.salisec.autosender.ui.InboxScreen
import ir.salisec.autosender.ui.PipelineScreen
import ir.salisec.autosender.ui.SaliTheme
import ir.salisec.autosender.ui.Screen
import ir.salisec.autosender.ui.SendScreen
import ir.salisec.autosender.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val app = application as App
        setContent {
            SaliTheme {
                AppNav(app)
            }
        }
    }
}

/** Simple back-stack navigation across the app screens. */
@Composable
fun AppNav(app: App) {
    val back = remember { mutableStateListOf<Screen>(Screen.Home) }
    val go: (Screen) -> Unit = { back.add(it) }
    val pop: () -> Unit = { if (back.size > 1) back.removeAt(back.size - 1) }
    BackHandler(enabled = back.size > 1) { pop() }

    when (val screen = back.last()) {
        Screen.Home -> HomeScreen(app, go)
        Screen.Settings -> SettingsScreen(app, onBack = pop)
        Screen.Domains -> ir.salisec.autosender.ui.DomainsScreen(app, onBack = pop, onNext = { go(Screen.Contacts) })
        Screen.Contacts -> ir.salisec.autosender.ui.ContactsScreen(app, onBack = pop, onNext = { go(Screen.Reports) })
        Screen.Reports -> ir.salisec.autosender.ui.ReportsScreen(app, onBack = pop, onNext = { go(Screen.Generate) })
        Screen.Generate -> GenerateScreen(app, onBack = pop, onDone = { go(Screen.Send) })
        Screen.Send -> SendScreen(app, onBack = pop)
        Screen.Inbox -> InboxScreen(app, onBack = pop)
        Screen.Pipeline -> PipelineScreen(app, onBack = pop)
    }
}
