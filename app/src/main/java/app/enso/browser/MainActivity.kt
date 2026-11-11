package app.enso.browser

import app.enso.browser.downloads.DownloadsTitle
import kotlinx.coroutines.Job
import androidx.core.content.FileProvider
import android.webkit.MimeTypeMap
import android.app.DownloadManager
import java.util.UUID
import java.io.File
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.browser.state.action.DownloadAction
import app.enso.browser.downloads.DownloadsList
import app.enso.browser.downloads.DownloadListActions
import app.enso.browser.downloads.DownloadItem
import app.enso.browser.bottombar.BarDownloads
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import mozilla.components.support.base.android.NotificationsDelegate
import mozilla.components.feature.downloads.manager.FetchDownloadManager
import mozilla.components.feature.downloads.DownloadsFeature
import app.enso.browser.downloads.DownloadService
import app.enso.browser.downloads.DownloadPromptCard
import app.enso.browser.downloads.DownloadPrompt
import android.os.Parcelable
import androidx.core.content.IntentCompat
import app.enso.browser.permissions.SiteNotifications
import app.enso.browser.permissions.SitePermissions
import app.enso.browser.permissions.SitePermission
import app.enso.browser.permissions.PermissionPromptCard
import app.enso.browser.permissions.PermissionChoice
import mozilla.components.support.ktx.util.URLStringUtils
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Build
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.animation.doOnEnd
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.enso.browser.bottombar.BarMetrics
import app.enso.browser.bottombar.BarPanel
import app.enso.browser.bottombar.BottomBar
import app.enso.browser.bottombar.BottomBarActions
import app.enso.browser.bottombar.SearchMode
import app.enso.browser.settings.AllSettingsActions
import app.enso.browser.settings.AllSettingsContent
import app.enso.browser.settings.AllSettingsTitle
import app.enso.browser.settings.Setting
import app.enso.browser.settings.SettingsCard
import app.enso.browser.settings.SettingsHeader
import app.enso.browser.settings.SettingsPage
import app.enso.browser.settings.SiteChoices
import app.enso.browser.settings.SiteSettingsActions
import app.enso.browser.settings.SiteSettingsContent
import app.enso.browser.settings.SiteState
import app.enso.browser.settings.SiteTitle
import app.enso.browser.sidebar.CardCorner
import app.enso.browser.sidebar.InnerFrame
import app.enso.browser.sidebar.MediaCardState
import app.enso.browser.sidebar.PageCard
import app.enso.browser.sidebar.Rail
import app.enso.browser.sidebar.RailWidth
import app.enso.browser.sidebar.Sidebar
import app.enso.browser.sidebar.SidebarActions
import app.enso.browser.sidebar.SidebarResizeHandle
import app.enso.browser.sidebar.EdgePadding
import app.enso.browser.sidebar.SidebarMaxWidth
import app.enso.browser.sidebar.SidebarMinWidth
import app.enso.browser.sidebar.mediaCardFor
import app.enso.browser.spacemenu.LocalSpaceMenuBlocks
import app.enso.browser.spacemenu.SpaceMenuBlocks
import app.enso.browser.spacemenu.SpaceMenuGesture
import app.enso.browser.spacemenu.SpaceMenuZones
import app.enso.browser.spacemenu.SpaceWave
import app.enso.browser.spacemenu.SpaceWaveState
import app.enso.browser.spacemenu.WaveSpace
import app.enso.browser.spaces.Space
import app.enso.browser.spaces.SpacesState
import app.enso.browser.spaces.updateTab
import app.enso.browser.spaces.QuickTab
import app.enso.browser.spaces.Split
import app.enso.browser.spaces.SplitLayout
import app.enso.browser.spaces.TabPlace
import app.enso.browser.spaces.essentialsFor
import app.enso.browser.spaces.find
import app.enso.browser.spaces.isInCurrentSpace
import app.enso.browser.spaces.keepQuickTab
import app.enso.browser.spaces.keepQuickTabIn
import app.enso.browser.spaces.resizeSplit
import app.enso.browser.spaces.select
import app.enso.browser.spaces.space
import app.enso.browser.spaces.splitOf
import app.enso.browser.spaces.splitWith
import app.enso.browser.spaces.toggleFolder
import app.enso.browser.spaces.unsplit
import app.enso.browser.split.DraggedTab
import app.enso.browser.bottombar.BarExtensions
import app.enso.browser.bottombar.BarSettings
import app.enso.browser.extensions.ExtensionListActions
import app.enso.browser.extensions.ExtensionPromptCard
import app.enso.browser.extensions.Extensions
import app.enso.browser.extensions.ExtensionsCard
import app.enso.browser.extensions.extensionRows
import app.enso.browser.glance.GlanceButton
import app.enso.browser.glance.GlanceLayout
import app.enso.browser.glance.GlanceOverlay
import app.enso.browser.glance.GlanceState
import app.enso.browser.glance.glanceLayout
import app.enso.browser.split.PagePanes
import app.enso.browser.split.PageView
import app.enso.browser.split.SplitDivider
import app.enso.browser.split.SplitDividerReach
import app.enso.browser.split.SplitDropZone
import app.enso.browser.split.TabDrag
import app.enso.browser.split.dragDivider
import app.enso.browser.split.paneRects
import app.enso.browser.split.splitGaps
import app.enso.browser.split.splitDropZone
import app.enso.browser.split.splitShares
import app.enso.browser.start.HistoryMatch
import app.enso.browser.start.StartScreen
import app.enso.browser.start.StartScreenActions
import app.enso.browser.sync.MozillaAccount
import app.enso.browser.sync.ZenSyncSheet
import app.enso.browser.sync.ZenSyncStatus
import app.enso.browser.tabsheet.MenuTarget
import app.enso.browser.tabsheet.TabDragActions
import app.enso.browser.tabsheet.TabMenu
import app.enso.browser.tabsheet.TabSheet
import app.enso.browser.tabsheet.TabSheetActions
import app.enso.browser.toolbar.AddressInput
import app.enso.browser.toolbar.PageState
import app.enso.browser.toolbar.ScrollingBarView
import app.enso.browser.toolbar.SteadyScrollDeltas
import app.enso.browser.toolbar.toPageState
import app.enso.browser.ui.AnimatedCard
import app.enso.browser.ui.EnsoColors
import app.enso.browser.ui.EnsoMotion
import app.enso.browser.ui.EnsoTheme
import app.enso.browser.ui.FrostedOver
import app.enso.browser.ui.FrostedPage
import app.enso.browser.ui.LocalFavicons
import app.enso.browser.ui.frostedCopy
import app.enso.browser.ui.colors
import kotlin.math.roundToInt
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.action.CustomTabListAction
import mozilla.components.browser.state.action.WebExtensionAction
import mozilla.components.browser.state.selector.findCustomTab
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.createCustomTab
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.HitResult
import mozilla.components.concept.engine.mediaquery.PreferredColorScheme
import mozilla.components.browser.thumbnails.BrowserThumbnails
import mozilla.components.concept.engine.EngineView
import mozilla.components.concept.engine.mediasession.MediaSession
import mozilla.components.feature.accounts.FxaWebChannelFeature
import mozilla.components.feature.tabs.WindowFeature
import mozilla.components.lib.state.ext.flow
import mozilla.components.lib.state.ext.observeAsComposableState
import mozilla.components.support.webextensions.WebExtensionOptionsPageObserver
import mozilla.components.support.webextensions.WebExtensionPopupObserver
import mozilla.components.ui.widgets.behavior.DependencyGravity
import mozilla.components.ui.widgets.behavior.EngineViewClippingBehavior
import mozilla.components.ui.widgets.behavior.EngineViewScrollingDataBehavior
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * The browser. Folded, the cover's tinted top bar sits over the page (boards 1–4); unfolded, the sidebar or rail
 * sits beside the page as a rounded card (boards 5 and 6). The start screen, tab sheet and menus go over them.
 * Folding keeps the page, tab, space and scroll position: the activity handles the change itself, so only the
 * layout around the page changes.
 */
class MainActivity : ComponentActivity() {
    private val components get() = (application as EnsoApplication).components

    private lateinit var root: CoordinatorLayout

    /** The first page view: the cover's page, and a split's first page. */
    private lateinit var engineView: EngineView
    private lateinit var engineContainer: FrameLayout
    private lateinit var pagePanes: PagePanes

    /** Everything Compose draws over the pages. */
    private lateinit var overlay: ComposeView

    /** The link being previewed (board 8), if any, and where it goes. */
    private var glance by mutableStateOf<GlanceState?>(null)
    private var floatingLayout by mutableStateOf<GlanceLayout?>(null)

    /** An extension's popup or settings page on screen (step 9): in the bottom bar's surface, or a card unfolded. */
    private var extensionPage by mutableStateOf<ExtensionPage?>(null)

    /** The inner screen's Extensions card, from the sidebar's ⋮ menu. */
    private var extensionsCardOpen by mutableStateOf(false)

    /** Unfolded, the settings card from the ⋮ menu, showing the site's settings or All settings (step 10). */
    private var settingsCard by mutableStateOf<SettingsPage?>(null)

    /** Bumped when All settings changes a preference, so composables that read [Settings] read it again. */
    private var settingsRevision by mutableIntStateOf(0)

    /** Glance or an extension's page covers the pages, which hide behind their blurred captures. */
    private val pagesCovered get() = glance != null || extensionPage != null

    /** The pages captured as Glance or an extension's page opened, blurred behind it, with where they were. */
    private var floatingBackdrop by mutableStateOf(emptyList<Pair<ImageBitmap, Rect>>())

    /** The page view for Glance and extensions' pages, under the overlay; made the first time one opens. */
    private var floatingPageView: PageView? = null

    private fun floatingPage(): PageView = floatingPageView
        ?: PageView(this, components.store, components.engine.createView(this).also { it.setActivityContext(this) }, keepVisible = false)
            .also {
                root.addView(it.container, root.indexOfChild(overlay))
                floatingPageView = it
            }
    /** The cover's bottom bar is drawn in the overlay; this invisible view is what the page's scrolling slides. */
    private lateinit var barAnchor: ScrollingBarView

    /**
     * With [CoverStatusBar.TintedStrip], a strip of the space's color under the status bar, which slides away with
     * the bottom bar so the page can use the whole screen.
     */
    private lateinit var statusBand: ScrollingBarView

    /** While the bar is scrolled away, a tap along the bottom edge brings it back. */
    private lateinit var bottomEdge: View
    private lateinit var thumbnails: BrowserThumbnails
    private lateinit var windowFeature: WindowFeature
    private lateinit var webChannelFeature: FxaWebChannelFeature

    private var editing by mutableStateOf(false)
    private var tabSheetOpen by mutableStateOf(false)

    /** The page the tab sheet opened over, blurred for its frosted backdrop (spec: board 4). */
    private var sheetBackdrop by mutableStateOf<FrostedPage?>(null)
    private var selectedTabId by mutableStateOf<String?>(null)

    /** Set when the app comes back after being away a while; cleared by opening or switching to a tab. */
    private var startScreenRequested by mutableStateOf(false)

    /** Shown when no tab is selected, the selected tab belongs to another space, or after time away. */
    private val startScreenVisible: Boolean
        get() {
            val selected = selectedTabId ?: return true
            return startScreenRequested ||
                !components.spaces.state.value.isInCurrentSpace(selected, components.settings.essentialsPerContainer)
        }

    private var zenSyncSheetOpen by mutableStateOf(false)

    /** Unfolded: the sidebar or rail beside the page card instead of the cover's top bar. */
    private var inner by mutableStateOf(false)
    private var sidebarExpanded by mutableStateOf(false)

    /** The full sidebar's width in dp, set by dragging its edge. */
    private var sidebarWidthDp by mutableFloatStateOf(0f)

    /** That width as it fits this window: never changes [sidebarWidthDp], so a narrow moment can't shrink it. */
    private var sidebarFullDp by mutableFloatStateOf(0f)

    /** The sidebar's width as drawn, in dp: it animates between the rail and the sidebar, and follows a drag. */
    private var sidebarShownDp by mutableFloatStateOf(0f)
    private var sidebarAnimator: ValueAnimator? = null

    /**
     * While the sidebar moves (opening, closing or being dragged), the page stays put under it at this width
     * (in dp) of sidebar, and resizes once when it stops. Resizing GeckoView every frame would make it reflow.
     */
    private var pinnedPageDp: Float? = null

    /** The rail's search button opened the sidebar, so it closes again when the edit ends. */
    private var collapseAfterEdit = false

    /** The page card on the inner screen, in window pixels. A split shares it out between its pages. */
    private var cardRect by mutableStateOf(Rect.Zero)

    /** The pages on screen, left to right, and their split while one shows (board 7). */
    private var shown by mutableStateOf(Shown(emptyList(), split = null))

    /** Where each page view sits on the inner screen, in window pixels. */
    private var paneViewRects by mutableStateOf(emptyList<Rect>())

    /** While a split's divider is dragged, the pages' shares of the width as drawn; they resize when it stops. */
    private var dividerSizes by mutableStateOf<List<Float>?>(null)

    /** A tab dragged out of the sidebar or rail, to split it with the page. */
    private var tabDrag by mutableStateOf<TabDrag?>(null)

    /** Every tab in a split, which keeps mobile sites unless [Settings.desktopSitesInSplit] is on. */
    private var splitTabIds = emptySet<String>()

    /**
     * The system bars around the inner screen: the cutout on the sides, the status bar on top while it shows,
     * and the navigation bar below when it has buttons (gesture navigation's handle sits over the page).
     */
    private var barInsets by mutableStateOf(Insets.NONE)

    /** The start screen's search palette. New Tab in the sidebar opens it too (board 9c). */
    private var paletteOpen by mutableStateOf(false)

    /** The hold menu opened from the sidebar or rail. */
    private var sidebarMenu by mutableStateOf<MenuTarget?>(null)

    /**
     * Whether Ensō muted each tab's media, with the media session it muted: Gecko doesn't report mute changes,
     * and a new session on the tab starts unmuted.
     */
    private val mutedMedia = mutableStateMapOf<String, Pair<MediaSession.Controller, Boolean>>()

    /** The space menu (board 3) and its picker, drawn over everything in [spaceWaveView]. */
    private val spaceWave = SpaceWaveState()
    private lateinit var spaceWaveView: ComposeView
    private lateinit var spaceMenuGesture: SpaceMenuGesture

    /** Menus and the palette register here while they're up, so a hold doesn't open the space menu over them. */
    private val spaceMenuBlocks = SpaceMenuBlocks()

    /** The bottom bar's open panel (boards B2–B4), if any. */
    private var barPanel by mutableStateOf<BarPanel?>(null)
    private var searchMode by mutableStateOf(SearchMode.NewQuickTab)

    /** How far the bottom bar has slid down off the screen, in pixels, as the anchor moves with the page. */
    private var barOffset by mutableFloatStateOf(0f)

    /** Bottom search's mic: the recognizer's best match is searched as if typed. */
    private val voiceSearchLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val said = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }
        if (result.resultCode == RESULT_OK && said != null) bottomBarActions.submit(said, searchMode)
    }

    private fun voiceSearchIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)

    /** Android's own permission dialog, for a site's request the user allowed; gets whether it was granted. */
    private var androidPermissionsAnswer: ((Boolean) -> Unit)? = null
    private val androidPermissions: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            // Approximate location alone is enough for a site, so any grant counts.
            androidPermissionsAnswer?.invoke(results.values.any { it })
            androidPermissionsAnswer = null
        }

    /** A download a site started, waiting on the user (step 12). */
    private var downloadPrompt by mutableStateOf<DownloadPrompt?>(null)

    /** Every download, newest first, for the Downloads page (polish pass). */
    private var downloadItems by mutableStateOf(emptyList<DownloadItem>())

    /** A download has just started: the chevron is a download button for a couple of seconds. */
    private var downloadJustStarted by mutableStateOf(false)
    private var downloadFlash: Job? = null

    /** The unfolded Downloads card, from the ⋮ menu. */
    private var downloadsCardOpen by mutableStateOf(false)

    /** Whether something was downloaded within [Settings.recentDownloadsHours], or still is. */
    private val recentDownloads: Boolean
        get() {
            val since = System.currentTimeMillis() - components.settings.recentDownloadsHours * 3_600_000L
            return downloadItems.any { it.status == DownloadItem.Status.Running || it.createdAt >= since }
        }

    private val downloadListActions = object : DownloadListActions {
        override fun open(id: String) {
            val download = components.store.state.downloads[id] ?: return
            val file = File(download.filePath)
            val opened = runCatching {
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.feature.downloads.fileprovider", file)
                val type = download.contentType?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                    ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
                    ?: "*/*"
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }.isSuccess
            // Nothing opens it (or it's gone): the Downloads folder instead.
            if (!opened) openFolder()
        }

        override fun retry(id: String) {
            val download = components.store.state.downloads[id] ?: return
            components.downloadsUseCases.removeDownload(id)
            components.store.dispatch(
                DownloadAction.AddDownloadAction(
                    download.copy(
                        id = UUID.randomUUID().toString(),
                        status = DownloadState.Status.INITIATED,
                        currentBytesCopied = 0,
                        createdTime = System.currentTimeMillis(),
                        notificationId = null,
                    ),
                ),
            )
        }

        override fun remove(id: String) = components.downloadsUseCases.removeDownload(id)

        override fun openFolder() {
            runCatching { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
        }
    }

    /** Downloads (step 12): Android Components' feature, with Ensō's prompt instead of its dialog. */
    private val downloadsFeature by lazy {
        DownloadsFeature(
            applicationContext = applicationContext,
            store = components.store,
            useCases = components.downloadsUseCases,
            onNeedToRequestPermissions = { permissions -> downloadPermissions.launch(permissions) },
            downloadFileUtils = components.downloadFileUtils,
            downloadManager = FetchDownloadManager(
                applicationContext,
                components.store,
                DownloadService::class,
                notificationsDelegate = NotificationsDelegate(NotificationManagerCompat.from(applicationContext)),
            ),
            customFirstPartyDownloadDialog = { download, _, positive, negative, _ ->
                downloadPrompt = DownloadPrompt(
                    fileName = download.value.fileName ?: download.value.url.substringAfterLast('/'),
                    size = download.value.contentLength,
                    onDownload = {
                        downloadPrompt = null
                        askForDownloadNotifications()
                        positive.value(download.value)
                    },
                    onCancel = {
                        downloadPrompt = null
                        negative.value()
                    },
                )
            },
        )
    }

    /**
     * The first download asks Android once for the notification permission, so its progress can show. Android
     * Components doesn't ask itself, and downloads without it, silently.
     */
    private fun askForDownloadNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || components.settings.downloadNotificationsAsked) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        components.settings.downloadNotificationsAsked = true
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private val notificationPermission: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Permissions Android Components' downloads ask for themselves (storage, before Android 10). */
    private val downloadPermissions: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val grants = results.values.map { if (it) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED }
            downloadsFeature.onPermissionsResult(results.keys.toTypedArray(), grants.toIntArray())
        }

    /** Sites' permission requests (step 11): the card, Android's dialog, and each site's remembered answers. */
    private val sitePermissions by lazy {
        SitePermissions(this, components.store, components.siteChoices) { permissions, then ->
            androidPermissionsAnswer = then
            androidPermissions.launch(permissions.toTypedArray())
        }
    }

    /** The page the Quick Tabs view zooms out of, captured as it opens. */
    private var quickTabsCapture by mutableStateOf<Pair<ImageBitmap, Rect>?>(null)

    /** The pages the last Clear All closed, for its undo. */
    private var clearedQuickTabs = emptyList<String>()

    /** Whether the touch going on has had two fingers down, so the page's scrolling then is a pinch's. */
    private var pinching = false

    /** The sign-in page's tab, closed once the account is signed in. */
    private var signInTabId: String? = null

    private var statusBarInset by mutableIntStateOf(0)

    /**
     * On the cover, the gesture-navigation handle the page runs under (0 with a bar of buttons, or while the
     * keyboard is up). The tab sheet keeps its search field above it.
     */
    private var gestureHandleInset by mutableIntStateOf(0)
    /** The bottom bar's height at rest, gesture handle included, in pixels; 0 until it's laid out. */
    private var barHeight = 0

    /** The tinted strip under the status bar, in pixels; 0 unless [CoverStatusBar.TintedStrip]. */
    private var bandHeight = 0
    private var barCollapsed = false
    private var canGoBack = false
    private var imeWasVisible = false

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (editing) editing = false else components.sessionUseCases.goBack()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // Before any view takes a color.
        applyTheme()

        inner = isInnerScreen(resources.configuration)
        // Before any tab gets an engine session, so the restored ones start in the right mode.
        updateDesktopSites()
        // With nothing to restore, the start screen shows.
        if (components.store.state.selectedTab == null) components.tabs.restore()
        selectedTabId = components.store.state.selectedTabId
        sidebarExpanded = components.settings.innerSidebarExpanded
        sidebarWidthDp = components.settings.innerSidebarWidthDp
        sidebarFullDp = sidebarWidthDp
        sidebarShownDp = if (sidebarExpanded) sidebarWidthDp else RailWidth.value

        engineView = components.engine.createView(this)
        engineView.setActivityContext(this)
        pagePanes = PagePanes(
            activity = this,
            store = components.store,
            main = engineView,
            createView = { components.engine.createView(this).also { it.setActivityContext(this) } },
            // Under the top bar and the overlay, like the first page.
            addContainer = { root.addView(it, root.indexOfChild(engineContainer) + 1) },
            load = { tabId -> if (tabId in shown.tabIds) components.tabs.load(listOf(tabId)) },
        )
        engineContainer = pagePanes.panes.first().container
        barAnchor = ScrollingBarView(this).apply { isVisible = false }
        statusBand = ScrollingBarView(this).apply { isVisible = false }
        bottomEdge = View(this).apply {
            isVisible = false
            contentDescription = getString(R.string.action_show_bar)
            setOnClickListener { expandBar() }
        }
        overlay = ComposeView(this).apply {
            setContent {
                EnsoTheme {
                    CompositionLocalProvider(LocalSpaceMenuBlocks provides spaceMenuBlocks, LocalFavicons provides components.favicons) {
                        OverlayContent()
                    }
                }
            }
        }
        root = CoordinatorLayout(this).apply {
            setBackgroundColor(EnsoColors.Page.toArgb())
            addView(engineContainer)
            addView(statusBand)
            addView(barAnchor)
            addView(bottomEdge)
            addView(this@MainActivity.overlay, CoordinatorLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        setContentView(root)
        // Beside the root rather than in it, so the tint covers the system bars too.
        spaceWaveView = ComposeView(this).apply {
            isVisible = false
            setContent { EnsoTheme { SpaceWave(spaceWave, onPick = ::addToSpace) } }
        }
        addContentView(spaceWaveView, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        spaceMenuGesture = SpaceMenuGesture(
            host = root,
            holdMs = { components.settings.spaceMenuHoldMs },
            opensAt = ::spaceMenuOpensAt,
            dispatchToViews = { window.superDispatchTouchEvent(it) },
            listener = spaceMenuListener,
        )

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val statusTop = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars()).top
            val edges = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime(),
            )
            val bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout())
            // Gesture navigation's handle is a thin strip the page can sit under, as in most apps; a bar of
            // buttons keeps its own space.
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val gestureNav = navBottom <= GESTURE_NAV_MAX_DP * resources.displayMetrics.density
            barInsets = Insets.of(
                bars.left,
                insets.getInsets(WindowInsetsCompat.Type.statusBars()).top,
                bars.right,
                if (gestureNav) 0 else navBottom,
            )
            if (inner) {
                // The frame and sidebar draw under the system bars themselves; only the keyboard moves things up.
                view.updatePadding(left = 0, top = 0, right = 0, bottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
                layoutInner()
            } else {
                // With gesture navigation the page runs under the handle; the keyboard still pushes it up.
                val keyboardOrCutout = insets.getInsets(WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout()).bottom
                val bottom = if (gestureNav) keyboardOrCutout else edges.bottom
                gestureHandleInset = (navBottom - bottom).coerceAtLeast(0).takeIf { gestureNav } ?: 0
                view.updatePadding(
                    left = edges.left,
                    // The page runs up under the status bar; what's there depends on the setting.
                    top = 0,
                    right = edges.right,
                    bottom = bottom,
                )
                layoutCoverBars(statusTop)
            }
            // Back with the keyboard up only closes the keyboard; treat that as cancelling the edit too.
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (imeWasVisible && !imeVisible) {
                editing = false
                if (barPanel == BarPanel.Search) barPanel = null
            }
            imeWasVisible = imeVisible
            layoutFloating()
            WindowInsetsCompat.CONSUMED
        }
        root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            // Rotating the unfolded screen resizes the window without changing the layout, and the insets
            // arrive before the new size does, so the page card is laid out again once the size is in.
            val resized = right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop
            if (inner && resized) root.post { if (inner) layoutInner() }
            if (resized) root.post { layoutFloating() }
        }
        root.viewTreeObserver.addOnPreDrawListener {
            // The Compose bar follows the anchor that the page's scrolling slides.
            barOffset = barAnchor.translationY
            val collapsed = barHeight > 0 && barAnchor.translationY >= barHeight - 1f
            if (collapsed != barCollapsed) {
                barCollapsed = collapsed
                onBarCollapsedChanged(collapsed)
            }
            true
        }
        thumbnails = BrowserThumbnails(this, engineView, components.store)

        // Links that open a new window (target=_blank, window.open) become tabs.
        windowFeature = WindowFeature(components.store, components.tabsUseCases)
        // Mozilla's sign-in page talks to the account manager over a web channel.
        webChannelFeature = FxaWebChannelFeature(
            customTabSessionId = null,
            runtime = components.engine,
            store = components.store,
            accountManager = components.accountManager,
            serverConfig = MozillaAccount.serverConfig,
        )
        onBackPressedDispatcher.addCallback(this, backCallback)
        observeState()
        if (savedInstanceState == null) handleViewIntent(intent)
        if (savedInstanceState == null) handleWebNotificationClick(intent)
        handleDebugIntent(intent)
    }

    /**
     * Folding or unfolding: the page, its tab, the space and the scroll position stay as they are. Only the
     * layout around the page changes, and anything half-done on the old screen (an edit, the tab sheet) closes.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Android's dark theme arrives here too: the activity handles uiMode itself.
        applyTheme(newConfig)
        applyScreenLayout(newConfig)
    }

    /**
     * Switches between the cover's layout and the inner screen's when [config] calls for the other one: folding or
     * unfolding, rotating the unfolded screen, or the portrait layout setting changing.
     */
    private fun applyScreenLayout(config: Configuration) {
        val nowInner = isInnerScreen(config)
        if (nowInner == inner) return
        inner = nowInner
        updateDesktopSites()
        editing = false
        tabSheetOpen = false
        barPanel = null
        sidebarMenu = null
        // An open Glance stays and fits itself to the new screen; its backdrop was captured on the old one. An
        // extension's page lives in the bar's panel or the inner card, so it closes with them.
        floatingBackdrop = emptyList()
        closeExtensionPage()
        extensionsCardOpen = false
        settingsCard = null
        spaceMenuGesture.reset()
        spaceWave.close()
        // Thumbnails capture the cover's page; unfolded, the first page view may show another tab of a split.
        if (inner) thumbnails.stop() else if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) thumbnails.start()
        // Make the bars lay themselves out again when they come back.
        if (!inner) barHeight = 0
        ViewCompat.requestApplyInsets(root)
        updateStatusBar()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) focusPageAt(event.x, event.y)
        // A second finger means a pinch, until the next touch starts.
        pinching = event.pointerCount > 1 || (pinching && event.actionMasked != MotionEvent.ACTION_DOWN)
        return spaceMenuGesture.onTouchEvent(event) || super.dispatchTouchEvent(event)
    }

    /**
     * In a split, touching a page that isn't focused focuses it, and the touch goes on to the page as usual.
     * GeckoView keeps touches from its parents, so this watches them on their way in. The divider's touch area
     * reaches into the pages, and touches there are the divider's.
     */
    private fun focusPageAt(x: Float, y: Float) {
        if (shown.split == null || pagesCovered) return
        if (startScreenVisible || sidebarMenu != null || zenSyncSheetOpen || tabDrag != null || dividerSizes != null) return
        val split = shown.split ?: return
        val cards = pageCards().map { it.card }
        val reach = SplitDividerReach.value * resources.displayMetrics.density
        val onDivider = splitGaps(cards, split.layout).any { (_, gap, across) ->
            if (across) y >= gap.top - reach && y <= gap.bottom + reach else x >= gap.left - reach && x <= gap.right + reach
        }
        if (onDivider) return
        val index = cards.indexOfFirst { it.contains(Offset(x, y)) }
        val tabId = shown.tabIds.getOrNull(index) ?: return
        if (tabId == selectedTabId) return
        editing = false
        components.tabs.select(tabId)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
        handleWebNotificationClick(intent)
        handleDebugIntent(intent)
    }

    private fun downloadItemOf(download: DownloadState) = DownloadItem(
        id = download.id,
        fileName = download.fileName ?: download.url.substringAfterLast('/'),
        size = download.contentLength,
        createdAt = download.createdTime,
        status = when (download.status) {
            DownloadState.Status.INITIATED, DownloadState.Status.DOWNLOADING -> DownloadItem.Status.Running
            DownloadState.Status.PAUSED -> DownloadItem.Status.Paused
            DownloadState.Status.FAILED -> DownloadItem.Status.Failed
            DownloadState.Status.CANCELLED -> DownloadItem.Status.Cancelled
            DownloadState.Status.COMPLETED -> DownloadItem.Status.Done
        },
        progress = download.contentLength?.takeIf { it > 0 }?.let { (download.currentBytesCopied.toFloat() / it).coerceIn(0f, 1f) },
    )

    /** A site's notification was tapped: the page hears of it, and its tab shows if it's still open. */
    private fun handleWebNotificationClick(intent: Intent?) {
        if (intent?.action != SiteNotifications.ACTION_WEB_NOTIFICATION) return
        intent.action = null
        IntentCompat.getParcelableExtra(intent, SiteNotifications.EXTRA_WEB_NOTIFICATION, Parcelable::class.java)?.let {
            components.engine.handleWebNotificationClick(it, null)
        }
        val host = SiteChoices.hostOf(intent.getStringExtra(SiteNotifications.EXTRA_WEB_NOTIFICATION_URL)) ?: return
        components.store.state.tabs.firstOrNull { SiteChoices.hostOf(it.content.url) == host }?.let {
            components.tabs.select(it.id)
            startScreenRequested = false
        }
    }

    /** A web link from another app (Ensō as the default browser) opens in a Quick Tab. */
    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val url = intent.dataString?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return
        // Taken once, so the same intent doesn't open it again.
        intent.action = null
        components.tabs.openNewTab(url, quick = true)
        startScreenRequested = false
    }

    /**
     * Debug builds only, through `adb shell am start -n app.enso.browser/.MainActivity` (plus
     * `--activity-single-top` while the app runs, so the intent reaches `onNewIntent`) with:
     * - `--ez enso_restore_zen_snapshot true`: puts Zen's spaces collection back as it was before the phone's
     *   first write.
     * - `--es enso_debug_open <url>`: opens the URL in a Quick Tab, for test pages. With `--ez enso_debug_kept true`
     *   it's a normal tab instead, which syncs to Zen.
     * - `--es enso_debug_split <tabId>`: splits the tab on screen with that tab, as a drop on the page does, and
     *   `--es enso_debug_unsplit <splitId>` ends a split. Both sync, for testing splits with Zen on the cover.
     * - `--ez enso_debug_desktop_sites true|false`: switches desktop sites on or off whatever the screen, until
     *   the next fold or unfold.
     * - `--ez enso_debug_update_extensions true`: checks every extension for an update now, rather than daily.
     */
    private fun handleDebugIntent(intent: Intent?) {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable || intent == null) return
        if (intent.getBooleanExtra(EXTRA_RESTORE_ZEN_SNAPSHOT, false)) {
            intent.removeExtra(EXTRA_RESTORE_ZEN_SNAPSHOT)
            components.zenSync.restoreSnapshot()
        }
        intent.getStringExtra(EXTRA_DEBUG_OPEN)?.let { url ->
            intent.removeExtra(EXTRA_DEBUG_OPEN)
            val kept = intent.getBooleanExtra(EXTRA_DEBUG_KEPT, false)
            intent.removeExtra(EXTRA_DEBUG_KEPT)
            components.tabs.openNewTab(url, quick = !kept)
            startScreenRequested = false
            if (kept) components.zenSync.localEdited()
        }
        intent.getStringExtra(EXTRA_DEBUG_SPLIT)?.let { tabId ->
            intent.removeExtra(EXTRA_DEBUG_SPLIT)
            val selected = selectedTabId ?: return@let
            components.spaces.update { it.splitWith(selected, tabId) }
            components.zenSync.localEdited()
        }
        intent.getStringExtra(EXTRA_DEBUG_UNSPLIT)?.let { splitId ->
            intent.removeExtra(EXTRA_DEBUG_UNSPLIT)
            components.spaces.update { it.unsplit(splitId) }
            components.zenSync.localEdited()
        }
        if (intent.hasExtra(EXTRA_DEBUG_DESKTOP_SITES)) {
            val desktop = intent.getBooleanExtra(EXTRA_DEBUG_DESKTOP_SITES, false)
            intent.removeExtra(EXTRA_DEBUG_DESKTOP_SITES)
            components.desktopSites.setActive(desktop, components.store)
        }
        if (intent.getBooleanExtra(EXTRA_DEBUG_UPDATE_EXTENSIONS, false)) {
            intent.removeExtra(EXTRA_DEBUG_UPDATE_EXTENSIONS)
            components.extensions.updateNow()
        }
    }

    override fun onStart() {
        downloadsFeature.start()
        super.onStart()
        val settings = components.settings
        // Anything that expired while we were away goes, even the tab that was on screen.
        components.tabs.clearExpiredQuickTabs(settings.quickTabLifetimeMs, keep = emptyList())
        val away = settings.lastStoppedAt?.let { System.currentTimeMillis() - it }
        if (away != null && settings.startScreenAfterMinutes > 0 && away >= settings.startScreenAfterMs) startScreenRequested = true
        windowFeature.start()
        webChannelFeature.start()
        if (!inner) thumbnails.start()
        popupObserver.start()
        optionsPageObserver.start()
        components.zenSync.syncNow(minIntervalMs = ZEN_SYNC_MIN_INTERVAL_MS)
    }

    override fun onStop() {
        downloadsFeature.stop()
        super.onStop()
        windowFeature.stop()
        webChannelFeature.stop()
        thumbnails.stop()
        popupObserver.stop()
        optionsPageObserver.stop()
        spaceMenuGesture.reset()
        spaceWave.close()
        if (!startScreenVisible) components.tabs.markViewed(shown.tabIds)
        components.settings.lastStoppedAt = System.currentTimeMillis()
        components.spaces.flush()
    }

    override fun onDestroy() {
        closeGlance()
        closeExtensionPage()
        floatingPageView?.release()
        pagePanes.release()
        super.onDestroy()
    }

    /**
     * Everything drawn over the page: on the cover, the start screen, the bottom bar with its panels, and the tab
     * sheet; unfolded, the frame and sidebar around the page card, with the start screen inside the card. The sync
     * sheet goes over both.
     */
    @Composable
    private fun OverlayContent() {
        // Read again whenever All settings changes one.
        val settings = remember(settingsRevision) { components.settings }
        val spaces by components.spaces.state.collectAsState()
        val installedExtensions by components.store.observeAsComposableState { extensionRows(it) }
        val extensionPrompts by components.extensions.prompts.collectAsState()
        val zenStatus by components.zenSync.status.collectAsState()
        // The site on screen, for its settings; none over the start screen.
        val siteOnScreen by components.store.observeAsComposableState { siteStateOf(it.selectedTab) }
        val site = siteOnScreen.takeUnless { startScreenVisible }
        val statusBarInset = with(LocalDensity.current) { statusBarInset.toDp() }
        if (inner) {
            // A plain container, so the rail and sidebar keep their own widths instead of the window's.
            Box(Modifier.fillMaxSize()) { InnerContent(spaces) }
        } else {
            // The start screen and the page cross-fade.
            AnimatedVisibility(startScreenVisible, enter = PageFadeIn, exit = PageFadeOut) {
                // Back from the start screen returns to the last tab, if there is one.
                BackHandler(enabled = startScreenVisible && selectedTabId != null) { startScreenRequested = false }
                // On the cover, tapping it opens bottom search rather than the palette.
                StartScreen(
                    spaces,
                    statusBarInset,
                    paletteOpen = false,
                    { if (it) bottomBarActions.newSearch() },
                    startScreenActions,
                    showEnso = settings.showEnsoOnNewTabs,
                )
            }
            val page by components.store.observeAsComposableState { it.selectedTab?.toPageState() ?: PageState() }
            // Glance covers the whole cover, bar included.
            // Find's "3/12": the last result Gecko reported for the tab on screen (its ordinal counts from 0).
            val findMatch by components.store.observeAsComposableState { state ->
                state.selectedTab?.content?.findResults?.lastOrNull()?.let { (it.activeMatchOrdinal + 1) to it.numberOfMatches }
            }
            if (glance == null) BottomBar(
                spaces = spaces,
                page = if (startScreenVisible) PageState() else page,
                selectedTabId = selectedTabId.takeIf { !startScreenVisible },
                panel = barPanel,
                searchMode = searchMode,
                scrollOffset = { barOffset },
                statusBarInset = statusBarInset,
                gestureInset = with(LocalDensity.current) { gestureHandleInset.toDp() },
                pageCapture = quickTabsCapture,
                findMatch = findMatch,
                downloads = BarDownloads(
                    items = downloadItems,
                    actions = downloadListActions,
                    justStarted = downloadJustStarted,
                    running = downloadItems.any { it.status == DownloadItem.Status.Running },
                    progress = downloadItems.firstOrNull { it.status == DownloadItem.Status.Running }?.progress,
                    recent = recentDownloads,
                ),
                thumbnails = components.thumbnailStorage,
                extensions = BarExtensions(
                    rows = installedExtensions,
                    actions = extensionListActions,
                    pageTitle = extensionPage?.title,
                    backdrop = floatingBackdrop,
                    onPageArea = ::placeExtensionPage,
                ),
                settings = BarSettings(site, siteSettingsActions) { modifier ->
                    AllSettingsContent(components.settings, zenStatus, allSettingsActions, modifier)
                },
                actions = bottomBarActions,
            )
        }
        LaunchedEffect(tabSheetOpen) {
            // A fresh capture each time it opens. The last one stays through the closing fade. Over the start
            // screen there's no page to capture, so the sheet is its plain colors.
            if (tabSheetOpen && !inner) {
                sheetBackdrop = null
                if (!startScreenVisible) sheetBackdrop = captureFrostedPage()
            }
        }
        AnimatedVisibility(
            visible = tabSheetOpen && !inner,
            // It comes up from the bottom bar, where the swipe that opens it starts.
            enter = fadeIn() + slideInVertically { it / 12 },
            exit = fadeOut() + slideOutVertically { it / 12 },
        ) {
            BackHandler { tabSheetOpen = false }
            FrostedOver(sheetBackdrop) { TabSheet(
                spaces = spaces,
                essentials = spaces.essentialsFor(perContainer = settings.essentialsPerContainer),
                selectedTabId = selectedTabId,
                statusBarInset = statusBarInset,
                navigationBarInset = with(LocalDensity.current) { gestureHandleInset.toDp() },
                quickTabLifetimeMs = settings.quickTabLifetimeMs,
                headerSwitchesSpaces = settings.switchSpacesFromSheetHeader,
                showQuickTabs = settings.quickTabsInTabSheet,
                actions = tabSheetActions,
            ) }
        }
        val glanceNow = glance
        val layout = floatingLayout
        val areaCorner = if (inner && settings.innerPageBorder) CardCorner else 0.dp
        if (glanceNow != null && layout != null) {
            // A plain container, so the overlay's exact window constraints don't stretch Glance's pieces.
            Box(Modifier.fillMaxSize()) {
                GlanceOverlay(
                    title = AddressInput.displayHost(glanceNow.url),
                    layout = layout,
                    backdrop = floatingBackdrop,
                    areaCorner = areaCorner,
                    buttons = listOfNotNull(
                        GlanceButton(R.drawable.ic_close, R.string.glance_close) { closeGlance() },
                        GlanceButton(R.drawable.ic_open_tab, R.string.glance_open_as_tab, ::openGlanceAsTab),
                        GlanceButton(R.drawable.ic_split, R.string.glance_split, ::splitGlance).takeIf { canSplitGlance() },
                    ),
                    onClose = { closeGlance() },
                )
            }
        }
        // Unfolded, an extension's popup or settings page is a card like Glance's, and the list a card of its own.
        val pageNow = extensionPage
        if (inner && pageNow != null && layout != null) {
            Box(Modifier.fillMaxSize()) {
                GlanceOverlay(
                    title = pageNow.title,
                    layout = layout,
                    backdrop = floatingBackdrop,
                    areaCorner = areaCorner,
                    buttons = listOf(GlanceButton(R.drawable.ic_close, R.string.glance_close) { closeExtensionPage() }),
                    onClose = { closeExtensionPage() },
                )
            }
        }
        AnimatedVisibility(inner && downloadsCardOpen && pageNow == null, enter = EnsoMotion.CardEnter, exit = EnsoMotion.CardExit) {
            Box(Modifier.fillMaxSize()) {
                SettingsCard(
                    cardRect,
                    areaCorner,
                    onBack = null,
                    onClose = { downloadsCardOpen = false },
                    header = {
                        SettingsHeader(null) { DownloadsTitle() }
                    },
                    cardModifier = Modifier.animateEnterExit(EnsoMotion.CardScaleIn, EnsoMotion.CardScaleOut),
                ) {
                    DownloadsList(downloadItems, downloadListActions)
                }
            }
        }
        AnimatedVisibility(inner && extensionsCardOpen && pageNow == null, enter = EnsoMotion.CardEnter, exit = EnsoMotion.CardExit) {
            Box(Modifier.fillMaxSize()) {
                ExtensionsCard(
                    cardRect,
                    areaCorner,
                    installedExtensions,
                    extensionListActions,
                    onClose = { extensionsCardOpen = false },
                    cardModifier = Modifier.animateEnterExit(EnsoMotion.CardScaleIn, EnsoMotion.CardScaleOut),
                )
            }
        }
        AnimatedCard(settingsCard.takeIf { inner && pageNow == null }) { page ->
            Box(Modifier.fillMaxSize()) {
                val shown = if (page == SettingsPage.Site && site == null) SettingsPage.All else page
                val back = { settingsCard = SettingsPage.Site }.takeIf { shown == SettingsPage.All && site != null }
                SettingsCard(
                    cardRect,
                    areaCorner,
                    onBack = back,
                    onClose = { settingsCard = null },
                    header = {
                        SettingsHeader(back) {
                            if (shown == SettingsPage.Site && site != null) SiteTitle(site) else AllSettingsTitle()
                        }
                    },
                    cardModifier = Modifier.animateEnterExit(EnsoMotion.CardScaleIn, EnsoMotion.CardScaleOut),
                ) {
                    if (shown == SettingsPage.Site && site != null) {
                        SiteSettingsContent(site, siteSettingsActions)
                    } else {
                        AllSettingsContent(components.settings, zenStatus, allSettingsActions)
                    }
                }
            }
        }
        AnimatedVisibility(zenSyncSheetOpen, enter = EnsoMotion.CardEnter, exit = EnsoMotion.CardExit) {
            BackHandler(enabled = zenSyncSheetOpen) { zenSyncSheetOpen = false }
            ZenSyncSheet(
                status = zenStatus,
                accent = ComposeColor(spaces.currentSpace.accent),
                onSignIn = ::signIn,
                onSyncNow = { components.zenSync.syncNow() },
                onSignOut = { components.zenSync.signOut() },
                onDismiss = { zenSyncSheetOpen = false },
                cardModifier = Modifier.animateEnterExit(EnsoMotion.CardScaleIn, EnsoMotion.CardScaleOut),
            )
        }
        // A download a site started, above the cover's bar.
        AnimatedCard(downloadPrompt) { prompt ->
            key(prompt) {
                DownloadPromptCard(
                    prompt,
                    ComposeColor(spaces.currentSpace.accent),
                    bottomInset = if (inner) 0.dp else with(LocalDensity.current) { BarMetrics.height(gestureHandleInset.toDp()) },
                    cardModifier = Modifier.animateEnterExit(EnsoMotion.CardScaleIn, EnsoMotion.CardScaleOut),
                )
            }
        }
        // A site's permission request for the tab on screen, above the cover's bar.
        AnimatedCard(sitePermissions.prompt) { prompt ->
            key(prompt) {
                PermissionPromptCard(
                    prompt,
                    ComposeColor(spaces.currentSpace.accent),
                    bottomInset = if (inner) 0.dp else with(LocalDensity.current) { BarMetrics.height(gestureHandleInset.toDp()) },
                    onAnswer = sitePermissions::answer,
                    onDismiss = sitePermissions::dismiss,
                    cardModifier = Modifier.animateEnterExit(EnsoMotion.CardScaleIn, EnsoMotion.CardScaleOut),
                )
            }
        }
        // Gecko's questions about extensions go over everything, one at a time.
        AnimatedCard(extensionPrompts.firstOrNull()) { prompt ->
            key(prompt) {
                ExtensionPromptCard(
                    prompt,
                    ComposeColor(spaces.currentSpace.accent),
                    onAnswer = { yes -> components.extensions.answer(prompt, yes) },
                    cardModifier = Modifier.animateEnterExit(EnsoMotion.CardScaleIn, EnsoMotion.CardScaleOut),
                )
            }
        }
    }

    /** The inner screen (boards 5, 6 and 9c): the frame, the sidebar or rail, and the start screen inside the card. */
    @Composable
    private fun InnerContent(spaces: SpacesState) {
        val settings = remember(settingsRevision) { components.settings }
        val page by components.store.observeAsComposableState { it.selectedTab?.toPageState() ?: PageState() }
        val playing by components.store.observeAsComposableState { mediaCardFor(it.tabs) }
        val media = playing?.let { card ->
            mutedMedia[card.tabId]?.takeIf { (controller, _) -> controller === card.controller }?.let { card.copy(muted = it.second) } ?: card
        }
        val density = LocalDensity.current
        val perContainer = settings.essentialsPerContainer
        val border = settings.innerPageBorder
        val corner = if (border) CardCorner else 0.dp
        // Under Glance the pages are hidden, and Glance's see-through hole would show a split's gaps and outline,
        // so the frame draws the page area as one card.
        val cards = if (pagesCovered) listOf(PageCard(cardRect)) else pageCards()
        InnerFrame(spaces.currentSpace.colors(), cards, focused = shown.tabIds.indexOf(selectedTabId).coerceAtLeast(0), page, corner)
        // Opening or closing, the sidebar is drawn at its full width and revealed as its edge moves, cross-fading
        // with the rail, so its rows don't lay out again on every frame.
        val rail = RailWidth.value
        val reveal = ((sidebarShownDp - rail) / (sidebarFullDp - rail)).coerceIn(0f, 1f)
        Box(
            with(density) { Modifier.padding(start = barInsets.left.toDp(), top = barInsets.top.toDp(), bottom = barInsets.bottom.toDp()) }
                .width(sidebarShownDp.dp)
                .fillMaxHeight()
                .clipToBounds(),
        ) {
            if (reveal > 0f) {
                Sidebar(
                    spaces = spaces,
                    essentialsFor = { space -> spaces.essentialsFor(space, perContainer) },
                    selectedTabId = selectedTabId,
                    page = page,
                    editing = editing,
                    newTabShowing = startScreenVisible,
                    media = media,
                    tabActions = tabSheetActions,
                    actions = sidebarActions,
                    onMenu = { sidebarMenu = it },
                    drag = tabDragActions,
                    width = maxOf(sidebarFullDp, sidebarShownDp).dp,
                    endPadding = if (border) 0.dp else EdgePadding,
                    modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true).graphicsLayer { alpha = reveal },
                )
            }
            if (reveal < 1f) {
                Rail(
                    spaces = spaces,
                    essentials = spaces.essentialsFor(perContainer = perContainer),
                    selectedTabId = selectedTabId,
                    newTabShowing = startScreenVisible,
                    tabActions = tabSheetActions,
                    actions = sidebarActions,
                    onMenu = { sidebarMenu = it },
                    drag = tabDragActions,
                    modifier = Modifier.graphicsLayer { alpha = 1f - reveal },
                )
            }
        }
        // No dividers under Glance either: nothing in Glance covers its page, so a divider there would take its touches.
        shown.split?.takeIf { !pagesCovered }?.let { split ->
            splitGaps(cards.map { it.card }, split.layout).forEach { (index, gap, across) ->
                SplitDivider(
                    gap = gap,
                    across = across,
                    onDragStart = ::startDividerDrag,
                    onDrag = { moveDivider(index, it) },
                    onDragEnd = ::endDividerDrag,
                    onReset = ::resetSplitSizes,
                )
            }
        }
        if (sidebarExpanded && reveal == 1f) {
            SidebarResizeHandle(
                edgeX = with(density) { barInsets.left.toDp() } + sidebarShownDp.dp,
                gap = if (border) BORDER_DP.dp else 0.dp,
                onDragStart = ::startSidebarResize,
                onDrag = ::resizeSidebar,
                onDragEnd = ::endSidebarResize,
            )
        }
        AnimatedVisibility(startScreenVisible && !cardRect.isEmpty, enter = PageFadeIn, exit = PageFadeOut) {
            BackHandler(enabled = startScreenVisible && selectedTabId != null) { startScreenRequested = false }
            Box(
                Modifier
                    .offset { IntOffset(cardRect.left.roundToInt(), cardRect.top.roundToInt()) }
                    .size(with(density) { cardRect.width.toDp() }, with(density) { cardRect.height.toDp() })
                    .clip(RoundedCornerShape(CardCorner)),
            ) {
                StartScreen(spaces, 0.dp, paletteOpen, { paletteOpen = it }, startScreenActions, centered = true, showEnso = settings.showEnsoOnNewTabs)
            }
        }
        sidebarMenu?.let { target ->
            BackHandler { sidebarMenu = null }
            TabMenu(
                target = target,
                spaces = spaces,
                selectedTabId = selectedTabId,
                quickTabLifetimeMs = settings.quickTabLifetimeMs,
                actions = tabSheetActions,
                onDismiss = { sidebarMenu = null },
            )
        }
        tabDrag?.let { drag ->
            if (canDropToSplit(drag.tabId)) {
                val zone = splitDropZone(cardRect, splitGap())
                SplitDropZone(zone, hovered = zone.contains(drag.finger), accent = spaces.currentSpace.colors().accent, corner = corner)
            }
            spaces.find(drag.tabId)?.tab?.let { DraggedTab(it, drag.finger) }
        }
    }

    /** The page cards as drawn, with where their pages are: one card, or a split's pages as Zen lays them out. */
    private fun pageCards(): List<PageCard> {
        val count = shown.tabIds.size.coerceAtLeast(1)
        val split = shown.split
        val drawn = paneRects(cardRect, count, dividerSizes ?: split?.sizes.orEmpty(), splitGap(), split?.layout ?: SplitLayout.SideBySide)
        return drawn.mapIndexed { index, card -> PageCard(card, paneViewRects.getOrNull(index) ?: card) }
    }

    /** The gap between a split's pages, the same as the page card's border (even with the border off). */
    private fun splitGap() = BORDER_DP * resources.displayMetrics.density

    private fun startDividerDrag() {
        val split = shown.split ?: return
        dividerSizes = splitShares(split.sizes, split.tabIds.size)
    }

    /** Moves the divider after page [index] by [delta] pixels, as drawn; the pages resize when the drag ends. */
    private fun moveDivider(index: Int, delta: Float) {
        val split = shown.split ?: return
        val count = split.tabIds.size
        val sizes = dividerSizes ?: return
        val length = (if (split.layout == SplitLayout.Stacked) cardRect.height else cardRect.width) - splitGap() * (count - 1)
        val min = SPLIT_PAGE_MIN_DP * resources.displayMetrics.density
        dividerSizes = dragDivider(sizes, count, index, delta, length, min)
    }

    private fun endDividerDrag() {
        val sizes = dividerSizes ?: return
        setSplitSizes(sizes)
        dividerSizes = null
    }

    /** Holding the divider: the pages go back to equal widths. */
    private fun resetSplitSizes() {
        dividerSizes = null
        setSplitSizes(emptyList())
    }

    /** Keeps the split's sizes (on the phone only) and resizes its pages right away, ahead of the model's update. */
    private fun setSplitSizes(sizes: List<Float>) {
        val split = shown.split ?: return
        components.spaces.update { it.resizeSplit(split.id, sizes) }
        showPanes(shown.copy(split = split.copy(sizes = sizes)))
    }

    /**
     * Whether dropping [tabId] on the page splits them: a single page of the current space shows, and neither
     * tab is an Essential.
     */
    private fun canDropToSplit(tabId: String): Boolean {
        val selected = selectedTabId ?: return false
        if (startScreenVisible || shown.split != null || tabId == selected) return false
        val state = components.spaces.state.value
        val current = state.find(selected)?.place ?: return false
        val dragged = state.find(tabId)?.place ?: return false
        return current !is TabPlace.Essential && dragged !is TabPlace.Essential && current.space?.id == dragged.space?.id
    }

    private val tabDragActions = object : TabDragActions {
        override fun start(tabId: String, finger: Offset) {
            // The hold opened the tab's menu; dragging on puts it away.
            sidebarMenu = null
            tabDrag = TabDrag(tabId, finger)
        }

        override fun move(finger: Offset) {
            tabDrag = tabDrag?.copy(finger = finger)
        }

        override fun drop(finger: Offset) {
            val drag = tabDrag ?: return
            tabDrag = null
            val selected = selectedTabId ?: return
            if (!canDropToSplit(drag.tabId) || !splitDropZone(cardRect, splitGap()).contains(finger)) return
            components.spaces.update { it.splitWith(selected, drag.tabId) }
            root.performHapticFeedback(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
            // Moving the dragged tab beside the page syncs; the split itself stays on the phone for now.
            components.zenSync.localEdited()
        }

        override fun cancel() {
            tabDrag = null
        }
    }

    /** Opens Mozilla's sign-in page in a Quick Tab; the web channel finishes signing in. */
    private fun signIn() {
        zenSyncSheetOpen = false
        lifecycleScope.launch {
            val url = components.zenSync.beginSignIn() ?: return@launch
            components.tabs.openNewTab(url, quick = true)
            signInTabId = components.store.state.selectedTabId
            startScreenRequested = false
        }
    }

    private val tabSheetActions = object : TabSheetActions {
        override fun select(tabId: String) {
            components.tabs.select(tabId)
            tabSheetOpen = false
            startScreenRequested = false
        }

        override fun close(tabId: String) {
            afterClosing(components.tabs.close(tabId))
            components.zenSync.localEdited()
        }

        override fun unload(tabId: String) = afterClosing(components.tabs.unload(tabId))

        override fun open(input: String) {
            components.tabs.openNewTab(AddressInput.toUrl(input), quick = true)
            tabSheetOpen = false
            startScreenRequested = false
        }

        override fun edit(transform: (SpacesState) -> SpacesState) {
            components.spaces.update(transform)
            components.zenSync.localEdited()
        }

        override fun switchSpace(step: Int) = components.tabs.switchSpace(step)

        override fun addToAnotherSpace(tabId: String) {
            val state = components.spaces.state.value
            val from = state.find(tabId)?.place?.space ?: state.currentSpace
            val others = state.spaces.filter { it.id != from.id }.map { it.toWaveSpace() }
            if (others.isEmpty()) return
            val leftHanded = components.settings.spaceMenuLeftHanded
            val screenHeight = window.decorView.height.toFloat()
            spaceWave.openPicker(tabId, others, from.toWaveSpace().tint, leftHanded, spaceMenuBounds(), screenHeight, Density(this@MainActivity))
            captureWaveBackdrop()
        }

        override fun dismiss() {
            tabSheetOpen = false
        }

        /** When the last tab went, leave the sheet for the start screen. */
        private fun afterClosing(nothingLeft: Boolean) {
            if (nothingLeft) tabSheetOpen = false
        }
    }

    private val bottomBarActions = object : BottomBarActions {
        override fun showPanel(panel: BarPanel?) {
            // Leaving an extension's page closes it.
            if (barPanel == BarPanel.ExtensionPage && panel != BarPanel.ExtensionPage) closeExtensionPage()
            if (panel == BarPanel.QuickTabs && barPanel != BarPanel.QuickTabs && !startScreenVisible) {
                // The Quick Tabs view zooms out of the page, so it opens once the page is captured.
                captureBackdrop { captures ->
                    quickTabsCapture = captures.firstOrNull()
                    barPanel = BarPanel.QuickTabs
                }
                return
            }
            if (panel == BarPanel.QuickTabs) quickTabsCapture = null
            // Leaving Find clears its highlights.
            if (barPanel == BarPanel.Find && panel != BarPanel.Find) find("")
            barPanel = panel
        }

        override fun newSearch() {
            searchMode = SearchMode.NewQuickTab
            barPanel = BarPanel.Search
        }

        override fun editUrl() {
            searchMode = SearchMode.CurrentTab
            barPanel = BarPanel.Search
        }

        override fun openTabSheet() {
            barPanel = null
            tabSheetOpen = true
        }

        override fun hideBar() {
            // Over the start screen there's no page to scroll it back.
            if (!startScreenVisible) collapseBar()
        }

        override fun select(tabId: String) {
            barPanel = null
            components.tabs.select(tabId)
            startScreenRequested = false
        }

        override fun close(tabId: String) {
            components.tabs.close(tabId)
        }

        override suspend fun searchSuggestions(query: String): List<String> =
            if (!components.settings.searchSuggestions || URLStringUtils.isURLLike(query)) emptyList() else components.searchSuggestions.fetch(query)

        override val voiceSearchAvailable by lazy { voiceSearchIntent().resolveActivity(packageManager) != null }

        override fun voiceSearch() {
            runCatching { voiceSearchLauncher.launch(voiceSearchIntent()) }
        }

        override fun keep(tabId: String) {
            components.spaces.update { it.keepQuickTab(tabId) }
            components.zenSync.localEdited()
        }

        override fun unkeep(tabId: String) {
            // Back to a Quick Tab: phone-only, so the next sync takes it out of the space in Zen too.
            components.spaces.update { state ->
                state.updateTab(tabId) { it.copy(quick = QuickTab(lastViewedAt = System.currentTimeMillis()), synced = false, zenUrl = null) }
            }
            components.zenSync.localEdited()
        }

        override fun keepElsewhere(tabId: String) {
            barPanel = null
            tabSheetActions.addToAnotherSpace(tabId)
        }

        override fun find(text: String) {
            val tab = components.store.state.selectedTab ?: return
            val session = tab.engineState.engineSession ?: return
            components.store.dispatch(ContentAction.ClearFindResultsAction(tab.id))
            if (text.isEmpty()) session.clearFindMatches() else session.findAll(text)
        }

        override fun findNext(forward: Boolean) {
            components.store.state.selectedTab?.engineState?.engineSession?.findNext(forward)
        }

        override fun focus(tabId: String) {
            components.tabs.select(tabId)
            startScreenRequested = false
        }

        override fun clearQuickTabs(): Int {
            barPanel = null
            val cleared = components.spaces.state.value.currentSpace.tabs.filter { it.quick != null }
            clearedQuickTabs = cleared.map { it.url }
            cleared.forEach { components.tabs.close(it.id) }
            return cleared.size
        }

        override fun undoClearQuickTabs() {
            // Their pages open again, though not their back history.
            clearedQuickTabs.forEach { components.tabs.openNewTab(it, quick = true) }
            clearedQuickTabs = emptyList()
            startScreenRequested = false
        }

        override fun back() = components.sessionUseCases.goBack()

        override fun forward() = components.sessionUseCases.goForward()

        override fun reload() = components.sessionUseCases.reload()

        override fun stop() = components.sessionUseCases.stopLoading()

        override fun copyUrl() {
            barPanel = null
            val url = components.store.state.selectedTab?.content?.url ?: return
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(AddressInput.displayHost(url), url))
        }

        override fun share() {
            barPanel = null
            val content = components.store.state.selectedTab?.content ?: return
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, content.url)
                putExtra(Intent.EXTRA_SUBJECT, content.title)
            }
            startActivity(Intent.createChooser(send, null))
        }

        override fun openSettings() {
            barPanel = if (siteNow() != null) BarPanel.SiteSettings else BarPanel.AllSettings
        }

        override fun submit(input: String, mode: SearchMode) {
            barPanel = null
            val url = AddressInput.toUrl(input)
            if (mode == SearchMode.CurrentTab && !startScreenVisible) {
                components.sessionUseCases.loadUrl(url)
            } else {
                components.tabs.openNewTab(url, quick = true)
                startScreenRequested = false
            }
        }

        override suspend fun history(query: String, limit: Int) = withContext(Dispatchers.IO) {
            if (query.isNotBlank()) {
                components.historyStorage.getSuggestions(query, limit).map { HistoryMatch(it.url, it.title.orEmpty()) }
            } else {
                // Before typing, the latest pages.
                components.historyStorage.getVisitsPaginated(0, limit * 3L)
                    .distinctBy { it.url }
                    .take(limit)
                    .map { HistoryMatch(it.url, it.title.orEmpty()) }
            }
        }
    }

    /** A long press's link, for Glance: a link, or a linked image's link. */
    private fun glanceLink(hit: HitResult): String? = when (hit) {
        is HitResult.UNKNOWN -> hit.src
        is HitResult.IMAGE_SRC -> hit.uri
        else -> null
    }?.takeIf { it.startsWith("https://") || it.startsWith("http://") }

    /** Holding a link: Glance captures the pages on screen for its backdrop, then opens over them (board 8). */
    private fun openGlance(url: String, parentTabId: String) {
        if (pagesCovered || tabSheetOpen || barPanel != null || zenSyncSheetOpen || spaceWave.mode != null) return
        root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        captureBackdrop { backdrop -> showGlance(url, parentTabId, backdrop) }
    }

    /**
     * Captures the pages on screen, with where they are, for a blurred backdrop, then calls [then]. A capture that
     * never answers mustn't hold things up, so it gives up waiting after a moment.
     */
    private fun captureBackdrop(then: (List<Pair<ImageBitmap, Rect>>) -> Unit) {
        val visible = pagePanes.panes.filter { it.tabId != null && it.container.isVisible }
        val captures = mutableListOf<Pair<ImageBitmap, Rect>>()
        var waiting = visible.size
        var done = false
        val finish = {
            if (!done) {
                done = true
                then(captures.toList())
            }
        }
        visible.forEach { pane ->
            val at = IntArray(2).also(pane.container::getLocationInWindow)
            pane.view.captureThumbnail { bitmap ->
                if (bitmap != null) {
                    val bounds = Rect(at[0].toFloat(), at[1].toFloat(), (at[0] + bitmap.width).toFloat(), (at[1] + bitmap.height).toFloat())
                    captures += bitmap.asImageBitmap() to bounds
                }
                if (--waiting == 0) finish()
            }
        }
        if (visible.isEmpty()) finish() else root.postDelayed({ finish() }, BACKDROP_CAPTURE_TIMEOUT_MS)
    }

    /** The page on screen, captured and blurred for a frosted backdrop, or null when there's none to capture. */
    private suspend fun captureFrostedPage(): FrostedPage? {
        val (image, bounds) = suspendCancellableCoroutine<Pair<ImageBitmap, Rect>?> { continuation ->
            captureBackdrop { captures -> continuation.resume(captures.firstOrNull()) }
        } ?: return null
        return withContext(Dispatchers.Default) { FrostedPage(frostedCopy(image.asAndroidBitmap()), bounds) }
    }

    /**
     * Opens the preview in a custom tab, which stays out of the user's tabs and sync until it's opened as a tab or
     * split. The pages behind hide, since their blurred captures stand in for them and GeckoView's surfaces would
     * otherwise overlap under the card.
     */
    private fun showGlance(url: String, parentTabId: String, backdrop: List<Pair<ImageBitmap, Rect>>) {
        val tab = createCustomTab(url)
        glance = GlanceState(tab.id, url, parentTabId)
        floatingBackdrop = backdrop
        updateDesktopSites()
        components.store.dispatch(CustomTabListAction.AddCustomTabAction(tab))
        layoutFloating()
        floatingPage().show(tab.id)
        hidePages()
    }

    /** Closes Glance; its custom tab goes too unless it's becoming a tab ([remove] false). */
    private fun closeGlance(remove: Boolean = true): GlanceState? {
        val closing = glance ?: return null
        glance = null
        floatingLayout = null
        floatingBackdrop = emptyList()
        floatingPage().show(null)
        if (remove) components.store.dispatch(CustomTabListAction.RemoveCustomTabAction(closing.tabId))
        restorePages()
        updateDesktopSites()
        return closing
    }

    private fun hidePages() = pagePanes.panes.forEach { it.container.isInvisible = true }

    private fun restorePages() =
        pagePanes.panes.forEachIndexed { index, pane -> pane.container.isVisible = index == 0 || pane.tabId != null }

    /**
     * Places Glance, or an extension's page unfolded, for the screen it's on: over the page card unfolded, over the
     * whole cover folded. On the cover an extension's page sits in the bar's panel, which places it
     * ([placeExtensionPage]).
     */
    private fun layoutFloating() {
        if (glance == null && !(inner && extensionPage != null)) return
        val decor = window.decorView
        if (decor.width == 0) return
        val layout = if (inner) {
            glanceLayout(cardRect, cardRect, inner = true, resources.displayMetrics.density)
        } else {
            // It dims the whole cover, and keeps clear of the status bar and the gesture handle.
            val screen = Rect(0f, 0f, decor.width.toFloat(), (decor.height - root.paddingBottom).toFloat())
            val room = screen.copy(top = statusBarInset.toFloat(), bottom = screen.bottom - gestureHandleInset)
            glanceLayout(screen, room, inner = false, resources.displayMetrics.density)
        }
        floatingLayout = layout
        place(floatingPage().container, layout.page)
    }

    /** Glance's Split: unfolded, beside a single page of the current space that isn't an Essential. */
    private fun canSplitGlance(): Boolean {
        val selected = selectedTabId ?: return false
        if (!inner || startScreenVisible || shown.split != null) return false
        val place = components.spaces.state.value.find(selected)?.place ?: return false
        return place !is TabPlace.Essential
    }

    /** Glance's Open as tab: the preview becomes a tab below the one it was opened from, and shows. */
    private fun openGlanceAsTab() {
        val previewed = glance ?: return
        val content = components.store.state.findCustomTab(previewed.tabId)?.content
        closeGlance(remove = false)
        components.tabs.adopt(previewed.tabId, content?.url ?: previewed.url, content?.title.orEmpty(), previewed.parentTabId, select = true)
        startScreenRequested = false
        updateDesktopSites()
        components.zenSync.localEdited()
    }

    /** Glance's Split: the preview becomes a tab, split to the right of the page (unfolded only). */
    private fun splitGlance() {
        val previewed = glance ?: return
        val selected = selectedTabId ?: return
        val content = components.store.state.findCustomTab(previewed.tabId)?.content
        closeGlance(remove = false)
        components.tabs.adopt(previewed.tabId, content?.url ?: previewed.url, content?.title.orEmpty(), selected, select = false)
        components.spaces.update { it.splitWith(selected, previewed.tabId) }
        components.zenSync.localEdited()
    }

    /** An extension's popup (its engine session) or settings page (a custom tab), with the extension's name. */
    private data class ExtensionPage(val extensionId: String, val title: String, val popup: EngineSession?, val optionsTabId: String?)

    /** The settings of the site on screen (step 10), or null when there's no site: the start screen, an about: page. */
    private fun siteStateOf(tab: TabSessionState?): SiteState? {
        val content = tab?.content ?: return null
        if (!content.url.startsWith("https://") && !content.url.startsWith("http://")) return null
        val host = SiteChoices.hostOf(content.url) ?: return null
        val protection = tab.trackingProtection
        return SiteState(
            tabId = tab.id,
            url = content.url,
            host = host,
            title = content.title,
            secure = content.securityInfo.isSecure,
            issuer = content.securityInfo.issuer.ifEmpty { null },
            desktop = content.desktopMode,
            desktopChosen = components.desktopSites.chosen(content.url),
            trackingProtection = protection.enabled && !protection.ignoredOnTrackingProtection,
            blockedTrackers = protection.blockedTrackers.size,
        )
    }

    private fun siteNow(): SiteState? = siteStateOf(components.store.state.selectedTab).takeUnless { startScreenVisible }

    private val siteSettingsActions = object : SiteSettingsActions {
        override fun setDesktop(desktop: Boolean) {
            val site = siteNow() ?: return
            components.desktopSites.choose(components.store, site.tabId, site.url, desktop)
        }

        override fun setTrackingProtection(on: Boolean) {
            val site = siteNow() ?: return
            // Gecko keeps the sites let off; the page loads again to apply it.
            if (on) components.trackingProtection.removeException(site.tabId) else components.trackingProtection.addException(site.tabId)
            components.sessionUseCases.reload(site.tabId)
        }

        override fun permissions(host: String) = sitePermissions.choicesFor(host)

        override val permissionsRevision get() = sitePermissions.revision

        override fun setPermission(permission: SitePermission, choice: PermissionChoice) {
            val site = siteNow() ?: return
            sitePermissions.setChoice(site.host, permission, choice)
            components.syncGeckoPermission(site.url, permission, choice)
        }

        override fun clearSiteData() {
            val site = siteNow() ?: return
            sitePermissions.forget(site.host)
            // Gecko clears the whole site the host belongs to (its base domain), cookies and sign-ins included.
            components.engine.clearData(
                Engine.BrowsingData.select(Engine.BrowsingData.AUTH_SESSIONS, Engine.BrowsingData.ALL_SITE_DATA),
                host = site.host,
                onSuccess = { components.sessionUseCases.reload(site.tabId) },
                onError = { error -> Log.w(TAG, "Couldn't clear the data of ${site.host}", error) },
            )
        }

        override fun openAllSettings() {
            if (inner) settingsCard = SettingsPage.All else barPanel = BarPanel.AllSettings
        }
    }

    private val allSettingsActions = object : AllSettingsActions {
        // The sync sheet opens over All settings, which is still there when it closes.
        override fun openZenSync() {
            zenSyncSheetOpen = true
        }

        override fun changed(setting: Setting) = settingChanged(setting)
    }

    /** A preference changed in All settings: applied at once where it can be; the rest take effect where they're read. */
    private fun settingChanged(setting: Setting) {
        settingsRevision++
        when (setting) {
            Setting.Theme -> applyTheme()
            Setting.CoverStatusBar, Setting.HideStatusBarUnfolded, Setting.PageBorder -> {
                if (!inner) barHeight = 0
                ViewCompat.requestApplyInsets(root)
                updateStatusBar()
            }
            Setting.UnfoldedPortrait -> applyScreenLayout(resources.configuration)
            Setting.DesktopSitesUnfolded, Setting.DesktopSitesInSplit -> updateDesktopSites()
            Setting.TrackingProtection -> components.engineSettingsUseCases.updateTrackingProtection(components.trackingProtectionPolicy())
            Setting.UpdateExtensions -> components.extensions.updateSettingChanged()
            // Page zoom applies after a restart; the rest are read where they're used, the next time they are.
            else -> Unit
        }
    }

    private val extensionListActions = object : ExtensionListActions {
        override fun open(id: String) {
            val state = components.store.state
            val extension = state.extensions[id] ?: return
            val override = state.selectedTab?.extensionState?.get(id)?.browserAction
            val action = extension.browserAction?.let { global -> override?.let(global::copyWithOverride) ?: global }
            // Its popup arrives through the popup observer, which shows it.
            if (action != null) action.onClick() else settings(id)
        }

        override fun setEnabled(id: String, enabled: Boolean) = components.extensions.setEnabled(id, enabled)

        override fun settings(id: String) = openExtensionSettings(id)

        override fun remove(id: String) = components.extensions.uninstall(id)

        /** The add-on site opens in a Quick Tab, as sign-in does: the user won't keep it. */
        override fun findMore() {
            barPanel = null
            extensionsCardOpen = false
            components.tabs.openNewTab(Extensions.ADDONS_SITE_URL, quick = true)
            startScreenRequested = false
        }
    }

    /** AC's popup observer: an extension's toolbar button opened its popup. */
    private val popupObserver by lazy {
        WebExtensionPopupObserver(components.store, onOpenPopup = { extension ->
            extension.popupSession?.let { showExtensionPage(ExtensionPage(extension.id, extension.name.orEmpty(), it, null)) }
        })
    }

    /** AC's options page observer: an extension asked to show its settings. */
    private val optionsPageObserver by lazy {
        WebExtensionOptionsPageObserver(components.store, onOpenOptionsPage = { page ->
            components.store.dispatch(WebExtensionAction.ClearOptionsPageSession(page.instanceId))
            openOptionsUrl(page.url, page.name)
        })
    }

    /** An extension's settings page, from the list. Extensions that want it in a tab get a Quick Tab. */
    private fun openExtensionSettings(id: String) {
        val metadata = components.extensions.installed(id)?.getMetadata() ?: return
        val url = metadata.optionsPageUrl ?: return
        if (metadata.openOptionsPageInTab) {
            barPanel = null
            extensionsCardOpen = false
            components.tabs.openNewTab(url, quick = true)
            startScreenRequested = false
        } else {
            openOptionsUrl(url, metadata.name ?: components.store.state.extensions[id]?.name.orEmpty())
        }
    }

    private fun openOptionsUrl(url: String, name: String) {
        val tab = createCustomTab(url)
        components.store.dispatch(CustomTabListAction.AddCustomTabAction(tab))
        showExtensionPage(ExtensionPage(extensionId = "", title = name, popup = null, optionsTabId = tab.id))
    }

    /**
     * Shows an extension's page as Glance shows its preview: the pages behind are captured for a blurred backdrop
     * and hidden. On the cover it opens in the bar's panel, which then places the page; unfolded, in a card.
     */
    private fun showExtensionPage(page: ExtensionPage) {
        closeGlance()
        closeExtensionPage()
        captureBackdrop { backdrop ->
            floatingBackdrop = backdrop
            extensionPage = page
            extensionsCardOpen = false
            updateDesktopSites()
            val view = floatingPage()
            if (inner) {
                layoutFloating()
            } else {
                // Out of sight until the bar's panel has grown and says where the page goes.
                place(view.container, Rect.Zero)
                barPanel = BarPanel.ExtensionPage
            }
            page.popup?.let(view::showSession) ?: page.optionsTabId?.let(view::show)
            hidePages()
        }
    }

    /** The bar's panel says where an extension's page goes on the cover, or null once it's gone. */
    private fun placeExtensionPage(area: Rect?) {
        if (inner || extensionPage == null) return
        place(floatingPage().container, area ?: Rect.Zero)
    }

    /** Closes an extension's page: a popup's session closes, a settings page's custom tab goes. */
    private fun closeExtensionPage() {
        val page = extensionPage ?: return
        extensionPage = null
        floatingBackdrop = emptyList()
        floatingLayout = null
        floatingPage().show(null)
        page.popup?.let { session ->
            components.store.dispatch(WebExtensionAction.UpdatePopupSessionAction(page.extensionId, popupSession = null))
            session.close()
        }
        page.optionsTabId?.let { components.store.dispatch(CustomTabListAction.RemoveCustomTabAction(it)) }
        if (barPanel == BarPanel.ExtensionPage) barPanel = BarPanel.Extensions
        restorePages()
        updateDesktopSites()
    }

    private val sidebarActions = object : SidebarActions {
        override fun setExpanded(expanded: Boolean) = showSidebar(expanded)

        override fun back() = components.sessionUseCases.goBack()

        override fun forward() = components.sessionUseCases.goForward()

        override fun reload() = components.sessionUseCases.reload()

        override fun stop() = components.sessionUseCases.stopLoading()

        override fun setEditing(editing: Boolean) {
            this@MainActivity.editing = editing
        }

        override fun submit(input: String) = this@MainActivity.submit(input)

        override fun openSettings() {
            settingsCard = if (siteNow() != null) SettingsPage.Site else SettingsPage.All
        }

        override fun openExtensions() {
            extensionsCardOpen = true
        }

        override val recentDownloads get() = this@MainActivity.recentDownloads

        override fun openDownloads() {
            downloadsCardOpen = true
        }

        override fun setMuted(media: MediaCardState, muted: Boolean) {
            media.controller.muteAudio(muted)
            mutedMedia[media.tabId] = media.controller to muted
        }

        override fun newTab() {
            editing = false
            startScreenRequested = true
            paletteOpen = true
        }

        override fun switchToSpace(spaceId: String) {
            components.tabs.switchTo(spaceId)
            startScreenRequested = false
        }

        override fun switchSpace(step: Int): Boolean {
            if (!components.tabs.switchSpace(step)) return false
            startScreenRequested = false
            return true
        }

        override fun searchFromRail() {
            collapseAfterEdit = true
            showSidebar(true)
            editing = true
        }

        override fun openFolder(folderId: String) {
            components.spaces.update { state ->
                val folder = state.currentSpace.folders.firstOrNull { it.id == folderId }
                if (folder != null && !folder.expanded) state.toggleFolder(folderId) else state
            }
            showSidebar(true)
        }
    }

    /**
     * Desktop sites with the sidebar layout (and the setting on), except in splits unless that's on too; pages
     * switch the next time they load.
     */
    private fun updateDesktopSites() {
        val settings = components.settings
        // Glance's preview and extensions' settings pages are narrower than the page, so they ask for mobile sites, as
        // a split's pages do.
        val mobile = (if (settings.desktopSitesInSplit) emptySet() else splitTabIds) +
            setOfNotNull(glance?.tabId, extensionPage?.optionsTabId)
        components.desktopSites.setActive(inner && settings.desktopSitesUnfolded, components.store, mobile)
    }

    /** The pages on screen, left to right, and the split they make, if any. */
    private data class Shown(val tabIds: List<String>, val split: Split?)

    /** The selected tab's split while it shows on the inner screen; otherwise the selected tab alone. */
    private fun shownFor(state: SpacesState, selected: String?, inner: Boolean, startScreenRequested: Boolean): Shown {
        selected ?: return Shown(emptyList(), split = null)
        val onScreen = !startScreenRequested && state.isInCurrentSpace(selected, components.settings.essentialsPerContainer)
        val split = state.splitOf(selected)?.takeIf { inner && onScreen }
        return Shown(split?.tabIds ?: listOf(selected), split)
    }

    private fun showPanes(shown: Shown) {
        this.shown = shown
        shown.split?.let { components.tabs.load(it.tabIds) }
        pagePanes.show(shown.tabIds)
        if (pagesCovered) hidePages()
        if (inner) layoutInner()
    }

    /** Opens or closes the inner sidebar, which resizes the page card beside it. */
    private fun showSidebar(expanded: Boolean) {
        if (expanded == sidebarExpanded) return
        sidebarExpanded = expanded
        components.settings.innerSidebarExpanded = expanded
        val target = if (expanded) sidebarFullDp else RailWidth.value
        if (inner) animateSidebarTo(target) else sidebarShownDp = target
    }

    /** Slides the sidebar's edge to [target] dp. The page stays under the narrower of the two until it stops. */
    private fun animateSidebarTo(target: Float) {
        sidebarAnimator?.cancel()
        val from = sidebarShownDp
        pinnedPageDp = minOf(from, target)
        sidebarAnimator = ValueAnimator.ofFloat(from, target).apply {
            duration = SIDEBAR_ANIMATION_MS
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                sidebarShownDp = it.animatedValue as Float
                layoutInner()
            }
            doOnEnd {
                if (sidebarAnimator === it) {
                    sidebarAnimator = null
                    pinnedPageDp = null
                    layoutInner()
                }
            }
            start()
        }
    }

    /** Dragging the sidebar's edge: the page goes under the narrowest sidebar until the drag ends. */
    private fun startSidebarResize() {
        pinnedPageDp = SidebarMinWidth.value
        layoutInner()
    }

    private fun resizeSidebar(byDp: Float) {
        sidebarWidthDp = (sidebarFullDp + byDp).coerceIn(SidebarMinWidth.value, maxSidebarDp())
        sidebarFullDp = sidebarWidthDp
        sidebarShownDp = sidebarWidthDp
        layoutInner()
    }

    private fun endSidebarResize() {
        pinnedPageDp = null
        components.settings.innerSidebarWidthDp = sidebarWidthDp
        layoutInner()
    }

    /** The widest the sidebar can be dragged: its maximum, or 60% of the window when that's less. */
    private fun maxSidebarDp(): Float {
        val windowDp = window.decorView.width / resources.displayMetrics.density
        return minOf(SidebarMaxWidth.value, windowDp * 0.6f).coerceAtLeast(SidebarMinWidth.value)
    }

    private val startScreenActions = object : StartScreenActions {
        override fun search(input: String) = openQuickTab(AddressInput.toUrl(input))

        override fun switchTo(tabId: String) {
            components.tabs.select(tabId)
            startScreenRequested = false
        }

        override fun openHistory(url: String) = openQuickTab(url)

        override fun openTabSheet() {
            if (!inner) tabSheetOpen = true
        }

        override suspend fun history(query: String, limit: Int) = withContext(Dispatchers.IO) {
            components.historyStorage.getSuggestions(query, limit).map { HistoryMatch(it.url, it.title.orEmpty()) }
        }

        private fun openQuickTab(url: String) {
            components.tabs.openNewTab(url, quick = true)
            startScreenRequested = false
        }
    }

    /**
     * Whether a hold here opens the space menu: a strip up the wave's edge, from the bottom bar's top edge (or the
     * gesture handle while the bar is scrolled away) up to the status bar (spec: board 3). Not while editing, or
     * over a panel, a menu, the palette or the sync sheet.
     */
    private fun spaceMenuOpensAt(x: Float, y: Float): Boolean {
        // Unfolded, the sidebar switches spaces instead (spec: inner-screen gestures).
        if (inner || editing || barPanel != null || zenSyncSheetOpen || spaceWave.mode != null || spaceMenuBlocks.count > 0) return false
        if (components.spaces.state.value.spaces.size < 2) return false
        val decor = window.decorView
        val screenBottom = decor.height - root.paddingBottom
        // Above the gesture handle, so a hold doesn't fight the home gesture, and above the bar where it shows;
        // its top edge moves down as it slides away.
        val barTop = if (tabSheetOpen) screenBottom.toFloat() else screenBottom - barHeight + barOffset
        val bottom = minOf((screenBottom - gestureHandleInset).toFloat(), barTop)
        val bounds = spaceMenuBounds()
        val stripTop = bounds.bottom - bounds.height * components.settings.spaceMenuStripHeight
        val leftHanded = components.settings.spaceMenuLeftHanded
        return SpaceMenuZones.contains(x, y, decor.width.toFloat(), stripTop, bottom, leftHanded, Density(this))
    }

    private val spaceMenuListener = object : SpaceMenuGesture.Listener {
        override fun onOpen(touch: Offset): Boolean {
            val state = components.spaces.state.value
            val current = state.spaces.indexOfFirst { it.id == state.currentSpaceId }
            if (state.spaces.size < 2 || current < 0) return false
            val spaces = state.spaces.map { it.toWaveSpace() }
            val leftHanded = components.settings.spaceMenuLeftHanded
            val screenHeight = window.decorView.height.toFloat()
            spaceWave.openMenu(spaces, current, touch, leftHanded, spaceMenuBounds(), screenHeight, Density(this@MainActivity))
            captureWaveBackdrop()
            root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            excludeWaveFromSystemGestures(true)
            return true
        }

        override fun onMove(finger: Offset) {
            if (spaceWave.move(finger)) root.performHapticFeedback(SPACE_TICK)
        }

        override fun onRelease() {
            excludeWaveFromSystemGestures(false)
            spaceWave.release()?.let { spaceId ->
                components.tabs.switchTo(spaceId)
                startScreenRequested = false
            }
        }

        override fun onCancel() {
            excludeWaveFromSystemGestures(false)
            spaceWave.close()
        }
    }

    /**
     * Captures the page for the wave's frosted backdrop; the wave shows frost alone until it arrives. Over the
     * start screen or the tab sheet there's no page on screen to capture.
     */
    private fun captureWaveBackdrop() {
        if (startScreenVisible || tabSheetOpen) return
        val page = engineView.asView()
        val at = IntArray(2).also(page::getLocationInWindow)
        val opening = spaceWave.openings
        engineView.captureThumbnail { bitmap ->
            if (bitmap == null) return@captureThumbnail
            val bounds = Rect(at[0].toFloat(), at[1].toFloat(), (at[0] + bitmap.width).toFloat(), (at[1] + bitmap.height).toFloat())
            spaceWave.showBackdrop(opening, bitmap.asImageBitmap(), bounds)
        }
    }

    /** The screen minus the system bars, in window pixels: where the wave has to fit. */
    private fun spaceMenuBounds(): Rect {
        val decor = window.decorView
        val bars = ViewCompat.getRootWindowInsets(decor)?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
        return Rect(
            left = (bars?.left ?: 0).toFloat(),
            top = (bars?.top ?: 0).toFloat(),
            right = (decor.width - (bars?.right ?: 0)).toFloat(),
            bottom = (decor.height - (bars?.bottom ?: 0)).toFloat(),
        )
    }

    /**
     * While the menu is open, the stretch of the edges beside the wave ignores Android's back gesture (spec:
     * board 3); Android keeps at most 200 dp of it per edge. It only checks this when a touch starts, so it guards
     * the next touch; during the hold itself, the resting finger is what stops the back gesture.
     */
    private fun excludeWaveFromSystemGestures(exclude: Boolean) {
        val rects = if (!exclude || spaceWave.mode == null) {
            emptyList()
        } else {
            val shape = spaceWave.shape(1f)
            val margin = (WAVE_EXCLUSION_MARGIN_DP * resources.displayMetrics.density).toInt()
            val top = minOf(shape.y(0f), spaceWave.finger?.y ?: Float.MAX_VALUE).toInt() - margin
            val bottom = maxOf(shape.y(shape.count - 1f), spaceWave.finger?.y ?: 0f).toInt() + margin
            listOf(android.graphics.Rect(0, top, root.width, bottom))
        }
        ViewCompat.setSystemGestureExclusionRects(root, rects)
    }

    /** "Add to another space": the Quick Tab becomes a normal tab of [spaceId], and Ensō follows it there. */
    private fun addToSpace(tabId: String, spaceId: String) {
        components.spaces.update { it.keepQuickTabIn(tabId, spaceId).copy(currentSpaceId = spaceId).select(tabId) }
        components.tabs.select(tabId)
        tabSheetOpen = false
        startScreenRequested = false
        components.zenSync.localEdited()
    }

    /** The wave's view of a space: tinted with its Zen gradient, or its accent when the theme has no colors. */
    private fun Space.toWaveSpace() =
        WaveSpace(id, name, icon, tint = gradient.map(::ComposeColor).ifEmpty { listOf(ComposeColor(accent)) })

    /** Loads what was typed in the current tab. Over the start screen (the inner sidebar's field), it's a new Quick Tab. */
    private fun submit(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        if (startScreenVisible) {
            components.tabs.openNewTab(AddressInput.toUrl(text), quick = true)
            startScreenRequested = false
        } else {
            components.sessionUseCases.loadUrl(AddressInput.toUrl(text))
        }
        editing = false
    }

    private fun observeState() {
        lifecycleScope.launch {
            snapshotFlow { editing }.collect { isEditing ->
                updateBackCallback()
                if (!isEditing) {
                    WindowCompat.getInsetsController(window, root).hide(WindowInsetsCompat.Type.ime())
                    if (collapseAfterEdit) {
                        collapseAfterEdit = false
                        showSidebar(false)
                    }
                }
            }
        }
        lifecycleScope.launch {
            // The palette belongs to the start screen; it shouldn't reopen with the next one.
            snapshotFlow { startScreenVisible }.collect { if (!it) paletteOpen = false }
        }
        lifecycleScope.launch {
            components.store.flow(this@MainActivity)
                .map { it.selectedTabId }
                .distinctUntilChanged()
                .collect { selectedTabId = it }
        }
        lifecycleScope.launch {
            // The Downloads page's list, and the chevron's download button when one starts.
            var known: Set<String>? = null
            components.store.flow(this@MainActivity)
                .map { it.downloads }
                .distinctUntilChanged()
                .collect { downloads ->
                    downloadItems = downloads.values.filterNot { it.private }.sortedByDescending { it.createdTime }.map(::downloadItemOf)
                    val running = downloads.filterValues { it.status == DownloadState.Status.INITIATED || it.status == DownloadState.Status.DOWNLOADING }.keys
                    val before = known
                    known = (before ?: emptySet()) + running
                    // The first look only takes in what's already there, such as downloads restored from last time.
                    if (before != null && (running - before).isNotEmpty()) {
                        downloadJustStarted = true
                        downloadFlash?.cancel()
                        downloadFlash = lifecycleScope.launch {
                            delay(2_000)
                            downloadJustStarted = false
                        }
                    }
                }
        }
        lifecycleScope.launch {
            // Sites' permission requests, for the tab on screen.
            components.store.flow(this@MainActivity).collect { sitePermissions.onState(it) }
        }
        lifecycleScope.launch {
            // Which pages show: the selected tab, or its whole split on the inner screen.
            combine(
                components.spaces.state,
                components.store.flow(this@MainActivity).map { it.selectedTabId }.distinctUntilChanged(),
                snapshotFlow { inner to startScreenRequested },
            ) { spaces, selected, (isInner, startRequested) -> shownFor(spaces, selected, isInner, startRequested) }
                .distinctUntilChanged()
                .collect(::showPanes)
        }
        lifecycleScope.launch {
            components.spaces.state
                .map { state -> state.spaces.flatMapTo(HashSet()) { space -> space.splits.flatMap { it.tabIds } } }
                .distinctUntilChanged()
                .collect {
                    splitTabIds = it
                    updateDesktopSites()
                }
        }
        lifecycleScope.launch {
            snapshotFlow { spaceWave.mode != null }.collect { spaceWaveView.isVisible = it }
        }
        lifecycleScope.launch {
            snapshotFlow { startScreenVisible to tabSheetOpen }.collect { (startScreen, _) ->
                if (startScreen) editing = false
                updateStatusBar()
            }
        }
        lifecycleScope.launch {
            // Pull from Zen every few minutes while the app is open.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(ZEN_SYNC_INTERVAL_MS)
                    components.zenSync.syncNow()
                }
            }
        }
        lifecycleScope.launch {
            // Close the sign-in page once the account is signed in.
            components.zenSync.status.collect { status ->
                val tab = signInTabId
                if (status is ZenSyncStatus.SignedIn && tab != null) {
                    signInTabId = null
                    components.tabs.close(tab)
                }
            }
        }
        lifecycleScope.launch {
            // While the app is open: keep the Quick Tab on screen fresh, and clear expired ones.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(QUICK_TAB_CHECK_INTERVAL_MS)
                    if (!startScreenVisible) components.tabs.markViewed(shown.tabIds)
                    components.tabs.clearExpiredQuickTabs(components.settings.quickTabLifetimeMs, keep = shown.tabIds)
                }
            }
        }
        lifecycleScope.launch {
            components.store.flow(this@MainActivity)
                .map { it.selectedTab?.content?.canGoBack == true }
                .distinctUntilChanged()
                .collect {
                    canGoBack = it
                    updateBackCallback()
                }
        }
        lifecycleScope.launch {
            // Show the bar again whenever a new page starts loading.
            components.store.flow(this@MainActivity)
                .map { it.selectedTab?.content?.loading == true }
                .distinctUntilChanged()
                .collect { loading -> if (loading) expandBar() }
        }
        lifecycleScope.launch {
            // Holding a link opens Glance. Gecko reports what was held; nothing else uses it yet, so every report
            // is consumed here.
            components.store.flow(this@MainActivity)
                .map { state ->
                    state.tabs.firstNotNullOfOrNull { tab -> tab.content.hitResult?.let { tab.id to it } }
                        ?: state.customTabs.firstNotNullOfOrNull { tab -> tab.content.hitResult?.let { tab.id to it } }
                }
                .distinctUntilChanged()
                .collect { held ->
                    val (tabId, hit) = held ?: return@collect
                    components.store.dispatch(ContentAction.ConsumeHitResultAction(tabId))
                    // Links held inside a preview don't open another one.
                    val url = glanceLink(hit) ?: return@collect
                    if (components.store.state.findCustomTab(tabId) == null) openGlance(url, tabId)
                }
        }
        lifecycleScope.launch {
            // A popup can close itself (window.close()); its sheet goes with it.
            components.store.flow(this@MainActivity)
                .map { state -> extensionPage?.let { page -> page.popup != null && state.extensions[page.extensionId]?.popupSession == null } ?: false }
                .distinctUntilChanged()
                .collect { closed -> if (closed) closeExtensionPage() }
        }
        lifecycleScope.launch {
            // The tinted strip under the status bar runs through all of the space's colors, left to right.
            combine(components.spaces.state, snapshotFlow { EnsoColors.dark }) { state, _ -> state.currentSpace.colors().stops.map { it.toArgb() } }
                .distinctUntilChanged()
                .collect { stops ->
                    // A gradient needs two colors, so one color runs into itself.
                    val colors = if (stops.size > 1) stops.toIntArray() else IntArray(2) { stops.first() }
                    statusBand.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors)
                }
        }
        lifecycleScope.launch {
            // An open panel holds the bar in place, with the status bar showing above it.
            snapshotFlow { barPanel != null }.collect { open ->
                if (open) {
                    expandBar()
                    barAnchor.disableScrolling()
                    statusBand.disableScrolling()
                } else {
                    barAnchor.enableScrolling()
                    statusBand.enableScrolling()
                    WindowCompat.getInsetsController(window, root).hide(WindowInsetsCompat.Type.ime())
                }
                updateStatusBar()
            }
        }
    }

    /** Brings the bottom bar (and the band under the status bar) back after they've scrolled away. */
    private fun expandBar() {
        if (inner) return
        barAnchor.expand()
        statusBand.expand()
    }

    /** Slides the bottom bar (and the band under the status bar) away, as scrolling down does. */
    private fun collapseBar() {
        if (inner) return
        barAnchor.collapse()
        statusBand.collapse()
    }

    /** Leave back to the system (closing the app) unless we have an edit to cancel or a page to go back to. */
    private fun updateBackCallback() {
        backCallback.isEnabled = editing || canGoBack
    }

    /**
     * Sizes the cover's bars and (re)installs the behaviors that slide them with the page: the bottom bar's anchor,
     * and with [CoverStatusBar.TintedStrip] a strip of the space's color under the status bar ([statusTop] tall).
     * Runs again when the insets change, for example when folding, unfolding or showing the keyboard.
     */
    private fun layoutCoverBars(statusTop: Int) {
        val density = resources.displayMetrics.density
        val height = (BarMetrics.height((gestureHandleInset / density).dp).value * density).roundToInt()
        val band = if (components.settings.coverStatusBar == CoverStatusBar.TintedStrip) statusTop else 0
        if (height == barHeight && statusTop == statusBarInset && band == bandHeight) return
        barHeight = height
        bandHeight = band
        // Whatever the setting, the status bar can show over the tab sheet and panels, which leave room for it.
        statusBarInset = statusTop

        // Follow the page's real scroll offsets. The touch-gesture variant never sees gestures that start mid-page
        // (GeckoView blocks parent interception), so it can hide a bar but not bring it back.
        // The bars follow the page's scroll position rather than AC's deltas, which miss most of a slow scroll.
        val scrolling = SteadyScrollDeltas(engineView) { pinching }
        barAnchor.translationY = 0f
        barAnchor.isVisible = true
        barAnchor.layoutParams = CoordinatorLayout.LayoutParams(MATCH_PARENT, height).apply {
            gravity = Gravity.BOTTOM
            behavior = EngineViewScrollingDataBehavior(scrolling, barAnchor, DependencyGravity.Bottom)
        }
        statusBand.translationY = 0f
        statusBand.isVisible = band > 0
        statusBand.layoutParams = CoordinatorLayout.LayoutParams(MATCH_PARENT, band).apply {
            gravity = Gravity.TOP
            if (band > 0) behavior = EngineViewScrollingDataBehavior(scrolling, statusBand, DependencyGravity.Top)
        }
        engineContainer.layoutParams = CoordinatorLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
            behavior = EngineViewClippingBehavior(this@MainActivity, null, engineContainer, band, height)
        }
        bottomEdge.layoutParams = CoordinatorLayout.LayoutParams(
            MATCH_PARENT,
            gestureHandleInset + resources.getDimensionPixelSize(R.dimen.bottom_edge_min_height),
        ).apply { gravity = Gravity.BOTTOM }
        engineView.setDynamicToolbarMaxHeight(band + height)
        if (barPanel == null) {
            barAnchor.enableScrolling()
            statusBand.enableScrolling()
        }
    }

    /**
     * Lays out the inner screen: the page as a card beside the sidebar or rail, inset from the system bars.
     * Runs again when the insets change or the sidebar opens or closes.
     */
    private fun layoutInner() {
        val window = window.decorView
        if (window.width == 0) {
            root.post { if (inner) layoutInner() }
            return
        }
        val density = resources.displayMetrics.density
        // Fit the sidebar to this window (a rotation can leave it too wide) without changing the width the user set.
        sidebarFullDp = minOf(sidebarWidthDp, maxSidebarDp())
        if (sidebarExpanded && pinnedPageDp == null && sidebarAnimator == null) sidebarShownDp = sidebarFullDp
        // The same border on all four sides, between the page and the sidebar too.
        val border = if (components.settings.innerPageBorder) (BORDER_DP * density).toInt() else 0
        val left = barInsets.left + (sidebarShownDp * density).toInt() + border
        val top = barInsets.top + border
        val right = window.width - barInsets.right - border
        val bottom = window.height - barInsets.bottom - border
        cardRect = Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        barAnchor.isVisible = false
        statusBand.isVisible = false
        bottomEdge.isVisible = false
        val pageLeft = pinnedPageDp?.let { barInsets.left + (it * density).toInt() + border } ?: left
        // A split's pages share the card, at the sizes the divider last stopped at.
        val pageArea = Rect(pageLeft.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        val rects = paneRects(
            pageArea,
            shown.tabIds.size.coerceAtLeast(1),
            shown.split?.sizes.orEmpty(),
            splitGap(),
            shown.split?.layout ?: SplitLayout.SideBySide,
        )
        paneViewRects = rects
        pagePanes.panes.drop(1).forEachIndexed { index, pane -> rects.getOrNull(index + 1)?.let { place(pane.container, it) } }
        if (!place(engineContainer, rects.first())) return
        // No bar to slide away on the inner screen, and nothing left over from the cover's one sliding the page.
        engineContainer.translationY = 0f
        engineView.asView().translationY = 0f
        engineView.setDynamicToolbarMaxHeight(0)
        engineView.setVerticalClipping(0)
    }

    /** Puts a page's container at [rect] (window pixels) on the inner screen. False when it was there already. */
    private fun place(container: View, rect: Rect): Boolean {
        val left = rect.left.roundToInt()
        val top = rect.top.roundToInt()
        val width = rect.right.roundToInt() - left
        val height = rect.bottom.roundToInt() - top
        val params = container.layoutParams as? CoordinatorLayout.LayoutParams
        val unchanged = params != null && params.behavior == null && params.leftMargin == left && params.topMargin == top &&
            params.width == width && params.height == height
        if (unchanged) return false
        container.layoutParams = CoordinatorLayout.LayoutParams(width, height).apply {
            leftMargin = left
            topMargin = top
        }
        return true
    }

    private fun onBarCollapsedChanged(collapsed: Boolean) {
        bottomEdge.isVisible = collapsed && !inner
        updateStatusBar()
    }

    /**
     * Light or dark (step 10): as Android's dark theme is, unless [Settings.themeMode] picks one. Composables follow
     * [EnsoColors.dark] by themselves. This sets the views that took a color once, the system bars' icons, and the
     * scheme Gecko asks pages for, which follows the system by itself unless Ensō picked one.
     */
    private fun applyTheme(config: Configuration = resources.configuration) {
        val mode = components.settings.themeMode
        val dark = when (mode) {
            ThemeMode.System -> config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
        }
        EnsoColors.dark = dark
        components.engine.settings.preferredColorScheme = when (mode) {
            ThemeMode.System -> PreferredColorScheme.System
            ThemeMode.Light -> PreferredColorScheme.Light
            ThemeMode.Dark -> PreferredColorScheme.Dark
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        val page = EnsoColors.Page.toArgb()
        window.decorView.setBackgroundColor(page)
        if (::root.isInitialized) root.setBackgroundColor(page)
        if (::pagePanes.isInitialized) pagePanes.recolor()
        floatingPageView?.recolor()
    }

    /**
     * The status bar, by the settings. On the cover it always shows over the start screen, the tab sheet and the
     * bottom bar's panels; over the page it's hidden, always showing, or hiding with the bar ([CoverStatusBar]).
     */
    private fun updateStatusBar() {
        val controller = WindowCompat.getInsetsController(window, root)
        val show = if (inner) {
            // Hidden, the page card's thin border runs right to the top.
            !components.settings.hideStatusBarUnfolded
        } else {
            val overPage = !startScreenVisible && !tabSheetOpen && barPanel == null
            when (components.settings.coverStatusBar) {
                CoverStatusBar.Hidden -> !overPage
                CoverStatusBar.OverPage -> true
                CoverStatusBar.TintedStrip -> !(overPage && barCollapsed)
            }
        }
        if (show) controller.show(WindowInsetsCompat.Type.statusBars()) else controller.hide(WindowInsetsCompat.Type.statusBars())
    }

    /**
     * Whether to use the inner screen's layout: a window at least 600 dp wide at its narrowest (so a rotated cover
     * doesn't count), unless it's held in portrait and the setting says portrait uses the cover's layout.
     */
    private fun isInnerScreen(config: Configuration): Boolean {
        if (config.smallestScreenWidthDp < INNER_MIN_WIDTH_DP) return false
        val portrait = config.orientation == Configuration.ORIENTATION_PORTRAIT
        return !portrait || components.settings.unfoldedPortraitLayout == UnfoldedPortraitLayout.Desktop
    }

    private companion object {
        const val INNER_MIN_WIDTH_DP = 600

        /** The thin border of space color around the page card, the same on all four sides. */
        const val BORDER_DP = 4

        /** How far past the wave's ends its stretch of the edge ignores the back gesture. */
        const val WAVE_EXCLUSION_MARGIN_DP = 64

        /** The narrowest (or, stacked, the shortest) a split's page gets when its divider is dragged. */
        const val SPLIT_PAGE_MIN_DP = 240

        /** A navigation bar this short or shorter is gesture navigation's handle, not a bar of buttons. */
        const val GESTURE_NAV_MAX_DP = 32
        const val SIDEBAR_ANIMATION_MS = 240L

        const val QUICK_TAB_CHECK_INTERVAL_MS = 60_000L
        const val ZEN_SYNC_INTERVAL_MS = 5 * 60_000L
        const val ZEN_SYNC_MIN_INTERVAL_MS = 60_000L
        const val TAG = "Enso"
        const val EXTRA_RESTORE_ZEN_SNAPSHOT = "enso_restore_zen_snapshot"
        const val EXTRA_DEBUG_OPEN = "enso_debug_open"
        const val EXTRA_DEBUG_DESKTOP_SITES = "enso_debug_desktop_sites"
        const val EXTRA_DEBUG_UPDATE_EXTENSIONS = "enso_debug_update_extensions"
        const val EXTRA_DEBUG_KEPT = "enso_debug_kept"

        /** The start screen and pages cross-fading. */
        val PageFadeIn = fadeIn(tween(EnsoMotion.PAGE_FADE_MS))
        val PageFadeOut = fadeOut(tween(EnsoMotion.PAGE_FADE_MS))

        /** How long Glance or an extension's page waits for the pages' captures before opening without them. */
        const val BACKDROP_CAPTURE_TIMEOUT_MS = 400L
        const val EXTRA_DEBUG_SPLIT = "enso_debug_split"
        const val EXTRA_DEBUG_UNSPLIT = "enso_debug_unsplit"

        /** The tick for each space the space menu's selection moves through. */
        val SPACE_TICK = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.SEGMENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
    }
}
