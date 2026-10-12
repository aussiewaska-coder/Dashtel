package dev.dialer.voice

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dialer.voice.call.AudioRouter
import dev.dialer.voice.data.DeviceContact
import dev.dialer.voice.data.ThemeMode
import dev.dialer.voice.data.TwilioRest
import dev.dialer.voice.data.UiPreferences
import dev.dialer.voice.diagnostics.DiagnosticLog
import dev.dialer.voice.model.CallRecord
import dev.dialer.voice.model.CallState
import dev.dialer.voice.model.DialPlan
import dev.dialer.voice.model.cleanDialInput
import dev.dialer.voice.model.createDialPlan
import dev.dialer.voice.model.normalizeForDial
import dev.dialer.voice.twilio.ActiveCall
import dev.dialer.voice.twilio.RegistrationState
import dev.dialer.voice.twilio.VoiceManager
import dev.dialer.voice.telecom.TelecomCallManager
import dev.dialer.voice.ui.CallScreen
import dev.dialer.voice.ui.ContactsScreen
import dev.dialer.voice.ui.DialerScreen
import dev.dialer.voice.ui.DialerTheme
import dev.dialer.voice.ui.DashPhoneSplash
import dev.dialer.voice.ui.HistoryScreen
import dev.dialer.voice.ui.RegistrationStatusChip
import dev.dialer.voice.ui.SettingsScreen
import dev.dialer.voice.ui.MessagesScreen
import dev.dialer.voice.data.SmsManager
import dev.dialer.voice.notifications.SmsNotifier
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.File

class MainActivity : ComponentActivity() {
    private var externalNumber by mutableStateOf<String?>(null)
    private var externalMessagePeer by mutableStateOf<String?>(null)
    private var themeMode by mutableStateOf(ThemeMode.System)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        themeMode = UiPreferences.theme(this)
        captureExternalNumber(intent)
        val showColdStart = savedInstanceState == null && !VoiceManager.activeCall.value.isLive
        val installedVersion = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        setContent {
            DialerTheme(themeMode) {
                val colors = MaterialTheme.colorScheme
                Box(
                    modifier = Modifier.fillMaxSize().background(
                        Brush.linearGradient(
                            0.0f to colors.background,
                            0.38f to colors.primaryContainer.copy(alpha = 0.34f),
                            0.7f to colors.secondary.copy(alpha = 0.14f),
                            1.0f to colors.background,
                        ),
                    ),
                ) {
                    CompositionLocalProvider(LocalContentColor provides colors.onBackground) {
                        var splashVisible by rememberSaveable { mutableStateOf(showColdStart) }
                        LaunchedEffect(splashVisible) {
                            if (splashVisible) {
                                delay(1_150)
                                splashVisible = false
                            }
                        }
                        if (splashVisible) {
                            DashPhoneSplash(version = installedVersion)
                        } else {
                            DialerRoot(
                                externalNumber = externalNumber,
                                onExternalNumberConsumed = { externalNumber = null },
                                externalMessagePeer = externalMessagePeer,
                                onExternalMessageConsumed = { externalMessagePeer = null },
                                themeMode = themeMode,
                                onThemeModeChange = { mode ->
                                    themeMode = mode
                                    UiPreferences.setTheme(this@MainActivity, mode)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureExternalNumber(intent)
    }

    private fun captureExternalNumber(intent: Intent?) {
        if (intent?.action == SmsNotifier.ACTION) {
            externalMessagePeer = intent.getStringExtra(SmsNotifier.PEER)
            return
        }
        if (intent?.action !in setOf(Intent.ACTION_DIAL, Intent.ACTION_VIEW, Intent.ACTION_SENDTO)) return
        if (intent?.data?.scheme != "tel") return
        externalNumber = intent.data?.schemeSpecificPart
    }
}

private enum class WorkspaceSection(val label: String) { Dialler("Dialler"), Recents("Recents"), Contacts("Contacts"), Messages("Messages") }

private data class CallPermissionStatus(
    val missingRuntimePermissions: List<String>,
    val notificationsBlocked: Boolean,
    val bluetoothMissing: Boolean,
    val telecomAccountRegistered: Boolean,
    val telecomAccountEnabled: Boolean,
) {
    val telecomAccountNeedsSetup: Boolean
        get() = !telecomAccountRegistered || !telecomAccountEnabled

    val issues: List<String>
        get() = buildList {
            if (!telecomAccountRegistered) {
                add("DashPhone could not register its Android calling account")
            } else if (!telecomAccountEnabled) {
                add("DashPhone is listed but switched off — tap DashPhone in Calling accounts and turn it on")
            }
            if (Manifest.permission.POST_NOTIFICATIONS in missingRuntimePermissions || notificationsBlocked) {
                add("Allow notifications so incoming calls can alert you")
            }
            if (Manifest.permission.CALL_PHONE in missingRuntimePermissions) add("Allow Phone access for Twilio system calls")
            if (Manifest.permission.RECORD_AUDIO in missingRuntimePermissions) add("Allow microphone access to make calls")
            if (bluetoothMissing) add("Allow Bluetooth access for headsets")
        }
}

private fun inspectCallPermissions(context: Context): CallPermissionStatus {
    val runtimePermissions = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    val missing = runtimePermissions.filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }
    val manager = context.getSystemService(NotificationManager::class.java)
    val telecomStatus = TelecomCallManager.accountStatus()
    return CallPermissionStatus(
        missingRuntimePermissions = missing,
        notificationsBlocked = !manager.areNotificationsEnabled(),
        bluetoothMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            Manifest.permission.BLUETOOTH_CONNECT in missing,
        // An unavailable status query is not evidence that the user disabled it.
        telecomAccountRegistered = telecomStatus.registered != false,
        telecomAccountEnabled = telecomStatus.enabled != false,
    )
}

@Composable
private fun DialerRoot(
    externalNumber: String?,
    onExternalNumberConsumed: () -> Unit,
    externalMessagePeer: String?,
    onExternalMessageConsumed: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val viewModel: DialerViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val call by VoiceManager.activeCall.collectAsStateWithLifecycle()
    val registration by VoiceManager.registration.collectAsStateWithLifecycle()
    val diagnostics by DiagnosticLog.entries.collectAsStateWithLifecycle()
    val smsState by SmsManager.state.collectAsStateWithLifecycle()
    val audioRoute by AudioRouter.audioRoute.collectAsStateWithLifecycle()
    val audioRoutes by AudioRouter.availableRoutes.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    var section by rememberSaveable { mutableStateOf(WorkspaceSection.Dialler) }
    var sidebarSection by rememberSaveable { mutableStateOf(WorkspaceSection.Recents) }
    var number by rememberSaveable { mutableStateOf("") }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var callPanelVisible by rememberSaveable { mutableStateOf(false) }
    var keypadVisible by rememberSaveable { mutableStateOf(false) }
    var pendingDialPlan by remember { mutableStateOf<DialPlan?>(null) }
    var callPermissionStatus by remember(context) { mutableStateOf(inspectCallPermissions(context)) }
    var contactsPermissionGranted by remember(context) {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var sidebarWidth by remember {
        mutableFloatStateOf(UiPreferences.sidebarWidth(context).coerceIn(280f, 420f))
    }
    val rootFocusRequester = remember { FocusRequester() }
    val activeContactName = remember(call.number, uiState.contacts) {
        val activeNumber = normalizeForDial(call.number)
        uiState.contacts.firstOrNull { normalizeForDial(it.number) == activeNumber }?.name
    }
    val dialSuggestions = remember(number, uiState.contacts) {
        if (number.length < 2) emptyList() else uiState.contacts.filter { it.matches(number) }.take(3)
    }

    val toneGenerator = remember { runCatching { ToneGenerator(AudioManager.STREAM_DTMF, 55) }.getOrNull() }
    DisposableEffect(Unit) {
        onDispose { toneGenerator?.release() }
    }
    fun playTone(digit: String) {
        val tone = when (digit) {
            "1" -> ToneGenerator.TONE_DTMF_1
            "2" -> ToneGenerator.TONE_DTMF_2
            "3" -> ToneGenerator.TONE_DTMF_3
            "4" -> ToneGenerator.TONE_DTMF_4
            "5" -> ToneGenerator.TONE_DTMF_5
            "6" -> ToneGenerator.TONE_DTMF_6
            "7" -> ToneGenerator.TONE_DTMF_7
            "8" -> ToneGenerator.TONE_DTMF_8
            "9" -> ToneGenerator.TONE_DTMF_9
            "0" -> ToneGenerator.TONE_DTMF_0
            "*" -> ToneGenerator.TONE_DTMF_S
            "#" -> ToneGenerator.TONE_DTMF_P
            else -> null
        }
        tone?.let { toneGenerator?.startTone(it, 90) }
    }

    val startupPermissionRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        callPermissionStatus = inspectCallPermissions(context)
    }
    val contactsPermissionRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        contactsPermissionGranted = granted
        if (granted) viewModel.loadContacts(context)
        else DiagnosticLog.warn("Contacts", "Contact permission not granted")
    }
    val callAudioPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val plan = pendingDialPlan
        pendingDialPlan = null
        val microphoneGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        val phoneGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        if (microphoneGranted && phoneGranted && plan != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                results[Manifest.permission.BLUETOOTH_CONNECT] == false
            ) {
                Toast.makeText(context, "Bluetooth unavailable; using phone audio", Toast.LENGTH_LONG).show()
            }
            VoiceManager.dial(plan.destination, plan.postDialDigits)
            callPanelVisible = true
            number = ""
        } else if (plan != null) {
            Toast.makeText(context, "Microphone and Phone permissions are required to call", Toast.LENGTH_LONG).show()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                callPermissionStatus = inspectCallPermissions(context)
                contactsPermissionGranted =
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                    PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        rootFocusRequester.requestFocus()
        val missing = callPermissionStatus.missingRuntimePermissions
        if (missing.isNotEmpty()) startupPermissionRequest.launch(missing.toTypedArray())
        if (uiState.config.isConfigured) viewModel.refreshHistory()
        if (contactsPermissionGranted) viewModel.loadContacts(context)
    }
    LaunchedEffect(externalNumber) {
        externalNumber?.let {
            number = cleanDialInput(it)
            section = WorkspaceSection.Dialler
            onExternalNumberConsumed()
        }
    }
    LaunchedEffect(uiState.config) { SmsManager.configure(uiState.config) }
    LaunchedEffect(externalMessagePeer) {
        if (externalMessagePeer != null) { section = WorkspaceSection.Messages; settingsOpen = false }
    }
    LaunchedEffect(call.state) {
        if (call.isLive || call.state == CallState.Failed) callPanelVisible = true
        if (call.state == CallState.Idle) {
            callPanelVisible = false
            keypadVisible = false
        }
    }

    fun requestDial(plan: DialPlan) {
        val permissions = buildList {
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            VoiceManager.dial(plan.destination, plan.postDialDigits)
            callPanelVisible = true
            number = ""
        } else {
            pendingDialPlan = plan
            callAudioPermissions.launch(missing.toTypedArray())
        }
    }

    fun dialEnteredNumber() {
        createDialPlan(number)?.let(::requestDial) ?: run {
            viewModel.showStatus("Enter an Australian number, or use +855 / +66")
            Toast.makeText(context, "Check the phone number", Toast.LENGTH_SHORT).show()
        }
    }

    val fixCallPermissions: () -> Unit = {
        callPermissionStatus = inspectCallPermissions(context)
        when {
            callPermissionStatus.missingRuntimePermissions.isNotEmpty() ->
                startupPermissionRequest.launch(callPermissionStatus.missingRuntimePermissions.toTypedArray())
            callPermissionStatus.telecomAccountNeedsSetup ->
                context.startActivity(
                    Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            callPermissionStatus.notificationsBlocked -> {
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData("package:${context.packageName}".toUri())
                }
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    val player = remember { MediaPlayer() }
    var playingSid by remember { mutableStateOf<String?>(null) }
    var playingFile by remember { mutableStateOf<File?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            player.release()
            playingFile?.delete()
        }
    }
    val playRecording: (CallRecord) -> Unit = { record ->
        if (playingSid != null) {
            runCatching { player.stop() }
            playingSid = null
            playingFile?.delete()
            playingFile = null
        } else {
            scope.launch {
                runCatching {
                    val sid = record.recordingSids.firstOrNull() ?: error("No recording for this call")
                    val file = File(context.cacheDir, "$sid.mp3")
                    sid to TwilioRest.downloadRecording(uiState.config, sid, file)
                }.onSuccess { (sid, file) ->
                    runCatching {
                        player.reset()
                        player.setDataSource(file.absolutePath)
                        player.setOnCompletionListener {
                            playingSid = null
                            playingFile?.delete()
                            playingFile = null
                        }
                        player.prepare()
                        player.start()
                        playingSid = sid
                        playingFile = file
                    }.onFailure {
                        file.delete()
                        viewModel.showStatus("Could not play recording: ${it.message}")
                    }
                }.onFailure {
                    viewModel.showStatus(it.message ?: "Playback failed")
                }
            }
        }
    }
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    fun copyNumber(value: String) {
        clipboard?.setPrimaryClip(ClipData.newPlainText("Phone number", value))
        Toast.makeText(context, "Number copied", Toast.LENGTH_SHORT).show()
    }
    val copyDiagnostics: () -> Unit = {
        val errors = buildList {
            if (call.state == CallState.Failed) add("Call failed: ${call.errorMessage ?: "Unknown error"}")
            (registration as? RegistrationState.Failed)?.let { add("Registration: ${it.message}") }
            uiState.statusMessage?.let(::add)
            uiState.historyError?.let(::add)
        }
        clipboard?.setPrimaryClip(ClipData.newPlainText("DashPhone diagnostics", DiagnosticLog.export(errors)))
        Toast.makeText(context, "Redacted diagnostics copied", Toast.LENGTH_SHORT).show()
    }

    val populateNumber: (String) -> Unit = { value ->
        number = cleanDialInput(value)
        section = WorkspaceSection.Dialler
    }
    val quickCall: (String) -> Unit = { value ->
        createDialPlan(value)?.let(::requestDial)
    }
    val appendDigit: (String) -> Unit = { digit ->
        if (digit.length == 1) playTone(digit)
        number = cleanDialInput(number + digit)
    }
    val sendDtmf: (String) -> Unit = { digit ->
        playTone(digit)
        VoiceManager.sendDtmf(digit)
    }

    val rootKeyHandler: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { event ->
        if (event.type != KeyEventType.KeyDown) {
            false
        } else if (settingsOpen) {
            if (event.key == Key.Escape) {
                settingsOpen = false
                true
            } else false
        } else if (section == WorkspaceSection.Messages) {
            false
        } else if (event.isCtrlPressed) {
            when {
                event.isShiftPressed && event.key == Key.H -> {
                    VoiceManager.hangup(); true
                }
                event.key == Key.M -> {
                    VoiceManager.toggleMute(); true
                }
                event.key == Key.L -> {
                    section = WorkspaceSection.Dialler; true
                }
                event.key == Key.F -> {
                    section = WorkspaceSection.Recents; sidebarSection = WorkspaceSection.Recents; true
                }
                event.key == Key.Comma -> {
                    settingsOpen = true; true
                }
                else -> false
            }
        } else {
            val character = event.nativeKeyEvent.unicodeChar.takeIf { it != 0 }?.toChar()?.toString()
            when {
                character in listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "#") -> {
                    if (call.state == CallState.Connected) sendDtmf(character!!)
                    else appendDigit(character!!)
                    true
                }
                event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                    if (!call.isLive) dialEnteredNumber()
                    true
                }
                event.key == Key.Backspace -> {
                    if (number.isNotEmpty()) number = number.dropLast(1)
                    true
                }
                event.key == Key.Escape -> {
                    keypadVisible = false
                    callPanelVisible = false
                    true
                }
                else -> false
            }
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
            .focusRequester(rootFocusRequester).onPreviewKeyEvent(rootKeyHandler).focusable(),
    ) {
        val compact = maxWidth < 600.dp
        val expanded = maxWidth >= 1_000.dp
        val compactCallOnly = compact && callPanelVisible && call.state != CallState.Idle
        Column(Modifier.fillMaxSize()) {
            if (!compactCallOnly) {
                AppHeader(registration = registration, onSettings = { settingsOpen = true },
                    showMessagesAction = !compact, messagesSelected = section == WorkspaceSection.Messages,
                    onMessages = { section = if (section == WorkspaceSection.Messages) WorkspaceSection.Dialler else WorkspaceSection.Messages })
                HorizontalDivider()
            }
            Box(Modifier.weight(1f)) {
            if (compact) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        when {
                            callPanelVisible && call.state != CallState.Idle -> ActiveCallPanel(
                                call = call,
                                contactName = activeContactName,
                                audioRoute = audioRoute,
                                audioRoutes = audioRoutes,
                                compact = true,
                                keypadVisible = keypadVisible,
                                onKeypad = { keypadVisible = !keypadVisible },
                                onDtmf = sendDtmf,
                                onDismiss = { callPanelVisible = false },
                                onCopyDiagnostics = copyDiagnostics,
                            )
                            section == WorkspaceSection.Dialler -> DialerScreen(
                                number = number,
                                suggestions = dialSuggestions,
                                callerId = uiState.config.phoneNumber,
                                registration = registration,
                                permissionIssues = callPermissionStatus.issues,
                                showHeader = true,
                                onFixPermissions = fixCallPermissions,
                                onNumberChange = { number = cleanDialInput(it) },
                                onPaste = {
                                    clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let {
                                        number = cleanDialInput(it)
                                    }
                                },
                                onDigit = appendDigit,
                                onBackspace = { if (number.isNotEmpty()) number = number.dropLast(1) },
                                onClear = { number = "" },
                                onDial = ::dialEnteredNumber,
                            )
                            section == WorkspaceSection.Messages -> MessagesScreen(
                                contacts = uiState.contacts, externalPeer = externalMessagePeer,
                                onExternalPeerConsumed = onExternalMessageConsumed,
                            )
                            section == WorkspaceSection.Recents -> HistoryScreen(
                                calls = uiState.history,
                                contacts = uiState.contacts,
                                loading = uiState.historyLoading,
                                error = uiState.historyError,
                                compact = true,
                                onRefresh = viewModel::refreshHistory,
                                onPopulate = populateNumber,
                                onCall = quickCall,
                                onCopy = ::copyNumber,
                                onPlay = playRecording,
                            )
                            else -> ContactsScreen(
                                contacts = uiState.contacts,
                                permissionGranted = contactsPermissionGranted,
                                loading = uiState.contactsLoading,
                                error = uiState.contactsError,
                                compact = true,
                                onRequestPermission = { contactsPermissionRequest.launch(Manifest.permission.READ_CONTACTS) },
                                onPopulate = populateNumber,
                                onCall = quickCall,
                            )
                        }
                    }
                    if (!compactCallOnly) {
                        NavigationBar {
                            listOf(WorkspaceSection.Dialler, WorkspaceSection.Contacts, WorkspaceSection.Messages).forEach { item ->
                                val icon = when (item) {
                                    WorkspaceSection.Dialler -> Icons.Default.Dialpad
                                    WorkspaceSection.Recents -> Icons.Default.History
                                    WorkspaceSection.Contacts -> Icons.Default.Contacts
                                    WorkspaceSection.Messages -> Icons.AutoMirrored.Filled.Message
                                }
                                NavigationBarItem(
                                    selected = section == item,
                                    onClick = {
                                        section = item
                                        callPanelVisible = false
                                        if (item == WorkspaceSection.Recents) viewModel.refreshHistory()
                                        if (item == WorkspaceSection.Contacts && contactsPermissionGranted) {
                                            viewModel.loadContacts(context)
                                        }
                                    },
                                    icon = { Icon(icon, contentDescription = item.label) },
                                    label = { Text(if (item == WorkspaceSection.Messages && smsState.unread.isNotEmpty()) "Messages (${smsState.unread.size})" else item.label) },
                                )
                            }
                        }
                    }
                }
            } else if (section == WorkspaceSection.Messages) {
                Row(Modifier.fillMaxSize()) {
                    MessagesScreen(contacts = uiState.contacts, modifier = Modifier.weight(1f),
                        externalPeer = externalMessagePeer, onExternalPeerConsumed = onExternalMessageConsumed)
                    if (callPanelVisible && call.state != CallState.Idle) {
                        ActiveCallPanel(call = call, contactName = activeContactName, audioRoute = audioRoute,
                            audioRoutes = audioRoutes, compact = false, keypadVisible = keypadVisible,
                            onKeypad = { keypadVisible = !keypadVisible }, onDtmf = sendDtmf,
                            onDismiss = { callPanelVisible = false }, onCopyDiagnostics = copyDiagnostics,
                            modifier = Modifier.weight(1f))
                    }
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    SidebarPane(
                        modifier = if (expanded) Modifier.weight(1f) else Modifier.width(sidebarWidth.dp),
                        section = sidebarSection,
                        onSectionChange = {
                            sidebarSection = it
                            if (it == WorkspaceSection.Recents) viewModel.refreshHistory()
                            if (it == WorkspaceSection.Contacts && contactsPermissionGranted) viewModel.loadContacts(context)
                        },
                        calls = uiState.history,
                        contacts = uiState.contacts,
                        historyLoading = uiState.historyLoading,
                        historyError = uiState.historyError,
                        contactsLoading = uiState.contactsLoading,
                        contactsError = uiState.contactsError,
                        contactsPermissionGranted = contactsPermissionGranted,
                        onRequestContacts = { contactsPermissionRequest.launch(Manifest.permission.READ_CONTACTS) },
                        onRefresh = viewModel::refreshHistory,
                        onPopulate = populateNumber,
                        onCall = quickCall,
                        onCopy = ::copyNumber,
                        onPlay = playRecording,
                    )
                    if (expanded) {
                        Surface(Modifier.width(1.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.outline) {}
                    } else {
                        Box(
                            Modifier.width(8.dp).fillMaxHeight().pointerInput(Unit) {
                                detectHorizontalDragGestures(
                                    onDragEnd = { UiPreferences.setSidebarWidth(context, sidebarWidth) },
                                ) { change, dragAmount ->
                                    change.consume()
                                    sidebarWidth = (sidebarWidth + with(density) { dragAmount.toDp().value }).coerceIn(280f, 420f)
                                }
                            },
                        ) {
                            Surface(Modifier.width(1.dp).fillMaxHeight().align(Alignment.Center), color = MaterialTheme.colorScheme.outline) {}
                        }
                    }
                    if (!expanded && callPanelVisible && call.state != CallState.Idle) {
                        ActiveCallPanel(
                            call = call,
                            contactName = activeContactName,
                            audioRoute = audioRoute,
                            audioRoutes = audioRoutes,
                            compact = false,
                            keypadVisible = keypadVisible,
                            onKeypad = { keypadVisible = !keypadVisible },
                            onDtmf = sendDtmf,
                            onDismiss = { callPanelVisible = false },
                            onCopyDiagnostics = copyDiagnostics,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        DialerScreen(
                            number = number,
                            suggestions = dialSuggestions,
                            callerId = uiState.config.phoneNumber,
                            registration = registration,
                            permissionIssues = callPermissionStatus.issues,
                            showHeader = false,
                            onFixPermissions = fixCallPermissions,
                            onNumberChange = { number = cleanDialInput(it) },
                            onPaste = {
                                clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let {
                                    number = cleanDialInput(it)
                                }
                            },
                            onDigit = appendDigit,
                            onBackspace = { if (number.isNotEmpty()) number = number.dropLast(1) },
                            onClear = { number = "" },
                            onDial = ::dialEnteredNumber,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (expanded && callPanelVisible && call.state != CallState.Idle) {
                        Surface(Modifier.width(1.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.outline) {}
                        ActiveCallPanel(
                            call = call,
                            contactName = activeContactName,
                            audioRoute = audioRoute,
                            audioRoutes = audioRoutes,
                            compact = false,
                            keypadVisible = keypadVisible,
                            onKeypad = { keypadVisible = !keypadVisible },
                            onDtmf = sendDtmf,
                            onDismiss = { callPanelVisible = false },
                            onCopyDiagnostics = copyDiagnostics,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
    }

    if (settingsOpen) {
        Dialog(
            onDismissRequest = { settingsOpen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.94f).widthIn(max = 760.dp),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 8.dp,
            ) {
                SettingsScreen(
                    config = uiState.config,
                    registration = registration,
                    busy = uiState.settingsBusy,
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    onClose = { settingsOpen = false },
                    testResult = uiState.statusMessage,
                    diagnostics = diagnostics,
                    onCopyDiagnostics = copyDiagnostics,
                    onSave = viewModel::saveConfig,
                    onTest = viewModel::testConfig,
                    testCallCountdownSeconds = uiState.testCallCountdownSeconds,
                    onScheduleTestCall = viewModel::scheduleIncomingTestCall,
                )
            }
        }
    }
}

@Composable
private fun AppHeader(registration: RegistrationState, onSettings: () -> Unit,
    showMessagesAction: Boolean, messagesSelected: Boolean, onMessages: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("DashPhone", style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text("Twilio voice & SMS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (showMessagesAction) FilterChip(selected = messagesSelected, onClick = onMessages,
                label = { Text(if (messagesSelected) "Back to dialler" else "Messages") })
            RegistrationStatusChip(registration)
            IconButton(onClick = onSettings, modifier = Modifier.padding(start = 6.dp)) {
                Icon(Icons.Default.Settings, contentDescription = "Settings (Ctrl+,)")
            }
        }
    }
}

@Composable
private fun ActiveCallPanel(
    call: ActiveCall,
    contactName: String?,
    audioRoute: String,
    audioRoutes: List<AudioRouter.Route>,
    compact: Boolean,
    keypadVisible: Boolean,
    onKeypad: () -> Unit,
    onDtmf: (String) -> Unit,
    onDismiss: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        if (call.state == CallState.Incoming) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(contactName ?: call.number, style = MaterialTheme.typography.headlineMedium)
                Text("Answer or decline using the system call screen")
            }
        } else {
            CallScreen(
                call = call,
                contactName = contactName,
                audioRoute = audioRoute,
                availableRoutes = audioRoutes,
                compact = compact,
                keypadVisible = keypadVisible,
                onKeypad = onKeypad,
                onMute = VoiceManager::toggleMute,
                onHold = VoiceManager::toggleHold,
                onSpeaker = VoiceManager::toggleSpeaker,
                onSelectAudioRoute = AudioRouter::selectRoute,
                onDtmf = onDtmf,
                onHangup = VoiceManager::hangup,
                onDismiss = onDismiss,
                onCopyDiagnostics = onCopyDiagnostics,
            )
        }
    }
}

@Composable
private fun SidebarPane(
    modifier: Modifier,
    section: WorkspaceSection,
    onSectionChange: (WorkspaceSection) -> Unit,
    calls: List<CallRecord>,
    contacts: List<DeviceContact>,
    historyLoading: Boolean,
    historyError: String?,
    contactsLoading: Boolean,
    contactsError: String?,
    contactsPermissionGranted: Boolean,
    onRequestContacts: () -> Unit,
    onRefresh: () -> Unit,
    onPopulate: (String) -> Unit,
    onCall: (String) -> Unit,
    onCopy: (String) -> Unit,
    onPlay: (CallRecord) -> Unit,
) {
    Column(modifier.fillMaxHeight()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(WorkspaceSection.Recents, WorkspaceSection.Contacts).forEach { item ->
                FilterChip(
                    selected = section == item,
                    onClick = { onSectionChange(item) },
                    label = { Text(item.label) },
                )
            }
        }
        when (section) {
            WorkspaceSection.Contacts -> ContactsScreen(
                contacts = contacts,
                permissionGranted = contactsPermissionGranted,
                loading = contactsLoading,
                error = contactsError,
                compact = false,
                onRequestPermission = onRequestContacts,
                onPopulate = onPopulate,
                onCall = onCall,
            )
            else -> HistoryScreen(
                calls = calls,
                contacts = contacts,
                loading = historyLoading,
                error = historyError,
                compact = false,
                onRefresh = onRefresh,
                onPopulate = onPopulate,
                onCall = onCall,
                onCopy = onCopy,
                onPlay = onPlay,
            )
        }
    }
}
