package ir.salisec.autosender

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Outbox
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Outbox
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ir.salisec.autosender.ui.CampaignStudioScreen
import ir.salisec.autosender.ui.EmailSetupWizard
import ir.salisec.autosender.ui.HomeScreen
import ir.salisec.autosender.ui.PipelineInboxScreen
import ir.salisec.autosender.ui.SaliTheme
import ir.salisec.autosender.ui.Screen
import ir.salisec.autosender.ui.SendScreen
import ir.salisec.autosender.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val app = application as App
        setContent {
            SaliTheme {
                AppShell(app)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(app: App) {
    val firstRunSetup = remember { app.prefs.load().isThunderbirdSetupNeeded() }
    var current by remember { mutableStateOf<Screen>(if (firstRunSetup) Screen.EmailSetup else Screen.Home) }
    var previousTab by remember { mutableStateOf<Screen>(Screen.Home) }
    var pendingCount by remember { mutableIntStateOf(0) }
    val sendState by app.engine.state.collectAsState()

    fun navigate(target: Screen) {
        if (target != Screen.Settings && target != Screen.EmailSetup) {
            previousTab = target
        }
        current = target
    }

    LaunchedEffect(
        current,
        sendState.sent,
        sendState.failed,
        sendState.skipped,
        sendState.running
    ) {
        pendingCount = app.db.messageDao().pendingCount()
    }

    BackHandler(enabled = current != Screen.Home && current != Screen.EmailSetup) {
        current = if (current == Screen.Settings) previousTab else Screen.Home
    }

    val titleText = when (current) {
        Screen.Home -> stringResource(R.string.app_name)
        Screen.Campaign -> stringResource(R.string.nav_campaign)
        Screen.Send -> stringResource(R.string.nav_send)
        Screen.PipelineInbox -> stringResource(R.string.nav_pipeline)
        Screen.Settings -> stringResource(R.string.nav_settings)
        Screen.EmailSetup -> "ورود و تنظیم ایمیل"
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            titleText,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    if (current == Screen.Settings || (current == Screen.EmailSetup && !firstRunSetup)) {
                        IconButton(
                            onClick = { current = previousTab },
                            modifier = Modifier.testTag("settings_back_button")
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.send_back)
                            )
                        }
                    }
                },
                actions = {
                    if (current != Screen.Settings && current != Screen.EmailSetup) {
                        IconButton(
                            onClick = { navigate(Screen.Settings) },
                            modifier = Modifier.testTag("open_settings_button")
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = stringResource(R.string.nav_settings)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        bottomBar = {
            if (current != Screen.Settings && current != Screen.EmailSetup) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp
                ) {
                    NavigationBarItem(
                        selected = current == Screen.Home,
                        onClick = { navigate(Screen.Home) },
                        icon = {
                            Icon(
                                if (current == Screen.Home) Icons.Filled.Dashboard else Icons.Outlined.Dashboard,
                                contentDescription = null
                            )
                        },
                        label = { Text(stringResource(R.string.nav_home), maxLines = 1) },
                        modifier = Modifier.testTag("nav_home")
                    )
                    NavigationBarItem(
                        selected = current == Screen.Campaign,
                        onClick = { navigate(Screen.Campaign) },
                        icon = {
                            Icon(
                                if (current == Screen.Campaign) Icons.Filled.AutoAwesome else Icons.Outlined.AutoAwesome,
                                contentDescription = null
                            )
                        },
                        label = { Text(stringResource(R.string.nav_campaign), maxLines = 1) },
                        modifier = Modifier.testTag("nav_campaign")
                    )
                    NavigationBarItem(
                        selected = current == Screen.Send,
                        onClick = { navigate(Screen.Send) },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (pendingCount > 0) {
                                        Badge { Text(pendingCount.toString()) }
                                    }
                                }
                            ) {
                                Icon(
                                    if (current == Screen.Send) Icons.Filled.Outbox else Icons.Outlined.Outbox,
                                    contentDescription = null
                                )
                            }
                        },
                        label = { Text(stringResource(R.string.nav_send), maxLines = 1) },
                        modifier = Modifier.testTag("nav_send")
                    )
                    NavigationBarItem(
                        selected = current == Screen.PipelineInbox,
                        onClick = { navigate(Screen.PipelineInbox) },
                        icon = {
                            Icon(
                                if (current == Screen.PipelineInbox) Icons.Filled.Forum else Icons.Outlined.Forum,
                                contentDescription = null
                            )
                        },
                        label = { Text(stringResource(R.string.nav_pipeline), maxLines = 1) },
                        modifier = Modifier.testTag("nav_pipeline")
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (current) {
                Screen.Home -> HomeScreen(app = app, go = { navigate(it) })
                Screen.Campaign -> CampaignStudioScreen(
                    app = app,
                    onOpenSettings = { navigate(Screen.Settings) },
                    onGoToSend = { navigate(Screen.Send) }
                )
                Screen.Send -> SendScreen(app = app, onOpenSettings = { navigate(Screen.Settings) })
                Screen.PipelineInbox -> PipelineInboxScreen(app = app, onOpenSettings = { navigate(Screen.Settings) })
                Screen.Settings -> SettingsScreen(
                    app = app,
                    onBack = { current = previousTab },
                    onOpenEmailSetup = { navigate(Screen.EmailSetup) }
                )
                Screen.EmailSetup -> EmailSetupWizard(
                    app = app,
                    isFirstRun = firstRunSetup,
                    onDone = { current = Screen.Home }
                )
            }
        }
    }
}
