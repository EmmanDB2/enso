package app.enso.browser.extensions

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.enso.browser.R
import app.enso.browser.spacemenu.BlockSpaceMenu
import app.enso.browser.ui.EnsoColors
import app.enso.browser.ui.EnsoType
import app.enso.browser.ui.LetterFavicon

/**
 * A question or notice about an extension, for [ExtensionPromptCard]. Gecko asks before it installs one from the
 * add-on site, before an update that needs more permissions, and when an extension asks for optional ones.
 */
class ExtensionPrompt(
    val kind: Kind,
    val extensionId: String?,
    val name: String,
    /** What it will be able to do, in the user's words. */
    val permissions: List<String> = emptyList(),
    /** What its developer says it collects, as one phrase. */
    val dataCollection: String? = null,
    /** Why an install failed. */
    @StringRes val failure: Int? = null,
    val loadIcon: (suspend (Int) -> Bitmap?)? = null,
    /** Takes the user's answer. Notices answer false. */
    val answer: (Boolean) -> Unit = {},
) {
    enum class Kind { Install, Update, MorePermissions, Added, Failed }
}

/**
 * The prompt in a card over a dimmed screen, like the sync sheet: the extension's icon and name, what it asks
 * for, and the answers. Back or a tap outside says no.
 */
@Composable
fun ExtensionPromptCard(
    prompt: ExtensionPrompt,
    accent: Color,
    onAnswer: (Boolean) -> Unit,
    /** For the card itself, inside its dim layer: its entrance, say. */
    cardModifier: Modifier = Modifier,
) {
    BackHandler { onAnswer(false) }
    BlockSpaceMenu()
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.22f))
            .pointerInput(prompt) { detectTapGestures { onAnswer(false) } },
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(20.dp)
        Column(
            cardModifier
                .padding(16.dp)
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .shadow(16.dp, shape)
                .background(EnsoColors.Card, shape)
                .clip(shape)
                // Taps inside the card shouldn't reach the dim layer and close it.
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PromptIcon(prompt)
                Spacer(Modifier.width(14.dp))
                Text(title(prompt), style = EnsoType.SpaceName.copy(fontSize = 20.sp), modifier = Modifier.weight(1f))
            }
            PromptBody(prompt)
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val (no, yes) = answers(prompt.kind)
                no?.let { PromptButton(it, accent, primary = false) { onAnswer(false) } }
                PromptButton(yes, accent, primary = true) { onAnswer(prompt.kind != ExtensionPrompt.Kind.Added && prompt.kind != ExtensionPrompt.Kind.Failed) }
            }
        }
    }
}

@Composable
private fun title(prompt: ExtensionPrompt): String = stringResource(
    when (prompt.kind) {
        ExtensionPrompt.Kind.Install -> R.string.extension_prompt_install
        ExtensionPrompt.Kind.Update -> R.string.extension_prompt_update
        ExtensionPrompt.Kind.MorePermissions -> R.string.extension_prompt_more
        ExtensionPrompt.Kind.Added -> R.string.extension_prompt_added
        ExtensionPrompt.Kind.Failed -> R.string.extension_prompt_failed
    },
    prompt.name,
)

/** The no and yes buttons' labels. A notice has only an OK. */
private fun answers(kind: ExtensionPrompt.Kind): Pair<Int?, Int> = when (kind) {
    ExtensionPrompt.Kind.Install -> R.string.extension_prompt_cancel to R.string.extension_prompt_add
    ExtensionPrompt.Kind.Update -> R.string.extension_prompt_not_now to R.string.extension_prompt_update_button
    ExtensionPrompt.Kind.MorePermissions -> R.string.extension_prompt_deny to R.string.extension_prompt_allow
    ExtensionPrompt.Kind.Added, ExtensionPrompt.Kind.Failed -> null to R.string.extension_prompt_ok
}

@Composable
private fun PromptBody(prompt: ExtensionPrompt) {
    when (prompt.kind) {
        ExtensionPrompt.Kind.Added -> Text(stringResource(R.string.extension_prompt_added_where), style = EnsoType.Secondary)
        ExtensionPrompt.Kind.Failed -> Text(stringResource(prompt.failure ?: R.string.extension_failed_unknown), style = EnsoType.Secondary)
        else -> {
            val intro = when (prompt.kind) {
                ExtensionPrompt.Kind.Update -> R.string.extension_prompt_update_can
                ExtensionPrompt.Kind.MorePermissions -> R.string.extension_prompt_more_can
                else -> R.string.extension_prompt_install_can
            }
            if (prompt.permissions.isEmpty()) {
                Text(stringResource(R.string.extension_prompt_no_permissions), style = EnsoType.Secondary)
            } else {
                Text(stringResource(intro), style = EnsoType.Secondary)
                Column(
                    Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    prompt.permissions.forEach { PermissionLine(it) }
                }
            }
            prompt.dataCollection?.let {
                Text(stringResource(R.string.extension_prompt_collects, it), style = EnsoType.Secondary)
            }
        }
    }
}

@Composable
private fun PermissionLine(text: String) {
    Row {
        Box(Modifier.padding(top = 8.dp).size(5.dp).background(EnsoColors.TextSecondary, CircleShape))
        Spacer(Modifier.width(12.dp))
        Text(text, style = EnsoType.Row.copy(fontSize = 15.sp))
    }
}

/** The extension's icon, or a letter until it loads (or when it has none). */
@Composable
private fun PromptIcon(prompt: ExtensionPrompt) {
    val sizePx = with(LocalDensity.current) { 40.dp.roundToPx() }
    val icon by produceState<ImageBitmap?>(null, prompt) {
        value = runCatching { prompt.loadIcon?.invoke(sizePx) }.getOrNull()?.asImageBitmap()
    }
    val image = icon
    if (image != null) {
        Image(image, contentDescription = null, modifier = Modifier.size(40.dp))
    } else {
        LetterFavicon(prompt.name, "", size = 40.dp)
    }
}

@Composable
private fun PromptButton(@StringRes label: Int, accent: Color, primary: Boolean, onClick: () -> Unit) {
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
