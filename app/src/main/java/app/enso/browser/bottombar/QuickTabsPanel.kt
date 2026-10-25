package app.enso.browser.bottombar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.enso.browser.R
import app.enso.browser.spaces.Space
import app.enso.browser.spaces.Tab
import app.enso.browser.tabsheet.clearsIn
import app.enso.browser.ui.EnsoColors
import app.enso.browser.ui.EnsoType
import app.enso.browser.ui.LetterFavicon
import app.enso.browser.ui.SpaceIcon
import app.enso.browser.ui.displayTitle
import kotlinx.coroutines.launch
import mozilla.components.browser.thumbnails.storage.ThumbnailStorage

/**
 * The Quick Tabs (board B2): the current space's Quick Tabs as cards to swipe through, starting on the one on
 * screen or else the latest. Tap a card to open it, swipe it up (or tap its ✕) to close it, and keep the focused
 * one in this space from the pill below; holding the pill picks another space on the wave.
 */
@Composable
internal fun QuickTabsPanel(
    tabs: List<Tab>,
    space: Space,
    selectedTabId: String?,
    height: Dp,
    quickTabLifetimeMs: Long,
    thumbnails: ThumbnailStorage,
    actions: BottomBarActions,
) {
    if (tabs.isEmpty()) {
        // The last one was closed or kept.
        LaunchedEffect(Unit) { actions.showPanel(null) }
        Box(Modifier.fillMaxWidth().height(height))
        return
    }
    val start = remember {
        tabs.indexOfFirst { it.id == selectedTabId }.takeIf { it >= 0 }
            ?: tabs.indices.maxBy { tabs[it].quick?.lastViewedAt ?: 0L }
    }
    val pager = rememberPagerState(initialPage = start) { tabs.size }
    Column(Modifier.fillMaxWidth().height(height)) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 16.dp), verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.bar_quick_tabs), style = EnsoType.SpaceName.copy(fontSize = 28.sp))
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.bar_quick_tabs_open, tabs.size), style = EnsoType.Secondary)
        }
        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 72.dp),
            pageSpacing = 14.dp,
            key = { tabs[it].id },
        ) { index ->
            val tab = tabs[index]
            QuickTabCard(
                tab = tab,
                focused = index == pager.currentPage,
                remainingMs = remainingMs(tab, selectedTabId, quickTabLifetimeMs),
                thumbnails = thumbnails,
                onOpen = { actions.select(tab.id) },
                onClose = { actions.close(tab.id) },
            )
        }
        Spacer(Modifier.height(16.dp))
        val focused = tabs.getOrNull(pager.currentPage)
        KeepPill(
            space = space,
            onKeep = { focused?.let { actions.keep(it.id) } },
            onKeepElsewhere = { focused?.let { actions.keepElsewhere(it.id) } },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(16.dp))
        PageDots(tabs.size, pager.currentPage, Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(18.dp))
        BarDivider()
    }
}

/** How long [tab] has left. The one on screen restarts its clock when you leave it, so it has the full time. */
internal fun remainingMs(tab: Tab, selectedTabId: String?, lifetimeMs: Long): Long {
    val now = System.currentTimeMillis()
    val viewedAt = if (tab.id == selectedTabId) now else tab.quick?.lastViewedAt ?: now
    return lifetimeMs - (now - viewedAt)
}

@Composable
private fun QuickTabCard(
    tab: Tab,
    focused: Boolean,
    remainingMs: Long,
    thumbnails: ThumbnailStorage,
    onOpen: () -> Unit,
    onClose: () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    val lift = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val close by rememberUpdatedState(onClose)
    val haptics = LocalHapticFeedback.current
    // The side cards sit a little lower, so the focused one stands out.
    val drop by animateDpAsState(if (focused) 0.dp else 36.dp, label = "cardDrop")
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = drop, bottom = 36.dp - drop)
            .graphicsLayer {
                translationY = lift.value
                alpha = 1f - (-lift.value / size.height).coerceIn(0f, 0.7f)
            }
            // Swipe up to close.
            .pointerInput(tab.id) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        scope.launch { lift.snapTo((lift.value + amount).coerceAtMost(0f)) }
                    },
                    onDragEnd = {
                        scope.launch {
                            if (lift.value < -size.height * 0.22f) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                lift.animateTo(-size.height * 1.3f)
                                close()
                            } else {
                                lift.animateTo(0f)
                            }
                        }
                    },
                    onDragCancel = { scope.launch { lift.animateTo(0f) } },
                )
            }
            .shadow(if (focused) 14.dp else 6.dp, shape)
            .background(EnsoColors.CurrentTab, shape)
            .border(2.dp, EnsoColors.QuickTabOutlineOnLight, shape)
            .clip(shape)
            .clickable(onClick = onOpen),
    ) {
        Row(Modifier.fillMaxWidth().height(58.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            LetterFavicon(tab.title, tab.url, size = 28.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                tab.displayTitle(),
                style = EnsoType.RowTitle.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (focused) {
                IconButton(onClick = onClose) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.action_close_tab),
                        Modifier.size(22.dp),
                        tint = EnsoColors.TextSecondary,
                    )
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(EnsoColors.TextSecondary.copy(alpha = 0.12f)))
        TabThumbnail(
            tab.id,
            thumbnails,
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(EnsoColors.FieldFill),
        ) {
            LetterFavicon(tab.title, tab.url, size = 48.dp)
        }
        Row(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(EnsoColors.QuickTabOutlineOnLight, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.quick_tab_clears_in, clearsIn(remainingMs)), style = EnsoType.Secondary)
        }
    }
}

/** "Keep in [space]" under the focused card. Holding it picks another space on the wave. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeepPill(space: Space, onKeep: () -> Unit, onKeepElsewhere: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(26.dp)
    Row(
        modifier
            .height(52.dp)
            .shadow(8.dp, shape)
            .background(EnsoColors.CurrentTab, shape)
            .clip(shape)
            .combinedClickable(onClick = onKeep, onLongClick = onKeepElsewhere)
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpaceIcon(space.icon, size = 24.dp, tint = androidx.compose.ui.graphics.Color(space.accent), fallbackName = space.name)
        Spacer(Modifier.width(14.dp))
        Text(stringResource(R.string.bar_keep_in, space.name), style = EnsoType.RowTitle.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold))
    }
}

/** Which card is focused: a gold dash among gray dots. */
@Composable
private fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { index ->
            val width by animateDpAsState(if (index == current) 18.dp else 6.dp, label = "dot")
            Box(
                Modifier
                    .size(width = width, height = 6.dp)
                    .background(
                        if (index == current) EnsoColors.QuickTabOutlineOnLight else EnsoColors.TextSecondary.copy(alpha = 0.35f),
                        CircleShape,
                    ),
            )
        }
    }
}
