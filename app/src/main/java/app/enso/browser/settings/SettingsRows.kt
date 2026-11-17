package app.enso.browser.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.enso.browser.R
import app.enso.browser.ui.EnsoColors
import app.enso.browser.ui.EnsoType
import kotlin.math.roundToInt

// The rows the settings screens are made of. The user had them restyled after Arc Search's settings: small type,
// everything on one left margin, and a choice's value as a line under its title, with the options in a menu.

/** The left margin everything lines up on: group names, icons, and the titles of rows without an icon. */
internal val SettingsMargin = 16.dp

/** Settings' type, a step smaller than the tab rows'. */
internal object SettingsType {
    val Heading get() = EnsoType.Row.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    val Group get() = EnsoType.Secondary.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
    val Title get() = EnsoType.Row.copy(fontSize = 15.sp)
    val Detail get() = EnsoType.Secondary.copy(fontSize = 12.5.sp)
}

/** How much smaller than Material's own the settings' switches are, to sit with the smaller type. */
private const val SWITCH_SCALE = 0.8f

/** A group's name over its rows. */
@Composable
internal fun SettingsGroup(title: String) {
    Text(title, style = SettingsType.Group, modifier = Modifier.padding(start = SettingsMargin, end = SettingsMargin, top = 20.dp, bottom = 4.dp))
}

/** A row: maybe an icon, a title and maybe a line under it, and what sits at its end. */
@Composable
internal fun SettingRow(
    title: String,
    subtitle: String? = null,
    @DrawableRes icon: Int? = null,
    color: Color = EnsoColors.TextPrimary,
    subtitleColor: Color = EnsoColors.TextSecondary,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) = RowLayout(
    title, subtitle, icon, color, subtitleColor,
    if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    trailing,
)

@Composable
private fun RowLayout(
    title: String,
    subtitle: String?,
    @DrawableRes icon: Int?,
    color: Color,
    subtitleColor: Color,
    modifier: Modifier,
    trailing: (@Composable () -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(modifier)
            .padding(horizontal = SettingsMargin, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(painterResource(it), contentDescription = null, Modifier.size(20.dp), tint = color)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = SettingsType.Title.copy(color = color))
            subtitle?.let { Text(it, style = SettingsType.Detail.copy(color = subtitleColor)) }
        }
        trailing?.let {
            Spacer(Modifier.width(12.dp))
            it()
        }
    }
}

/** A setting that's on or off. The whole row toggles it; the switch only shows it. */
@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
    @DrawableRes icon: Int? = null,
) = RowLayout(
    title, subtitle, icon, EnsoColors.TextPrimary, EnsoColors.TextSecondary,
    Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
) { EnsoSwitch(checked, onChange = null, Modifier.scaledLayout(SWITCH_SCALE)) }

/** Ensō's switch, the same in the Extensions list and the settings. Without [onChange] it only shows the value. */
@Composable
internal fun EnsoSwitch(checked: Boolean, onChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedTrackColor = EnsoColors.TextPrimary,
            checkedThumbColor = EnsoColors.Card,
            uncheckedTrackColor = EnsoColors.FieldFill,
            uncheckedThumbColor = EnsoColors.TextSecondary,
            uncheckedBorderColor = EnsoColors.TextSecondary.copy(alpha = 0.4f),
        ),
    )
}

/** Draws this at [scale], and lays it out at that size too, so it keeps to the row's margin. */
internal fun Modifier.scaledLayout(scale: Float) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val width = (placeable.width * scale).roundToInt()
    val height = (placeable.height * scale).roundToInt()
    layout(width, height) {
        placeable.placeWithLayer((width - placeable.width) / 2, (height - placeable.height) / 2) {
            scaleX = scale
            scaleY = scale
        }
    }
}

/** A choice between a few options: the chosen one's name under the title, and a tap lists them all. */
@Composable
internal fun <T> ChoiceRow(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, @DrawableRes icon: Int? = null) =
    MenuRow(title, options.firstOrNull { it.first == selected }?.second.orEmpty(), options, selected, onSelect, icon)

/**
 * A row whose [value] shows under its title (in [valueColor]). A tap opens a menu of the [options] just below it, on the
 * same margin, with the chosen one checked.
 */
@Composable
internal fun <T> MenuRow(
    title: String,
    value: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    @DrawableRes icon: Int? = null,
    valueColor: Color = EnsoColors.TextSecondary,
) {
    var choosing by remember { mutableStateOf(false) }
    Box {
        SettingRow(title, value, icon, subtitleColor = valueColor, onClick = { choosing = true })
        DropdownMenu(
            expanded = choosing,
            onDismissRequest = { choosing = false },
            offset = DpOffset(SettingsMargin, 0.dp),
            shape = RoundedCornerShape(14.dp),
            containerColor = EnsoColors.Card,
        ) {
            options.forEach { (option, label) ->
                DropdownMenuItem(
                    text = { Text(label, style = SettingsType.Title) },
                    trailingIcon = if (option == selected) {
                        { Icon(painterResource(R.drawable.ic_check), contentDescription = null, Modifier.size(18.dp), tint = EnsoColors.TextPrimary) }
                    } else {
                        null
                    },
                    onClick = {
                        choosing = false
                        if (option != selected) onSelect(option)
                    },
                )
            }
        }
    }
}

/** A number stepped up and down between [range], by [step]: page zoom, the space menu's hold time. */
@Composable
internal fun StepperRow(title: String, subtitle: String?, value: Int, range: IntRange, step: Int, label: (Int) -> String, onChange: (Int) -> Unit) =
    SettingRow(title, subtitle) {
        Row(
            Modifier.background(EnsoColors.FieldFill, RoundedCornerShape(10.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepButton(R.drawable.ic_minus, enabled = value - step >= range.first) { onChange(value - step) }
            Text(
                label(value),
                style = SettingsType.Title.copy(fontSize = 13.sp),
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.widthIn(min = 44.dp),
            )
            StepButton(R.drawable.ic_plus, enabled = value + step <= range.last) { onChange(value + step) }
        }
    }

@Composable
private fun StepButton(@DrawableRes icon: Int, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            Modifier.size(16.dp),
            tint = EnsoColors.TextPrimary.copy(alpha = if (enabled) 1f else 0.3f),
        )
    }
}
