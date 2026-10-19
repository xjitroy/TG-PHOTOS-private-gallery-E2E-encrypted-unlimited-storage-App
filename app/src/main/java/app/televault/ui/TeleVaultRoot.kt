package app.televault.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.*
import app.televault.PhoneCountries
import app.televault.R
import app.televault.domain.AuthProtocol
import app.televault.telegram.AuthSnapshot
import app.televault.telegram.SetupIssue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TeleVaultRoot(
    auth: AuthSnapshot,
    submit: (AuthProtocol.Action, String, String) -> Unit,
    dynamicColor: Boolean,
    changeDynamicColor: suspend (Boolean) -> Unit,
    phoneCountries: PhoneCountries = PhoneCountries(),
    preparePhoneCountries: () -> Unit = {},
    authenticatedContent: @Composable () -> Unit = { Workspace(auth, submit, dynamicColor, changeDynamicColor) }
) {
    var started by rememberSaveable { mutableStateOf(false) }
    AppSurface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (app.televault.domain.AuthRestorePolicy.screen(auth.stage, auth.previouslyAuthorized, started)) {
            app.televault.domain.AuthRestorePolicy.Screen.CONTENT -> authenticatedContent()
            app.televault.domain.AuthRestorePolicy.Screen.WELCOME -> Welcome { started = true }
            app.televault.domain.AuthRestorePolicy.Screen.LOGIN -> Login(auth, submit, phoneCountries, preparePhoneCountries)
            app.televault.domain.AuthRestorePolicy.Screen.RESTORING -> if (auth.error == null) {
                QuietStartupGate()
            } else {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(label("Connecting to your saved session…", "সংরক্ষিত সেশনে সংযোগ হচ্ছে…"), style = MaterialTheme.typography.titleMedium)
                    Text(label("Wait for Telegram to restore its saved session before requesting another code. Do not clear app data.", "নতুন কোড চাওয়ার আগে Telegram-এর সেশন ফিরে আসা পর্যন্ত অপেক্ষা করুন। অ্যাপের ডাটা ক্লিয়ার করবেন না।"), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    if (auth.error != null) {
                        val context = LocalContext.current
                        val retryScope = rememberCoroutineScope()
                        TextButton({ retryScope.launch { runCatching { (context.applicationContext as app.televault.TeleVaultApplication).telegram.reconnect() } } }) { Text(label("Retry connection", "সংযোগ আবার চেষ্টা করুন")) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Welcome(connect: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Spacer(Modifier.height(24.dp))
            Box(Modifier.size(160.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Cloud, null, Modifier.size(148.dp), tint = MaterialTheme.colorScheme.primary)
                AppSurface(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Icon(Icons.Outlined.Lock, null, Modifier.padding(18.dp).size(48.dp), tint = MaterialTheme.colorScheme.tertiary)
                }
            }
            Text("TG Photos", style = MaterialTheme.typography.headlineLarge)
            Text(label("Your private cloud gallery", "আপনার ব্যক্তিগত ক্লাউড গ্যালারি"), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(Icons.Outlined.Photo, Icons.Outlined.PlayCircle, Icons.Outlined.Description, Icons.Outlined.MusicNote).forEach { icon ->
                    AppSurface(shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) { Icon(icon, null, Modifier.padding(14.dp), tint = MaterialTheme.colorScheme.primary) }
                }
            }
            Text(stringResource(R.string.disclosure), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.encryption_disclosure), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.capacity_disclosure), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(connect, Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(stringResource(R.string.connect))
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Outlined.ArrowForward, null)
        }
    }
}

@Composable
private fun InformationCard(title: Int, body: Int, icon: ImageVector) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Login(auth: AuthSnapshot, submit: (AuthProtocol.Action, String, String) -> Unit, phoneCountries: PhoneCountries, preparePhoneCountries: () -> Unit) {
    LaunchedEffect(auth.stage) { if (auth.stage == AuthProtocol.State.PHONE) preparePhoneCountries() }
    val context = LocalContext.current
    var codePulse by remember(auth.stage) { mutableStateOf(false) }
    val codeAlpha by androidx.compose.animation.core.animateFloatAsState(if (codePulse) .68f else 1f, androidx.compose.animation.core.tween(600), label = "Telegram code attention")
    LaunchedEffect(auth.stage) {
        if (auth.stage == AuthProtocol.State.CODE && auth.delivery == AuthProtocol.CodeDelivery.TELEGRAM && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            repeat(3) {
                codePulse = true
                delay(700)
                codePulse = false
                delay(900)
            }
        }
    }
    var linkUnavailable by remember { mutableStateOf(false) }
    // Secrets are deliberately excluded from SavedStateHandle/rememberSaveable.
    var input by remember(auth.stage) { mutableStateOf("") }
    var lastName by remember(auth.stage) { mutableStateOf("") }
    var agreed by remember(auth.stage) { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(auth.retryAtElapsedMillis, auth.resendAtElapsedMillis) {
        now = SystemClock.elapsedRealtime()
        while (now < maxOf(auth.retryAtElapsedMillis, auth.resendAtElapsedMillis)) {
            delay(1000)
            now = SystemClock.elapsedRealtime()
        }
    }
    var changingPhone by remember(auth.stage) { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(changingPhone) {
        changingPhone = false
        input = ""
    }
    val phoneEntry = auth.stage == AuthProtocol.State.PHONE || changingPhone
    val action = if (changingPhone) {
        AuthProtocol.Action.PHONE
    } else {
        when (auth.stage) {
            AuthProtocol.State.PHONE -> AuthProtocol.Action.PHONE
            AuthProtocol.State.CODE -> AuthProtocol.Action.CODE
            AuthProtocol.State.PASSWORD -> AuthProtocol.Action.PASSWORD
            AuthProtocol.State.EMAIL -> AuthProtocol.Action.EMAIL
            AuthProtocol.State.EMAIL_CODE -> AuthProtocol.Action.EMAIL_CODE
            AuthProtocol.State.REGISTRATION -> AuthProtocol.Action.REGISTER
            else -> null
        }
    }
    val label = when (auth.stage) {
        AuthProtocol.State.PHONE -> R.string.phone
        AuthProtocol.State.CODE -> R.string.telegram_code
        AuthProtocol.State.PASSWORD -> R.string.telegram_password
        AuthProtocol.State.EMAIL -> R.string.email
        AuthProtocol.State.EMAIL_CODE -> R.string.email_code
        AuthProtocol.State.REGISTRATION -> R.string.first_name
        else -> R.string.auth_waiting
    }
    val secret = auth.stage in setOf(AuthProtocol.State.PASSWORD, AuthProtocol.State.CODE, AuthProtocol.State.EMAIL_CODE)
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
                AppSurface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Outlined.Send, null, Modifier.padding(20.dp).size(36.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Text(if (auth.stage == AuthProtocol.State.CODE) label("Verification code", "ভেরিফিকেশন কোড") else stringResource(R.string.login_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.login_disclosure), style = MaterialTheme.typography.bodySmall)
            if (auth.previouslyAuthorized && auth.stage == AuthProtocol.State.PHONE) Text(label("Telegram is requesting sign-in again. Your local encrypted library has not been reset. Sign in to the same account; do not clear app data.", "Telegram আবার লগইন চাইছে। লোকাল এনক্রিপ্টেড লাইব্রেরি রিসেট হয়নি। একই অ্যাকাউন্টে লগইন করুন; অ্যাপের ডাটা ক্লিয়ার করবেন না।"), style = MaterialTheme.typography.bodyMedium)
            if (auth.previouslyAuthorized && auth.stage in setOf(AuthProtocol.State.PHONE, AuthProtocol.State.SETUP_REQUIRED)) {
                val sessionInfo by produceState("") { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { (context.applicationContext as app.televault.TeleVaultApplication).telegram.sessionDiagnostics() + " · " + app.televault.RuntimeDiagnostics.summary(context) } }
                Text(sessionInfo, style = MaterialTheme.typography.labelSmall)
            }
            if (auth.stage == AuthProtocol.State.CLOSED || auth.stage == AuthProtocol.State.SETUP_REQUIRED && auth.setupIssue == SetupIssue.DEVICE_KEY || auth.stage == AuthProtocol.State.PARAMETERS && auth.error != null) {
                val retryScope = rememberCoroutineScope()
                TextButton({ retryScope.launch { runCatching { (context.applicationContext as app.televault.TeleVaultApplication).telegram.reconnect() } } }) { Text(label("Retry connection", "সংযোগ আবার চেষ্টা করুন")) }
            }
            when (auth.stage) {
                AuthProtocol.State.SETUP_REQUIRED -> InformationCard(
                    R.string.setup_title,
                    when (auth.setupIssue) {
                        SetupIssue.CREDENTIALS -> R.string.missing_credentials
                        SetupIssue.NATIVE_LIBRARY -> R.string.missing_native
                        else -> R.string.device_key_issue
                    },
                    Icons.Outlined.Settings
                )
                AuthProtocol.State.OTHER_DEVICE -> {
                    Text(stringResource(R.string.confirm_other_device))
                    SelectionContainerLink(auth.confirmationLink)
                }
                AuthProtocol.State.PREMIUM_REQUIRED -> Text(stringResource(R.string.premium_required))
                AuthProtocol.State.UNSUPPORTED -> Text(stringResource(R.string.unsupported_auth))
                AuthProtocol.State.CLOSED -> Text(stringResource(R.string.session_closed))
                AuthProtocol.State.REGISTRATION -> {
                    Text(auth.terms)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(agreed, { agreed = it })
                        Text(stringResource(R.string.accept_terms), Modifier.weight(1f))
                    }
                }
                else -> Unit
            }
            if (auth.stage != AuthProtocol.State.PHONE && AuthProtocol.permits(auth.stage, AuthProtocol.Action.PHONE)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (auth.phoneNumber.isNotBlank()) Text(auth.phoneNumber, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton({
                        changingPhone = !changingPhone
                        input = ""
                    }, enabled = !auth.busy) {
                        Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp))
                        Text(if (changingPhone) label("Back to code", "কোডে ফিরুন") else label("Edit number", "নম্বর বদলান"))
                    }
                }
                if (changingPhone) Text(label("Enter the correct number. Telegram will send a new code; the previous code will not sign in to this number.", "সঠিক নম্বর দিন। Telegram নতুন কোড পাঠাবে; আগের কোড দিয়ে এই নম্বরে লগইন হবে না।"), style = MaterialTheme.typography.bodySmall)
            }
            if (auth.stage == AuthProtocol.State.CODE && !changingPhone) {
                Text(codeDeliveryName(auth.delivery))
                if (auth.delivery == AuthProtocol.CodeDelivery.FLASH_CALL && auth.callPattern.isNotBlank()) Text(label("Caller number pattern: ", "কল করা নম্বরের প্যাটার্ন: ") + auth.callPattern, style = MaterialTheme.typography.bodySmall)
                if (auth.delivery == AuthProtocol.CodeDelivery.MISSED_CALL) Text(label("Enter the last ${auth.codeLength} digits of the caller number beginning with ", "কল করা নম্বরের শেষ ${auth.codeLength}টি সংখ্যা দিন; নম্বর শুরু হবে ") + auth.callPrefix, style = MaterialTheme.typography.bodySmall)
                if (auth.delivery == AuthProtocol.CodeDelivery.TELEGRAM) {
                    TextButton({
                        codePulse = false
                        linkUnavailable = !openTelegramCodeChat(context)
                    }, Modifier.graphicsLayer { alpha = codeAlpha }) { Text(label("View code in Telegram", "Telegram-এ কোড দেখুন")) }
                    Text(label("Opens Telegram's code chat. If Telegram is not installed, opens Play Store. Enter the received code here yourself.", "Telegram-এর কোডের চ্যাট খুলবে। ইনস্টল না থাকলে Play Store খুলবে। পাওয়া কোড নিজে এখানে দিন।"), style = MaterialTheme.typography.bodySmall)
                    if (linkUnavailable) Text(label("Could not open Telegram or Play Store on this device.", "এই ডিভাইসে Telegram বা Play Store খোলা যায়নি।"), color = MaterialTheme.colorScheme.error)
                }
                val available = AuthProtocol.canResend(auth.nextDelivery, now, auth.resendAtElapsedMillis, auth.retryAtElapsedMillis, auth.busy)
                if (auth.nextDelivery != AuthProtocol.CodeDelivery.NONE && auth.nextDelivery != AuthProtocol.CodeDelivery.UNSUPPORTED) {
                    val wait = ((maxOf(auth.resendAtElapsedMillis, auth.retryAtElapsedMillis) - now).coerceAtLeast(0) + 999) / 1000
                    OutlinedButton({ submit(AuthProtocol.Action.RESEND_CODE, "", "") }, enabled = available) {
                        Text(label("Request next code: ", "পরবর্তী কোড চান: ") + codeDeliveryName(auth.nextDelivery) + if (wait > 0) " (${wait}s)" else "")
                    }
                } else {
                    Text(label("Telegram has not offered an SMS/call alternative. Check your Telegram app. Delivery is chosen by Telegram.", "Telegram এখনও SMS/কলের বিকল্প দেয়নি। Telegram অ্যাপে কোড দেখুন। পাঠানোর পদ্ধতি Telegram ঠিক করে।"))
                }
            }
            if (action != null) {
                if (phoneEntry) {
                    PhoneCountryField(phoneCountries, !auth.busy, preparePhoneCountries) { input = it }
                } else {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(label)) },
                        enabled = !auth.busy,
                        singleLine = true,
                        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = when (auth.stage) {
                                AuthProtocol.State.PHONE -> KeyboardType.Phone
                                AuthProtocol.State.EMAIL -> KeyboardType.Email
                                AuthProtocol.State.PASSWORD -> KeyboardType.Password
                                AuthProtocol.State.CODE -> if (auth.delivery == AuthProtocol.CodeDelivery.FLASH_CALL) {
                                    KeyboardType.Phone
                                } else if (auth.delivery in setOf(AuthProtocol.CodeDelivery.SMS_WORD, AuthProtocol.CodeDelivery.SMS_PHRASE)) {
                                    KeyboardType.Password
                                } else {
                                    KeyboardType.NumberPassword
                                }
                                AuthProtocol.State.EMAIL_CODE -> KeyboardType.NumberPassword
                                else -> KeyboardType.Text
                            }
                        )
                    )
                }
                if (auth.stage == AuthProtocol.State.REGISTRATION) {
                    OutlinedTextField(lastName, { lastName = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.last_name)) }, singleLine = true)
                }
            }
            if (auth.busy || auth.stage == AuthProtocol.State.STARTING || auth.stage == AuthProtocol.State.PARAMETERS) {
                SecureLoader(Modifier.size(32.dp))
                Text(stringResource(R.string.auth_waiting))
            }
            auth.error?.let { error ->
                Text(
                    stringResource(
                        when (error.kind()) {
                            AuthProtocol.Failure.INVALID_INPUT -> R.string.invalid_input
                            AuthProtocol.Failure.FLOOD_WAIT -> R.string.flood_wait
                            AuthProtocol.Failure.NETWORK -> R.string.network_error
                            AuthProtocol.Failure.TIMEOUT -> R.string.timeout_error
                            else -> R.string.request_error
                        }
                    ),
                    color = MaterialTheme.colorScheme.error
                )
                if (error.kind() == AuthProtocol.Failure.FLOOD_WAIT) {
                    Text(stringResource(R.string.wait_seconds, ((auth.retryAtElapsedMillis - now).coerceAtLeast(0) / 1000)))
                }
            }
        }
        if (action != null) {
            Button(
                onClick = {
                    submit(action, input, lastName)
                    input = ""
                    lastName = ""
                    changingPhone = false
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).heightIn(min = 48.dp),
                enabled = !auth.busy && input.isNotBlank() && now >= auth.retryAtElapsedMillis && (action != AuthProtocol.Action.REGISTER || agreed)
            ) { Text(stringResource(R.string.continue_action)) }
        }
    }
}

@Composable
private fun codeDeliveryName(delivery: AuthProtocol.CodeDelivery): String = when (delivery) {
    AuthProtocol.CodeDelivery.TELEGRAM -> label("Code in Telegram", "Telegram-এ কোড")
    AuthProtocol.CodeDelivery.SMS -> label("SMS code", "SMS কোড")
    AuthProtocol.CodeDelivery.CALL -> label("Phone call code", "ফোন কলে কোড")
    AuthProtocol.CodeDelivery.FLASH_CALL -> label("Flash call · enter the calling number", "Flash call · কল করা নম্বর লিখুন")
    AuthProtocol.CodeDelivery.MISSED_CALL -> label("Missed call · enter the number's final digits", "Missed call · নম্বরের শেষের সংখ্যাগুলি লিখুন")
    AuthProtocol.CodeDelivery.SMS_WORD -> label("Word received by SMS", "SMS-এ পাওয়া শব্দ")
    AuthProtocol.CodeDelivery.SMS_PHRASE -> label("Phrase received by SMS", "SMS-এ পাওয়া বাক্য")
    AuthProtocol.CodeDelivery.FRAGMENT -> label("Code delivered through Fragment", "Fragment-এর মাধ্যমে পাঠানো কোড")
    else -> label("Authentication code", "ভেরিফিকেশন কোড")
}

@Composable
private fun SelectionContainerLink(link: String) {
    // Display/select only; never automatically launch an untrusted server-provided URI.
    androidx.compose.foundation.text.selection.SelectionContainer { Text(link) }
}

private enum class Tab(val title: Int, val icon: ImageVector) {
    HOME(R.string.home, Icons.Outlined.Home),
    FILES(R.string.files, Icons.Outlined.Folder),
    GALLERY(R.string.gallery, Icons.Outlined.PhotoLibrary),
    TRANSFERS(R.string.transfers, Icons.Outlined.SwapVert),
    SETTINGS(R.string.settings, Icons.Outlined.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Workspace(auth: AuthSnapshot, submit: (AuthProtocol.Action, String, String) -> Unit, dynamicColor: Boolean, changeDynamicColor: suspend (Boolean) -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val selected = Tab.entries.find { it.name == entry?.destination?.route } ?: Tab.HOME
    var logout by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val choose: (Tab) -> Unit = { tab ->
        nav.navigate(tab.name) {
            popUpTo(nav.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 600.dp
        Scaffold(
            topBar = { TopAppBar(title = { Text(stringResource(selected.title)) }) },
            bottomBar = {
                if (!expanded) {
                    NavigationBar {
                        Tab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = selected == tab,
                                onClick = { choose(tab) },
                                icon = { Icon(tab.icon, stringResource(tab.title)) },
                                label = { Text(stringResource(tab.title)) }
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Row(Modifier.padding(padding).consumeWindowInsets(padding)) {
                if (expanded) {
                    NavigationRail {
                        Tab.entries.forEach { tab ->
                            NavigationRailItem(
                                selected = selected == tab,
                                onClick = { choose(tab) },
                                icon = { Icon(tab.icon, stringResource(tab.title)) },
                                label = { Text(stringResource(tab.title)) }
                            )
                        }
                    }
                }
                NavHost(nav, Tab.HOME.name, Modifier.weight(1f)) {
                    Tab.entries.forEach { tab ->
                        composable(tab.name) {
                            Column(
                                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(24.dp)
                            ) {
                                InformationCard(R.string.connected, R.string.vault_pending, Icons.Outlined.Lock)
                                Text(stringResource(R.string.phase_status), style = MaterialTheme.typography.bodyMedium)
                                if (tab == Tab.SETTINGS) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(stringResource(R.string.dynamic_color), Modifier.weight(1f))
                                        Switch(dynamicColor, { scope.launch { changeDynamicColor(it) } })
                                    }
                                    Text(stringResource(R.string.language_system))
                                    OutlinedButton({ logout = true }, enabled = !auth.busy) { Text(stringResource(R.string.logout)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (logout) {
        AlertDialog(
            onDismissRequest = { logout = false },
            title = { Text(stringResource(R.string.logout)) },
            text = { Text(stringResource(R.string.logout_warning)) },
            confirmButton = {
                TextButton({
                    logout = false
                    submit(AuthProtocol.Action.LOGOUT, "", "")
                }) { Text(stringResource(R.string.logout)) }
            },
            dismissButton = { TextButton({ logout = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun WelcomePreview() {
    TeleVaultTheme(false) { Welcome {} }
}

@Preview(showBackground = true, locale = "bn", widthDp = 360, heightDp = 800, fontScale = 2f)
@Composable
private fun MissingSetupBengaliPreview() {
    TeleVaultTheme(false) { Login(AuthSnapshot(AuthProtocol.State.SETUP_REQUIRED, setupIssue = SetupIssue.CREDENTIALS), { _, _, _ -> }, PhoneCountries(), {}) }
}
