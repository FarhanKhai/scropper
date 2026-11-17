package com.scropper.app

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity(), WebHost {
    private lateinit var settings: ShieldSettings
    private lateinit var controllers: List<ScropperWebController>
    private val uiPrefs by lazy { getSharedPreferences("ui", Context.MODE_PRIVATE) }

    private var site by mutableStateOf(Site.YOUTUBE)
    private var panelOpen by mutableStateOf(false)
    private var settingsOpen by mutableStateOf(false)
    private var handleY by mutableFloatStateOf(0.45f)
    private var resumed = false

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        fileCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.BLACK),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.BLACK),
        )
        super.onCreate(savedInstanceState)

        settings = ShieldSettings(this)
        handleY = uiPrefs.getFloat(KEY_HANDLE_Y, 0.45f)
        site = runCatching { Site.valueOf(uiPrefs.getString(KEY_SITE, null) ?: "") }.getOrDefault(Site.YOUTUBE)
        // Both WebViews are created once and live as long as the activity.
        controllers = Site.entries.map { ScropperWebController(this, it, settings, this) }
        handleIntent(intent)
        // With ad blocking on, pages wait for the engine so the very first load is already filtered.
        if (settings.config.blockAds) startAdBlock() else controllers.forEach { it.start() }

        setContent {
            MaterialTheme(colorScheme = ScropperColors) {
                ScropperApp(
                    site = site,
                    panelOpen = panelOpen,
                    settingsOpen = settingsOpen,
                    handleY = handleY,
                    config = settings.state.value,
                    controllers = controllers,
                    onPanel = { panelOpen = it },
                    onSelectSite = ::selectSite,
                    onSettings = { settingsOpen = it; if (it) panelOpen = false },
                    onHandleMove = { handleY = it },
                    onHandleSettled = { uiPrefs.edit().putFloat(KEY_HANDLE_Y, handleY).apply() },
                    onConfig = ::updateConfig,
                    onBack = ::handleBack,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val url = intent.dataString ?: return
        val target = Site.forUrl(url) ?: return
        routeToSite(target, url)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        syncForeground()
    }

    override fun onPause() {
        resumed = false
        syncForeground()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        AdBlock.clearListener()
        controllers.forEach { it.destroy() }
        super.onDestroy()
    }

    // --- navigation / settings ----------------------------------------------------------------

    private fun controller(s: Site) = controllers.first { it.site == s }

    private fun syncForeground() {
        controllers.forEach { it.setForeground(resumed && site == it.site) }
    }

    private fun selectSite(next: Site) {
        panelOpen = false
        settingsOpen = false
        if (next == site) {
            controller(next).goHome()
            return
        }
        site = next
        uiPrefs.edit().putString(KEY_SITE, next.name).apply()
        syncForeground()
    }

    private fun updateConfig(transform: (ShieldConfig) -> ShieldConfig) {
        settings.update(transform)
        controllers.forEach { it.applyConfig(settings.config) }
        syncForeground()
        if (settings.config.blockAds && !AdBlock.ready) startAdBlock()
    }

    private fun startAdBlock() {
        AdBlock.start(this) {
            controllers.forEach {
                it.applyConfig(settings.config)
                it.start()
            }
        }
    }

    private fun handleBack() {
        val current = controller(site)
        when {
            customView != null -> customViewCallback?.onCustomViewHidden() ?: hideCustomView()
            panelOpen -> panelOpen = false
            current.webView.canGoBack() -> current.webView.goBack()
            else -> moveTaskToBack(true)
        }
    }

    // --- WebHost ------------------------------------------------------------------------------

    override fun routeToSite(site: Site, url: String) {
        controller(site).load(url)
        if (site != this.site) selectSite(site)
    }

    override fun openExternal(uri: Uri) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        }
    }

    override fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        val match = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val container = FullscreenContainer(this).apply {
            setBackgroundColor(AndroidColor.BLACK)
            addView(view, match)
        }
        customView = container
        customViewCallback = callback
        (window.decorView as FrameLayout).addView(container, match)
        // Like the YouTube app: the fullscreen button turns the screen for landscape videos,
        // even with auto-rotate off. Portrait videos stay upright.
        val current = site
        controller(current).webView.evaluateJavascript(VIDEO_ASPECT_JS) { result ->
            val aspect = result?.toFloatOrNull() ?: 0f
            val landscape = aspect > 1f || (aspect == 0f && current == Site.YOUTUBE)
            if (customView != null && landscape) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun hideCustomView() {
        val view = customView ?: return
        (view as? ViewGroup)?.removeAllViews()
        (window.decorView as FrameLayout).removeView(view)
        customView = null
        customViewCallback = null
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    override fun openFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        return try {
            filePicker.launch(params.createIntent())
            true
        } catch (_: Exception) {
            fileCallback = null
            false
        }
    }

    private companion object {
        const val KEY_HANDLE_Y = "handle_y"
        const val KEY_SITE = "last_site"
        const val VIDEO_ASPECT_JS =
            "(function(){var v=document.querySelector('video');" +
                "return v&&v.videoWidth&&v.videoHeight?v.videoWidth/v.videoHeight:0})()"
    }
}

/**
 * Hosts the fullscreen video. A downward swipe that starts at the top edge is meant for the
 * status bar; it is taken away from the page so the site's own "swipe down to leave fullscreen"
 * gesture doesn't fire. Taps and other gestures pass through.
 */
private class FullscreenContainer(context: Context) : FrameLayout(context) {
    private val topZone = 40 * resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var guarded = false
    private var stolen = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                guarded = ev.y < topZone
                stolen = false
            }
            MotionEvent.ACTION_MOVE -> if (guarded && !stolen) {
                val dy = ev.y - downY
                if (dy > slop && dy > abs(ev.x - downX)) stolen = true
            }
        }
        return stolen
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = stolen
}

// --- UI -------------------------------------------------------------------------------------------

private val Surface = Color(0xFF141416)
private val Muted = Color(0xFF8A8A93)
private val Accent = Color(0xFF7CF29A)
private val Hairline = Color.White.copy(alpha = 0.10f)

private val ScropperColors = darkColorScheme(
    primary = Accent,
    background = Color.Black,
    surface = Surface,
    surfaceContainerLow = Surface,
    onBackground = Color.White,
    onSurface = Color.White,
)

private fun icon(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { addPath(pathData = addPathNodes(it), fill = SolidColor(Color.White)) }
    }.build()

private val ShieldIcon = icon(
    "Shield",
    "M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45 9,-12V5l-9,-4z",
)

/** Rounded screen with a play cut-out. */
private val VideoIcon = icon(
    "Video",
    "M6,4h12a4,4 0,0 1,4 4v8a4,4 0,0 1,-4 4H6a4,4 0,0 1,-4 -4V8a4,4 0,0 1,4 -4z" +
        "M10,8.5v7l6,-3.5z",
)

/** Rounded square camera outline. */
private val CameraIcon = icon(
    "Camera",
    "M7,2h10a5,5 0,0 1,5 5v10a5,5 0,0 1,-5 5H7a5,5 0,0 1,-5 -5V7a5,5 0,0 1,5 -5z" +
        "M7,4a3,3 0,0 0,-3 3v10a3,3 0,0 0,3 3h10a3,3 0,0 0,3 -3V7a3,3 0,0 0,-3 -3z",
    "M12,7a5,5 0,1 1,0 10a5,5 0,1 1,0 -10zM12,9a3,3 0,1 0,0 6a3,3 0,1 0,0 -6z",
    "M17.5,5.3a1.2,1.2 0,1 1,0 2.4a1.2,1.2 0,1 1,0 -2.4z",
)

private val Site.icon: ImageVector
    get() = when (this) {
        Site.YOUTUBE -> VideoIcon
        Site.INSTAGRAM -> CameraIcon
    }

private val Site.label: String
    get() = when (this) {
        Site.YOUTUBE -> "YouTube"
        Site.INSTAGRAM -> "Instagram"
    }

private val HandleTouchWidth = 22.dp
private val HandleTouchHeight = 96.dp

@Composable
private fun ScropperApp(
    site: Site,
    panelOpen: Boolean,
    settingsOpen: Boolean,
    handleY: Float,
    config: ShieldConfig,
    controllers: List<ScropperWebController>,
    onPanel: (Boolean) -> Unit,
    onSelectSite: (Site) -> Unit,
    onSettings: (Boolean) -> Unit,
    onHandleMove: (Float) -> Unit,
    onHandleSettled: () -> Unit,
    onConfig: ((ShieldConfig) -> ShieldConfig) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    // Samsung's gesture hint reports a 0 navigation-bar inset but still draws its pill over the app.
    val density = LocalDensity.current
    val pillOverlaps = WindowInsets.navigationBars.getBottom(density) == 0 &&
        WindowInsets.mandatorySystemGestures.getBottom(density) > 0

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(bottom = if (pillOverlaps) 10.dp else 0.dp),
    ) {
        ScropperWebStack(controllers, site, Modifier.fillMaxSize())

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val travelPx = with(density) { (maxHeight - HandleTouchHeight).toPx() }.coerceAtLeast(1f)
            val handleTopPx = handleY.coerceIn(0f, 1f) * travelPx
            val handleTop = with(density) { handleTopPx.toDp() }

            // Scrim: tap anywhere to dismiss the panel.
            AnimatedVisibility(panelOpen, enter = fadeIn(), exit = fadeOut()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onPanel(false) },
                )
            }

            EdgeHandle(
                hidden = panelOpen,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, handleTopPx.roundToInt()) },
                onOpen = { onPanel(true) },
                onDrag = { dy -> onHandleMove(((handleY * travelPx) + dy).coerceIn(0f, travelPx) / travelPx) },
                onDragEnd = onHandleSettled,
            )

            // Panel floats beside the handle, clamped so it never leaves the screen.
            val panelHeight = 236.dp
            val panelTop = (handleTop + HandleTouchHeight / 2 - panelHeight / 2)
                .coerceIn(8.dp, (maxHeight - panelHeight - 8.dp).coerceAtLeast(8.dp))
            AnimatedVisibility(
                panelOpen,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = panelTop)
                    .padding(end = 14.dp),
                enter = slideInHorizontally(spring(dampingRatio = 0.8f, stiffness = 600f)) { it / 2 } +
                    fadeIn() + scaleIn(initialScale = 0.9f),
                exit = slideOutHorizontally { it / 2 } + fadeOut() + scaleOut(targetScale = 0.9f),
            ) {
                SwitcherPanel(
                    selected = site,
                    onSelect = onSelectSite,
                    onShield = { onSettings(true) },
                    modifier = Modifier.height(panelHeight),
                )
            }
        }
    }

    if (settingsOpen) {
        SettingsSheet(config, onConfig, onDismiss = { onSettings(false) })
    }
}

/**
 * Thin pill on the right edge. Swipe inward or tap -> open panel. Drag vertically -> move it.
 * Its touch area is excluded from the system Back gesture, so it wins over Back only there.
 */
@Composable
private fun EdgeHandle(
    hidden: Boolean,
    modifier: Modifier,
    onOpen: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val currentOnOpen by rememberUpdatedState(onOpen)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    var dragging by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        when {
            hidden -> 0f
            dragging -> 0.75f
            else -> 0.32f
        },
        label = "handleAlpha",
    )
    val pillWidth by animateFloatAsState(if (dragging) 6f else 4.5f, label = "handleWidth")

    Box(
        modifier
            .size(HandleTouchWidth, HandleTouchHeight)
            .systemGestureExclusion()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = viewConfiguration.touchSlop
                    var total = Offset.Zero
                    var mode = 0 // 0 = undecided, 1 = vertical move, 2 = horizontal swipe
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val delta = change.positionChange()
                        total += delta
                        if (mode == 0 && total.getDistance() > slop) {
                            mode = if (abs(total.y) > abs(total.x)) 1 else 2
                            if (mode == 1) {
                                dragging = true
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                        if (mode == 1) currentOnDrag(delta.y)
                        if (mode != 0) change.consume()
                        if (!change.pressed) break
                    }
                    when (mode) {
                        0 -> currentOnOpen()
                        1 -> {
                            dragging = false
                            currentOnDragEnd()
                        }
                        2 -> if (total.x < 0) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            currentOnOpen()
                        }
                    }
                }
            },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            Modifier
                .padding(end = 3.dp)
                .size(pillWidth.dp, 56.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = alpha)),
        )
    }
}

@Composable
private fun SwitcherPanel(
    selected: Site,
    onSelect: (Site) -> Unit,
    onShield: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(32.dp)
    Column(
        modifier
            .width(76.dp)
            .shadow(24.dp, shape)
            .clip(shape)
            .background(Surface.copy(alpha = 0.96f))
            .border(0.5.dp, Hairline, shape)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        Site.entries.forEach { s ->
            PanelButton(s.icon, s.label, active = s == selected) { onSelect(s) }
        }
        Box(
            Modifier
                .width(28.dp)
                .height(0.5.dp)
                .background(Hairline),
        )
        PanelButton(ShieldIcon, "Shield", active = false, tint = Accent, onClick = onShield)
    }
}

@Composable
private fun PanelButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(if (active) Color.White.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(24.dp), tint = if (active) tint else tint.copy(alpha = 0.7f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    config: ShieldConfig,
    onConfig: ((ShieldConfig) -> ShieldConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface,
        scrimColor = Color.Black.copy(alpha = 0.5f),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(ShieldIcon, null, Modifier.size(22.dp), tint = Accent)
                Spacer(Modifier.width(10.dp))
                Text("Shield", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Text("Choose what Scropper removes.", color = Muted, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))

            ToggleRow(
                "Block Ads",
                "YouTube video and feed ads, Instagram sponsored posts, trackers. Filters update daily.",
                config.blockAds,
            ) { v -> onConfig { it.copy(blockAds = v) } }
            ToggleRow(
                "Hide YouTube Shorts",
                "Shorts tab, shelves and results. Shorts links open as normal videos.",
                config.hideYtShorts,
            ) { v -> onConfig { it.copy(hideYtShorts = v) } }
            ToggleRow(
                "Hide Instagram Reels",
                "Reels tab and Reels in the feed. Stories, DMs and posts stay.",
                config.hideIgReels,
            ) { v -> onConfig { it.copy(hideIgReels = v) } }
            ToggleRow(
                "Hide Instagram Explore Grid",
                "Empties the recommendation grid. Search still works.",
                config.hideIgExplore,
            ) { v -> onConfig { it.copy(hideIgExplore = v) } }
            ToggleRow(
                "Background Audio Play",
                "Keep YouTube playing when you switch tabs or leave the app.",
                config.backgroundAudio,
                divider = false,
            ) { v -> onConfig { it.copy(backgroundAudio = v) } }

            Spacer(Modifier.height(12.dp))
            Text(
                "Tip: swipe the thin bar on the right edge to switch apps. Drag it up or down to move it.",
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    divider: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Switch,
            ) { onChange(!checked) }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = Muted, fontSize = 13.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = Accent,
                uncheckedThumbColor = Muted,
                uncheckedTrackColor = Color.Black,
                uncheckedBorderColor = Muted.copy(alpha = 0.5f),
            ),
        )
    }
    if (divider) HorizontalDivider(thickness = 0.5.dp, color = Hairline)
}
