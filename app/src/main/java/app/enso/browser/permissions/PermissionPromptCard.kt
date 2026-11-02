package app.enso.browser.permissions

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.enso.browser.R
import app.enso.browser.spacemenu.BlockSpaceMenu
import app.enso.browser.ui.EnsoColors
import app.enso.browser.ui.EnsoType

/**
 * A site's permission request (step 11), in a card near the bottom of the screen, above the cover's bar
 * ([bottomInset]): what the site wants, that the answer is remembered, and Don't allow or Allow. Back or a tap
 * outside refuses this time without remembering it.
 */
@Composable
fun PermissionPromptCard(
    prompt: PermissionPrompt,
    accent: Color,
    bottomInset: Dp,
    onAnswer: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    cardModifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDismiss)
    BlockSpaceMenu()
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.22f))
            .pointerInput(prompt) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.BottomCenter,
    ) {
        val shape = RoundedCornerShape(20.dp)
        Column(
            cardModifier
                .padding(start = 12.dp, end = 12.dp, bottom = bottomInset + 12.dp)
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .shadow(16.dp, shape)
                .background(EnsoColors.Card, shape)
                .clip(shape)
                // Taps inside the card shouldn't reach the dim layer and close it.
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).background(accent.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(iconOf(prompt.permissions.first())), contentDescription = null, Modifier.size(20.dp), tint = EnsoColors.TextPrimary)
                }
                Spacer(Modifier.width(12.dp))
                Text(stringResource(titleOf(prompt.permissions), prompt.host), style = EnsoType.RowTitle.copy(fontSize = 17.sp), modifier = Modifier.weight(1f))
            }
            Text(stringResource(R.string.permission_prompt_remembered), style = EnsoType.Secondary)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PromptButton(R.string.permission_prompt_deny, accent, primary = false, Modifier.weight(1f)) { onAnswer(false) }
                PromptButton(R.string.permission_prompt_allow, accent, primary = true, Modifier.weight(1f)) { onAnswer(true) }
            }
        }
    }
}

@Composable
private fun PromptButton(@StringRes label: Int, accent: Color, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier
            .height(44.dp)
            .clip(shape)
            .background(if (primary) accent else EnsoColors.FieldFill, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(label), style = EnsoType.MenuItem.copy(color = if (primary) Color.White else EnsoColors.TextPrimary))
    }
}

/** "[site] wants to use your camera", and the rest. Camera and microphone together get one card. */
@StringRes
private fun titleOf(permissions: List<SitePermission>): Int = when {
    SitePermission.Camera in permissions && SitePermission.Microphone in permissions -> R.string.permission_prompt_camera_microphone
    else -> when (permissions.first()) {
        SitePermission.Camera -> R.string.permission_prompt_camera
        SitePermission.Microphone -> R.string.permission_prompt_microphone
        SitePermission.Location -> R.string.permission_prompt_location
        SitePermission.Notifications -> R.string.permission_prompt_notifications
        SitePermission.ProtectedContent -> R.string.permission_prompt_drm
    }
}

/** Each permission's thin-line icon, in the card and in site settings. */
@DrawableRes
fun iconOf(permission: SitePermission): Int = when (permission) {
    SitePermission.Camera -> R.drawable.ic_camera
    SitePermission.Microphone -> R.drawable.ic_mic
    SitePermission.Location -> R.drawable.ic_location
    SitePermission.Notifications -> R.drawable.ic_bell
    SitePermission.ProtectedContent -> R.drawable.ic_play
}

/** Each permission's name in site settings. */
@StringRes
fun labelOf(permission: SitePermission): Int = when (permission) {
    SitePermission.Camera -> R.string.permission_camera
    SitePermission.Microphone -> R.string.permission_microphone
    SitePermission.Location -> R.string.permission_location
    SitePermission.Notifications -> R.string.permission_notifications
    SitePermission.ProtectedContent -> R.string.permission_drm
}
