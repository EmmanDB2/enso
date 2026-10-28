package app.enso.browser.sync

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.enso.browser.R
import app.enso.browser.ui.EnsoColors
import app.enso.browser.ui.EnsoType

/** Account and status for Zen sync, opened from the ⋮ menu. */
@Composable
fun ZenSyncSheet(
    status: ZenSyncStatus,
    accent: Color,
    onSignIn: () -> Unit,
    onSyncNow: () -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
    /** For the card itself, inside its dim layer: its entrance, say. */
    cardModifier: Modifier = Modifier,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.22f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(20.dp)
        Column(
            cardModifier
                .padding(16.dp)
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .shadow(16.dp, shape)
                .background(EnsoColors.Card, shape)
                .clip(shape)
                // Taps inside the card shouldn't reach the dim layer and close it.
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.zen_sync_title), style = EnsoType.SpaceName.copy(fontSize = 20.sp))
            when (status) {
                ZenSyncStatus.Starting -> Text(stringResource(R.string.zen_sync_starting), style = EnsoType.Secondary)
                ZenSyncStatus.SignedOut -> {
                    Text(stringResource(R.string.zen_sync_signed_out), style = EnsoType.Row)
                    Text(stringResource(R.string.zen_sync_both_ways), style = EnsoType.Secondary)
                    SheetButton(R.string.zen_sync_sign_in, accent, primary = true, onClick = onSignIn)
                }
                is ZenSyncStatus.SignedIn -> {
                    status.email?.let { Text(it, style = EnsoType.RowTitle) }
                    Text(stateText(status.state), style = EnsoType.Secondary)
                    Text(stringResource(R.string.zen_sync_both_ways), style = EnsoType.Secondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SheetButton(
                            if (status.state == ZenSyncStatus.State.NeedsSignIn) R.string.zen_sync_sign_in else R.string.zen_sync_sync_now,
                            accent,
                            primary = true,
                            onClick = if (status.state == ZenSyncStatus.State.NeedsSignIn) onSignIn else onSyncNow,
                        )
                        SheetButton(R.string.zen_sync_sign_out, accent, primary = false, onClick = onSignOut)
                    }
                }
            }
        }
    }
}

@Composable
private fun stateText(state: ZenSyncStatus.State): String = when (state) {
    ZenSyncStatus.State.Syncing -> stringResource(R.string.zen_sync_syncing)
    is ZenSyncStatus.State.Synced -> state.at?.let {
        stringResource(R.string.zen_sync_synced_at, DateUtils.getRelativeTimeSpanString(it).toString().lowercase())
    } ?: stringResource(R.string.zen_sync_synced_never)
    ZenSyncStatus.State.ZenSyncOff -> stringResource(R.string.zen_sync_off)
    ZenSyncStatus.State.NeedsUpdate -> stringResource(R.string.zen_sync_needs_update)
    ZenSyncStatus.State.NeedsSignIn -> stringResource(R.string.zen_sync_needs_sign_in)
    is ZenSyncStatus.State.Failed -> stringResource(R.string.zen_sync_failed, state.reason)
}

@Composable
private fun SheetButton(@StringRes label: Int, accent: Color, primary: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .height(44.dp)
            .clip(shape)
            .background(if (primary) accent else EnsoColors.FieldFill, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(label), style = EnsoType.MenuItem.copy(color = if (primary) Color.White else EnsoColors.TextPrimary))
    }
}
