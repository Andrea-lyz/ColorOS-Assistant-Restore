package io.github.andrea_lyz.assistrestore.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Help
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsVoice
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.andrea_lyz.assistrestore.AssistConfig
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/* --------------------------------------------------------------------------------------------- */
/* Preview data                                                                                   */
/* --------------------------------------------------------------------------------------------- */

private val ENTRY_NAMES = listOf("长按电源键", "长按手势条", "底角内滑")

/** Entry identifiers as stored in the configuration, in the same order as [ENTRY_NAMES]. */
/** The OEM assistant: it declares no voice interaction service, so it is started as an activity. */
private const val BREENO_PACKAGE = "com.heytap.speechassist"

private val ENTRY_IDS = listOf(
    AssistConfig.ENTRY_POWER,
    AssistConfig.ENTRY_HANDLE,
    AssistConfig.ENTRY_CORNER,
)

private val ENTRY_ICONS = listOf(
    Icons.Rounded.PowerSettingsNew,
    Icons.Rounded.Gesture,
    Icons.Rounded.Swipe,
)

/** What one entry point wakes. Exactly one of these is active per entry. */
/** Reads the three entries' targets out of the stored configuration. */
private fun loadChoices(store: SettingsStore): List<TargetChoice> = ENTRY_IDS.map { entry ->
    when (store.mode(entry)) {
        AssistConfig.MODE_CTS -> TargetChoice.CircleToSearch
        AssistConfig.MODE_APP -> TargetChoice.App(store.targetPackage(entry))
        AssistConfig.MODE_CUSTOM -> TargetChoice.Custom(store.targetPackage(entry))
        AssistConfig.MODE_OEM -> TargetChoice.Oem
        AssistConfig.MODE_NONE -> TargetChoice.None
        else -> TargetChoice.FollowDefault
    }
}

/**
 * Single choice that also allows "everything off": turning the active row off means the entry wakes
 * nothing, the OEM invocation included.
 */
private fun toggleChoice(
    on: Boolean,
    target: TargetChoice,
    onSelectChoice: (TargetChoice) -> Unit,
) {
    onSelectChoice(if (on) target else TargetChoice.None)
}

/** The four things the paste box understands. */
private data class PastedIntent(
    val action: String = "",
    val packageName: String = "",
    val className: String = "",
    val category: String = "",
    val extra: String = "",
)

/**
 * Reads the {@code {"action": "...", "packageName": "...", "className": "..."}} form used by app
 * inspection tools. Deliberately lenient: unknown keys are ignored and missing values are dropped.
 */
private fun parseIntentJson(text: String): PastedIntent? {
    val values = LinkedHashMap<String, String>()
    var index = 0
    while (true) {
        val keyStart = text.indexOf('"', index)
        if (keyStart < 0) break
        val keyEnd = text.indexOf('"', keyStart + 1)
        if (keyEnd < 0) break
        val key = text.substring(keyStart + 1, keyEnd)
        val colon = text.indexOf(':', keyEnd)
        if (colon < 0) break
        var valueStart = colon + 1
        while (valueStart < text.length && text[valueStart] == ' ') valueStart++
        if (valueStart < text.length && text[valueStart] == '"') {
            val valueEnd = text.indexOf('"', valueStart + 1)
            if (valueEnd < 0) break
            values[key] = text.substring(valueStart + 1, valueEnd)
            index = valueEnd + 1
        } else {
            var valueEnd = text.indexOf(',', valueStart)
            if (valueEnd < 0) valueEnd = text.length
            values[key] = text.substring(valueStart, valueEnd).trim()
            index = valueEnd
        }
    }
    if (values.isEmpty()) return null
    return PastedIntent(
        action = values["action"].orEmpty(),
        packageName = values["packageName"].orEmpty(),
        className = values["className"].orEmpty(),
        category = values["category"].orEmpty(),
        extra = values["extra"].orEmpty(),
    )
}

private sealed interface TargetChoice {
    data object FollowDefault : TargetChoice
    data object CircleToSearch : TargetChoice
    data class App(val packageName: String) : TargetChoice
    /** Target configured on the custom screen: explicit package / component / intent. */
    data class Custom(val packageName: String) : TargetChoice
    /** Leave the entry to ColorOS: the gesture handle stays 小布识屏. */
    data object Oem : TargetChoice
    /** Wake nothing: the entry is switched off, OEM call included. */
    data object None : TargetChoice
}

private const val ROUTE_ENTRIES = "entries"
private const val ROUTE_TARGET = "target"
private const val ROUTE_CUSTOM = "custom"
private const val ROUTE_ADVANCED = "advanced"

/* --------------------------------------------------------------------------------------------- */
/* Root                                                                                           */
/* --------------------------------------------------------------------------------------------- */

@Composable
fun AssistRestoreApp() {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf(AssistantSnapshot.load(context)) }
    val store = remember { SettingsStore(context) }
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var route by remember { mutableStateOf(ROUTE_ENTRIES) }
    var entryIndex by remember { mutableIntStateOf(1) }
    var frameworkConnected by remember { mutableStateOf(App.service != null) }
    var masterEnabled by remember { mutableStateOf(store.isEnabled()) }
    var choices by remember { mutableStateOf(loadChoices(store)) }
    var skipOcrPreload by remember { mutableStateOf(store.skipOcrPreload()) }
    var unblockPageFlags by remember { mutableStateOf(store.unblockPageFlags()) }
    var fakeGoogleBuild by remember { mutableStateOf(store.spoofGoogleBuild()) }

    // The framework bridge arrives asynchronously: once it is bound the real configuration is
    // readable (and writable) instead of the local fallback.
    // The default assistant and the installed assistant apps live outside this app, so they are
    // re-read every time the screen comes back to the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                snapshot = AssistantSnapshot.load(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        val listener = object : App.ServiceStateListener {
            override fun onServiceStateChanged(service: XposedService?) {
                store.bind(service)
                frameworkConnected = service != null
                masterEnabled = store.isEnabled()
                choices = loadChoices(store)
                skipOcrPreload = store.skipOcrPreload()
                unblockPageFlags = store.unblockPageFlags()
                fakeGoogleBuild = store.spoofGoogleBuild()
            }
        }
        App.addServiceStateListener(listener, true)
        onDispose { App.removeServiceStateListener(listener) }
    }

    // The launcher only re-reads the corner gesture availability on a navigation-mode change or at
    // boot, so a corner change needs a reboot to fully settle.
    val rebootHint: () -> Unit = {
        Toast.makeText(
            context,
            "已保存：底角那项要重启手机后桌面才会重新判断，之前可能仍保留手势动画",
            Toast.LENGTH_LONG,
        ).show()
    }

    fun persistChoice(index: Int, choice: TargetChoice) {
        val entry = ENTRY_IDS[index]
        val wasDisabled = AssistConfig.MODE_NONE == store.mode(entry)
        when (choice) {
            // Only the mode changes here; the custom fields stay untouched as history.
            TargetChoice.FollowDefault -> store.setTarget(entry, AssistConfig.MODE_DEFAULT)
            TargetChoice.CircleToSearch -> store.setTarget(entry, AssistConfig.MODE_CTS)
            is TargetChoice.App -> store.setTarget(
                entry, AssistConfig.MODE_APP, packageName = choice.packageName,
            )
            is TargetChoice.Custom -> store.setTarget(entry, AssistConfig.MODE_CUSTOM)
            TargetChoice.Oem -> store.setTarget(entry, AssistConfig.MODE_OEM)
            TargetChoice.None -> store.setTarget(entry, AssistConfig.MODE_NONE)
        }
        if (entry == AssistConfig.ENTRY_CORNER) {
            // Only the disabled/available transition changes what the launcher was told; switching
            // between ordinary targets (CTS, an app, the OEM behaviour) keeps that flag as it is.
            if (wasDisabled != (choice is TargetChoice.None)) {
                rebootHint()
            }
        }
    }


    val notify: (String) -> Unit = { message ->
        scope.launch { snackbarHost.showSnackbar(message) }
    }

    val openSystemAssistantSettings: () -> Unit = {
        val candidates = listOf(
            // What ColorOS' own "默认语音助手" row opens; gated on some builds, so try it first.
            Intent("android.intent.action.MANAGE_DEFAULT_APP")
                .putExtra("android.intent.extra.ROLE_NAME", "android.app.role.ASSISTANT"),
            // Settings' assistant page (ManageAssistActivity) — the page the card is meant to open.
            Intent("android.settings.VOICE_INPUT_SETTINGS"),
            // Last resort: the default-apps list, where 数字助理应用 is one row.
            Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"),
        )
        val opened = candidates.any { intent ->
            runCatching { context.startActivity(intent) }.isSuccess
        }
        if (!opened) {
            Toast.makeText(context, "系统里没有可打开的助理设置页", Toast.LENGTH_SHORT).show()
        }
    }

    val selectTab: (Int) -> Unit = { index ->
        route = when (index) {
            0 -> ROUTE_ENTRIES
            1 -> ROUTE_ADVANCED
            else -> ROUTE_ENTRIES
        }
    }

    when (route) {
        ROUTE_TARGET -> {
            BackHandler { route = ROUTE_ENTRIES }
            TargetScreen(
                entryIndex = entryIndex,
                onSelectEntry = { entryIndex = it },
                choice = choices[entryIndex],
                onSelectChoice = { picked ->
                    choices = choices.toMutableList().also { it[entryIndex] = picked }
                    persistChoice(entryIndex, picked)
                },
                snapshot = snapshot,
                onBack = { route = ROUTE_ENTRIES },
                onCustom = { route = ROUTE_CUSTOM },
                oemOptionVisible = ENTRY_IDS[entryIndex] == AssistConfig.ENTRY_HANDLE,
                breenoOptionVisible = ENTRY_IDS[entryIndex] != AssistConfig.ENTRY_HANDLE,
                cornerHintVisible = ENTRY_IDS[entryIndex] == AssistConfig.ENTRY_CORNER,
                snackbarHost = snackbarHost,
            )
        }

        ROUTE_CUSTOM -> {
            BackHandler { route = ROUTE_TARGET }
            CustomTargetScreen(
                entry = ENTRY_IDS[entryIndex],
                store = store,
                onBack = {
                    route = ROUTE_TARGET
                    // The custom screen writes straight to the store, so the entry list has to be
                    // re-read or the previous page keeps showing the old selection.
                    choices = loadChoices(store)
                },
                snackbarHost = snackbarHost,
                notify = notify,
            )
        }

        ROUTE_ADVANCED -> AdvancedScreen(
            skipOcrPreload = skipOcrPreload,
            onSkipOcrPreloadChange = { skipOcrPreload = it; store.setSkipOcrPreload(it) },
            unblockPageFlags = unblockPageFlags,
            onUnblockPageFlagsChange = { unblockPageFlags = it; store.setUnblockPageFlags(it) },
            fakeGoogleBuild = fakeGoogleBuild,
            onFakeGoogleBuildChange = { fakeGoogleBuild = it; store.setSpoofGoogleBuild(it) },
            selectedTab = 1,
            onSelectTab = selectTab,
        )

        else -> EntriesScreen(
            snapshot = snapshot,
            choices = choices,
            masterEnabled = masterEnabled,
            onMasterChange = { masterEnabled = it; store.setEnabled(it); rebootHint() },
            onOpenTarget = { index ->
                entryIndex = index
                route = ROUTE_TARGET
            },
            onOpenAssistantSettings = openSystemAssistantSettings,
            frameworkConnected = frameworkConnected,
            snackbarHost = snackbarHost,
            selectedTab = 0,
            onSelectTab = selectTab,
        )
    }
}

/* --------------------------------------------------------------------------------------------- */
/* Shared building blocks                                                                         */
/* --------------------------------------------------------------------------------------------- */

@Composable
private fun AppScreen(
    title: String,
    snackbarHost: SnackbarHostState? = null,
    onBack: (() -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { AppTopBar(title, onBack, actions) },
        bottomBar = { bottomBar?.invoke() },
        snackbarHost = { if (snackbarHost != null) SnackbarHost(snackbarHost) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { insets ->
        content(
            Modifier
                .padding(insets)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        )
    }
}

@Composable
private fun AppTopBar(
    title: String,
    onBack: (() -> Unit)?,
    actions: (@Composable () -> Unit)?,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(64.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            )
            actions?.invoke()
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun AppBottomBar(selected: Int, onSelect: (Int) -> Unit) {
    NavigationBar {
        listOf(
            Triple("入口", Icons.Rounded.TouchApp, 0),
            Triple("高级", Icons.Rounded.Settings, 1),
        ).forEach { (label, icon, index) ->
            NavigationBarItem(
                selected = selected == index,
                onClick = { onSelect(index) },
                icon = { Icon(icon, contentDescription = label) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
    ) {
        Column(content = content)
    }
}

@Composable
private fun ListRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconBitmap: ImageBitmap? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when {
            iconBitmap != null -> Image(
                bitmap = iconBitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(40.dp),
            )

            icon != null -> Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }

            else -> Spacer(Modifier.width(40.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        trailing?.invoke()
    }
}

/**
 * Keeps a card's text readable whatever sits behind it: the tint stays opaque where the title and
 * body are, then fades towards the bottom so the graphic only shows through as a watermark.
 */
@Composable
private fun BoxScope.ReadabilityScrim(containerColor: Color) {
    Box(
        Modifier
            .matchParentSize()
            .background(
                Brush.verticalGradient(
                    0f to containerColor,
                    0.52f to containerColor.copy(alpha = 0.94f),
                    1f to containerColor.copy(alpha = 0.30f),
                )
            )
    )
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 72.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun Chevron() {
    Icon(
        imageVector = Icons.Rounded.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(checked = checked, onCheckedChange = onChange)
}

/* --------------------------------------------------------------------------------------------- */
/* Home: entries                                                                                  */
/* --------------------------------------------------------------------------------------------- */

@Composable
private fun EntriesScreen(
    snapshot: AssistantSnapshot,
    choices: List<TargetChoice>,
    masterEnabled: Boolean,
    onMasterChange: (Boolean) -> Unit,
    onOpenTarget: (Int) -> Unit,
    onOpenAssistantSettings: () -> Unit,
    frameworkConnected: Boolean,
    snackbarHost: SnackbarHostState,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
) {
    AppScreen(
        title = "ColorOS 唤语",
        snackbarHost = snackbarHost,
        bottomBar = { AppBottomBar(selectedTab, onSelectTab) },
    ) { modifier ->
        Column(modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ModuleStatusCard(
                    active = frameworkConnected,
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f),
                )
                AssistantCard(
                    snapshot = snapshot,
                    onClick = onOpenAssistantSettings,
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f),
                )
            }

            Spacer(Modifier.height(24.dp))
            Text("唤醒入口", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            SectionCard {
                ENTRY_NAMES.forEachIndexed { index, name ->
                    ListRow(
                        title = name,
                        subtitle = describeChoice(choices[index], snapshot),
                        icon = ENTRY_ICONS[index],
                        trailing = { Chevron() },
                        onClick = { onOpenTarget(index) },
                    )
                    if (index != ENTRY_NAMES.lastIndex) RowDivider()
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionCard {
                ListRow(
                    title = "模块接管全部入口",
                    subtitle = "关闭后三个入口都回到 ColorOS 原始行为",
                    icon = Icons.Rounded.Tune,
                    trailing = { Toggle(masterEnabled, onMasterChange) },
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The status icon is the card's background layer: about three quarters of the card, centred. */
@Composable
private fun ModuleStatusCard(
    active: Boolean,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (active) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = if (active) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = containerColor,
        modifier = modifier
            .clip(MaterialTheme.shapes.extraLarge)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Box(Modifier.fillMaxSize()) {
            Icon(
                imageVector = if (active) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
                contentDescription = null,
                tint = contentColor.copy(alpha = 0.30f),
                modifier = Modifier
                    // Anchored to the corner and sized past the card so it bleeds off the edge: the
                    // glyph stays recognisable while the text area stays clean.
                    .align(Alignment.BottomEnd)
                    .fillMaxSize(0.92f)
                    .offset(x = 16.dp, y = 16.dp),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(20.dp),
            ) {
                Text(
                    text = if (active) "模块已生效" else "模块未生效",
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (active) "4 个作用域已挂载" else "未连接 Xposed 服务",
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.8f),
                )
            }
        }
    }
}

/** The assistant card reuses the default assistant app's own launcher icon. */
@Composable
private fun AssistantCard(
    snapshot: AssistantSnapshot,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = MaterialTheme.colorScheme.primaryContainer
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = containerColor,
        modifier = modifier
            .clip(MaterialTheme.shapes.extraLarge)
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxSize()) {
            val icon = snapshot.defaultIcon
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxSize(0.72f),
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.SettingsVoice,
                    contentDescription = null,
                    tint = contentColor.copy(alpha = 0.22f),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxSize(0.72f),
                )
            }
            ReadabilityScrim(containerColor)
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(20.dp),
            ) {
                Text(
                    text = snapshot.defaultLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "当前默认助理 · 点按打开设置",
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.8f),
                )
            }
        }
    }
}

private fun describeChoice(choice: TargetChoice, snapshot: AssistantSnapshot): String =
    when (choice) {
        TargetChoice.FollowDefault -> "当前：跟随系统默认助理 · ${snapshot.defaultLabel}"
        TargetChoice.CircleToSearch -> "当前：一圈即搜"
        is TargetChoice.App -> "当前：" + (
            snapshot.apps.firstOrNull { it.packageName == choice.packageName }?.label
                ?: choice.packageName
            )
        is TargetChoice.Custom -> "当前：自定义 · " + choice.packageName
        TargetChoice.Oem -> "当前：小布识屏（ColorOS 原生）"
        TargetChoice.None -> "当前：不唤醒助理"
    }

/* --------------------------------------------------------------------------------------------- */
/* Target                                                                                         */
/* --------------------------------------------------------------------------------------------- */

@Composable
private fun TargetScreen(
    entryIndex: Int,
    onSelectEntry: (Int) -> Unit,
    choice: TargetChoice,
    onSelectChoice: (TargetChoice) -> Unit,
    snapshot: AssistantSnapshot,
    onBack: () -> Unit,
    onCustom: () -> Unit,
    oemOptionVisible: Boolean,
    breenoOptionVisible: Boolean,
    cornerHintVisible: Boolean,
    snackbarHost: SnackbarHostState,
) {
    AppScreen(title = "唤醒目标", snackbarHost = snackbarHost, onBack = onBack) { modifier ->
        Column(modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))

            // Single choice: the tab switches which entry is being configured.
            TabRow(selectedTabIndex = entryIndex) {
                ENTRY_NAMES.forEachIndexed { index, name ->
                    Tab(
                        selected = index == entryIndex,
                        onClick = { onSelectEntry(index) },
                        text = {
                            Text(
                                text = name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("选择这个入口要唤醒谁", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            SectionCard {
                ListRow(
                    title = "跟随系统默认助理",
                    subtitle = "当前系统默认：${snapshot.defaultLabel}",
                    icon = Icons.Rounded.SettingsVoice,
                    trailing = {
                        Toggle(choice is TargetChoice.FollowDefault) {
                            toggleChoice(it, TargetChoice.FollowDefault, onSelectChoice)
                        }
                    },
                )
                RowDivider()
                ListRow(
                    title = "一圈即搜",
                    subtitle = "走系统 CTS 服务，不跟随默认助理设置",
                    icon = Icons.Rounded.Search,
                    trailing = {
                        Toggle(choice is TargetChoice.CircleToSearch) {
                            toggleChoice(it, TargetChoice.CircleToSearch, onSelectChoice)
                        }
                    },
                )
                if (oemOptionVisible) {
                    RowDivider()
                    ListRow(
                        title = "小布识屏",
                        subtitle = "不接管：长按手势条仍由 ColorOS 自己处理",
                        icon = Icons.Rounded.Tune,
                        trailing = {
                            Toggle(choice is TargetChoice.Oem) {
                                toggleChoice(it, TargetChoice.Oem, onSelectChoice)
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            SectionCard {
                if (breenoOptionVisible) {
                    ListRow(
                        title = "小布助手",
                        subtitle = "只有 ACTION_ASSIST 活动，直接唤起小布助手本体",
                        icon = Icons.Rounded.SettingsVoice,
                        trailing = {
                            Toggle(
                                choice is TargetChoice.App &&
                                    choice.packageName == BREENO_PACKAGE
                            ) {
                                toggleChoice(
                                    it,
                                    TargetChoice.App(BREENO_PACKAGE),
                                    onSelectChoice,
                                )
                            }
                        },
                    )
                    RowDivider()
                }
                ListRow(
                    title = "其他应用…",
                    subtitle = "手动填包名或服务组件，用该应用自己的助理入口",
                    icon = Icons.Rounded.Add,
                    trailing = { Chevron() },
                    onClick = onCustom,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "三个入口各存一份目标；全部关掉 = 这个入口不唤醒任何助理（ColorOS 原生调用也一并关掉）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (cornerHintVisible) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "底角这项改动要重启手机后桌面才会重新判断，重启前可能仍保留手势动画。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/* --------------------------------------------------------------------------------------------- */
/* Custom target                                                                                  */
/* --------------------------------------------------------------------------------------------- */

@Composable
private fun CustomTargetScreen(
    entry: String,
    store: SettingsStore,
    onBack: () -> Unit,
    snackbarHost: SnackbarHostState,
    notify: (String) -> Unit,
) {
    var packageName by remember {
        mutableStateOf(store.targetPackage(entry).ifEmpty { "com.heytap.speechassist" })
    }
    var component by remember { mutableStateOf(store.targetComponent(entry)) }
    var intentArgs by remember {
        mutableStateOf(
            store.targetArgs(entry).ifEmpty {
                "action=heytap.intent.action.ACTIVATE_SPEECH_ASSIST&start_type=91"
            }
        )
    }

    val methods = listOf(
        AssistConfig.METHOD_AUTO,
        AssistConfig.METHOD_ASSIST,
        AssistConfig.METHOD_INTENT,
    )
    val methodLabels = listOf("自动", "ACTION_ASSIST", "显式 Intent")
    var methodIndex by remember {
        mutableIntStateOf(methods.indexOf(store.targetMethod(entry)).coerceAtLeast(0))
    }
    var pasted by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }

    AppScreen(title = "自定义目标", snackbarHost = snackbarHost, onBack = onBack) { modifier ->
        Column(modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = pasted,
                onValueChange = { pasted = it },
                label = { Text("粘贴 Intent JSON") },
                supportingText = { Text("把应用信息里那段 JSON 粘进来，点下面按钮自动填") },
                leadingIcon = { Icon(Icons.Rounded.DataObject, contentDescription = null) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val parsed = parseIntentJson(pasted)
                    if (parsed == null) {
                        notify("没认出 JSON，检查一下格式")
                    } else {
                        if (parsed.packageName.isNotEmpty()) {
                            packageName = parsed.packageName
                        }
                        if (parsed.className.isNotEmpty()) {
                            component = parsed.className
                        }
                        methodIndex =
                            methods.indexOf(AssistConfig.METHOD_INTENT).coerceAtLeast(0)
                        val builder = StringBuilder()
                        if (parsed.action.isNotEmpty()) {
                            builder.append("action=").append(parsed.action)
                        }
                        if (parsed.category.isNotEmpty()) {
                            if (builder.isNotEmpty()) builder.append("&")
                            builder.append("category=").append(parsed.category)
                        }
                        if (parsed.extra.isNotEmpty()) {
                            if (builder.isNotEmpty()) builder.append("&")
                            builder.append(parsed.extra)
                        }
                        intentArgs = builder.toString()
                        notify("已识别并填入，确认后按「保存目标」")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("识别并填入")
            }
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = packageName,
                onValueChange = { packageName = it },
                label = { Text("包名") },
                supportingText = { Text("例如 com.heytap.speechassist") },
                leadingIcon = { Icon(Icons.Rounded.Apps, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = component,
                onValueChange = { component = it },
                label = { Text("服务组件") },
                supportingText = { Text("com.heytap.speechassist/.service.SpeechAssistService") },
                leadingIcon = { Icon(Icons.Rounded.Widgets, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            Column {
                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(MaterialTheme.shapes.extraSmall)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline,
                                shape = MaterialTheme.shapes.extraSmall,
                            )
                            .clickable { menuOpen = true }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CallSplit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = methodLabels[methodIndex],
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = Icons.Rounded.ArrowDropDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        methodLabels.forEachIndexed { index, name ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    methodIndex = index
                                    menuOpen = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "自动：优先该应用声明的助理活动；显式 Intent：按下面的组件与参数启动",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = intentArgs,
                onValueChange = { intentArgs = it },
                label = { Text("Intent 参数") },
                supportingText = {
                    Text("action=heytap.intent.action.ACTIVATE_SPEECH_ASSIST · start_type=91")
                },
                leadingIcon = { Icon(Icons.Rounded.DataObject, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    store.setTarget(
                        entry = entry,
                        mode = AssistConfig.MODE_CUSTOM,
                        packageName = packageName.trim(),
                        component = component.trim(),
                        method = methods[methodIndex],
                        args = intentArgs.trim(),
                    )
                    notify("已保存：该入口改为自定义目标")
                    notify(
                        "已保存：" + store.mode(entry) +
                            " · " + store.targetPackage(entry) +
                            " · " + store.targetComponent(entry) +
                            " · " + store.targetMethod(entry)
                    )
                    onBack()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("保存目标")
            }


            Spacer(Modifier.height(24.dp))
        }
    }
}

/* --------------------------------------------------------------------------------------------- */
/* Advanced                                                                                       */
/* --------------------------------------------------------------------------------------------- */

@Composable
private fun AdvancedScreen(
    skipOcrPreload: Boolean,
    onSkipOcrPreloadChange: (Boolean) -> Unit,
    unblockPageFlags: Boolean,
    onUnblockPageFlagsChange: (Boolean) -> Unit,
    fakeGoogleBuild: Boolean,
    onFakeGoogleBuildChange: (Boolean) -> Unit,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
) {
    AppScreen(
        title = "高级",
        bottomBar = { AppBottomBar(selectedTab, onSelectTab) },
    ) { modifier ->
        Column(modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(16.dp))

            SectionCard {
                ListRow(
                    title = "跳过识屏服务预绑定",
                    subtitle = "长按手势条时不再白唤醒一次小布识屏服务",
                    icon = Icons.Rounded.Block,
                    trailing = { Toggle(skipOcrPreload, onSkipOcrPreloadChange) },
                )
                RowDivider()
                ListRow(
                    title = "解除页面级手势限制",
                    subtitle = "设置这类页面也能用底角内滑",
                    icon = Icons.Rounded.LockOpen,
                    trailing = { Toggle(unblockPageFlags, onUnblockPageFlagsChange) },
                )
                RowDivider()
                ListRow(
                    title = "Google 应用机型伪装",
                    subtitle = "伪装为 SM-S928B，解锁一圈即搜",
                    icon = Icons.Rounded.Smartphone,
                    trailing = { Toggle(fakeGoogleBuild, onFakeGoogleBuildChange) },
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/* --------------------------------------------------------------------------------------------- */
/* Logs                                                                                           */
/* --------------------------------------------------------------------------------------------- */
