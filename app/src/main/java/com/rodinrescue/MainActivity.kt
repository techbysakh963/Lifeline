package com.rodinrescue

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rodinrescue.ui.*

class MainActivity : FragmentActivity() {
    private val vm: RodinViewModel by viewModels()
    /** App lock: locked at launch and again after 30 s in the background, when enabled in Settings. */
    private var locked by mutableStateOf(false)
    private var backgroundedAt = 0L

    override fun onStart() {
        super.onStart()
        if (vm.appLock && (backgroundedAt == 0L || System.currentTimeMillis() - backgroundedAt > 30_000)) locked = true
    }
    override fun onStop() { super.onStop(); if (!isChangingConfigurations) backgroundedAt = System.currentTimeMillis() }

    /** Screen asked for by a launcher shortcut (long-press the app icon → "Type on PC"); consumed once shown. */
    private var shortcutRoute by mutableStateOf<String?>(null)
    private fun readShortcut(i: Intent?) { if (i?.action == ACTION_VOICE_TYPE) shortcutRoute = "voice" }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); readShortcut(intent) }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) readShortcut(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Frost.Bg.toArgb(), Frost.Ink.toArgb()),
            navigationBarStyle = SystemBarStyle.light(Frost.Bg.toArgb(), Frost.Ink.toArgb())
        )
        super.onCreate(savedInstanceState)
        setContent {
            RodinTheme {
                var splash by rememberSaveable { mutableStateOf(vm.showSplash && shortcutRoute == null) }
                val view = LocalView.current
                val keepOn = vm.keepScreenOn && vm.running
                LaunchedEffect(keepOn) {
                    if (keepOn) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                LaunchedEffect(splash) { WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !splash }
                Box(Modifier.fillMaxSize()) {
                    RodinRoot(vm, shortcutRoute) { shortcutRoute = null }
                    if (locked && vm.appLock) LockScreen(onUnlock = { AppLock.prompt(this@MainActivity, "Unlock ${Brand.NAME}") { locked = false } })
                    AnimatedVisibility(splash, enter = EnterTransition.None, exit = fadeOut(tween(500)) + scaleOut(tween(500), targetScale = 1.08f)) {
                        SplashScreen { splash = false }
                    }
                }
            }
        }
    }
}

@Composable
fun RodinRoot(vm: RodinViewModel, shortcutRoute: String? = null, shortcutShown: () -> Unit = {}) {
    val nav = rememberNavController()
    LaunchedEffect(shortcutRoute) { shortcutRoute?.let { nav.navigate(it) { launchSingleTop = true }; shortcutShown() } }
    val dests = listOf(
        NavItem("dashboard", "Home", Icons.Outlined.Home), NavItem("chat", "Rescue chat", Icons.Outlined.ChatBubbleOutline),
        NavItem("boot", "Boot drive", Icons.Outlined.Album),
        NavItem("providers", "AI brain", Icons.Outlined.AutoAwesome), NavItem("devices", "Devices", Icons.Outlined.Computer)
    )
    val entry by nav.currentBackStackEntryAsState()
    val go: (String) -> Unit = { r -> nav.navigate(r) { launchSingleTop = true; popUpTo("dashboard") } }
    // Content runs edge to edge behind the floating pill; screens pad their own bottom by LocalNavSpace
    // so nothing is cut off and the area around the pill stays fully transparent.
    val navSpace = NavBarHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize().background(Frost.Bg)) {
        CompositionLocalProvider(LocalNavSpace provides navSpace) {
        NavHost(nav, "dashboard", Modifier.fillMaxSize()) {
            composable("dashboard") { DashboardScreen(vm, openKbRescue = { nav.navigate("kbrescue") }, openVoice = { nav.navigate("voice") }, openSerial = { nav.navigate("serial") }, openBoot = { go("boot") }, openChat = { go("chat") }, openAi = { go("providers") }, openSettings = { go("settings") }, openDevices = { go("devices") }) }
            composable("chat") { ChatScreen(vm, openKbRescue = { nav.navigate("kbrescue") }, openTerminal = { nav.navigate("terminal") }) }
            composable("terminal") { TerminalScreen(vm) }
            composable("boot") { BootScreen(vm) }
            composable("serial") { SerialScreen(vm, back = { nav.popBackStack() }, openChat = { go("chat") }) }
            composable("voice") { VoiceTypeScreen(vm, back = { nav.popBackStack() }) }
            composable("kbrescue") { KeyboardRescueScreen(vm, back = { nav.popBackStack() }) }
            composable("providers") { ProvidersScreen(vm) }
            composable("devices") { DevicesScreen(vm, openChat = { go("chat") }, openKbRescue = { nav.navigate("kbrescue") }) }
            composable("settings") { SettingsScreen(vm, back = { nav.popBackStack() }, openStorage = { nav.navigate("storage") }) }
            composable("storage") { StorageScreen(vm, back = { nav.popBackStack() }) }
        }
        }
        // Content scrolls under the status bar and fades out there instead of being sliced off.
        Box(Modifier.fillMaxWidth().height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 22.dp)
            .background(Brush.verticalGradient(0f to Frost.Bg, 0.6f to Frost.Bg, 1f to Frost.Bg.copy(alpha = 0f))))
        Box(Modifier.align(Alignment.BottomCenter)) { PillNavBar(dests, entry?.destination?.route, go) }
    }
    ApprovalDialog(vm)
    HostKeyDialog(vm)
    RemoveDialog(vm)
    RemovalReportDialog(vm)
}

const val ACTION_VOICE_TYPE = "com.rodinrescue.action.VOICE_TYPE"
