package app.enso.browser.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.enso.browser.R
import app.enso.browser.ui.EnsoColors
import app.enso.browser.ui.EnsoType
import kotlin.math.roundToInt

// The rows the settings screens are made of (step 10): no board covers them, so they follow the sketch the user
// approved. Rows line up on a 24 dp icon column, filled or not.

/** A group's name over its rows. */
@Composable
internal fun SettingsGroup(title: String) {
    Text(
        title,
        style = EnsoType.Secondary.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 2.dp),
    )
}

/** A row: an icon (or room for one), a title and maybe a subtitle, and what sits at its end. */
@Composable
internal fun SettingRow(
    title: String,
    subtitle: String? = null,
    @DrawableRes icon: Int? = null,
    color: Color = EnsoColors.TextPrimary,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            icon?.let { Icon(painterResource(it), contentDescription = null, Modifier.size(22.dp), tint = color) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = EnsoType.Row.copy(color = color))
            subtitle?.let { Text(it, style = EnsoType.Secondary.copy(fontSize = 13.sp)) }
        }
        trailing?.let {
            Spacer(Modifier.width(12.dp))
            it()
        }
    }
}

@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
    @DrawableRes icon: Int? = null,
) = SettingRow(title, subtitle, icon, onClick = { onChange(!checked) }) { EnsoSwitch(checked, onChange) }

/** Ensō's switch, the same in the Extensions list and the settings. */
@Composable
internal fun EnsoSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedTrackColor = EnsoColors.TextPrimary,
            checkedThumbColor = EnsoColors.Card,
            uncheckedTrackColor = EnsoColors.FieldFill,
            uncheckedThumbColor = EnsoColors.TextSecondary,
            uncheckedBorderColor = EnsoColors.TextSecondary.copy(alpha = 0.4f),
        ),
    )
}

/** A row that opens something: Sync with Zen, say. */
@Composable
internal fun LinkRow(title: String, subtitle: String?, @DrawableRes icon: Int?, onClick: () -> Unit) =
    SettingRow(title, subtitle, icon, onClick = onClick) {
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, Modifier.size(20.dp), tint = EnsoColors.TextSecondary)
    }

/** A choice between a few options: the title, then the options side by side, the chosen one raised. */
@Composable
internal fun <T> ChoiceRow(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(start = 54.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
        Text(title, style = EnsoType.Row)
        subtitle?.let { Text(it, style = EnsoType.Secondary.copy(fontSize = 13.sp)) }
        Spacer(Modifier.size(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .background(EnsoColors.FieldFill, RoundedCornerShape(12.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            options.forEach { (value, label) ->
                val chosen = value == selected
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (chosen) EnsoColors.Chosen else Color.Transparent)
                        .clickable { onSelect(value) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = EnsoType.MenuItem.copy(fontSize = 14.sp, color = if (chosen) EnsoColors.TextPrimary else EnsoColors.TextSecondary),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** A number stepped up and down between [range], by [step]: page zoom. */
@Composable
internal fun StepperRow(title: String, subtitle: String?, value: Int, range: IntRange, step: Int, label: (Int) -> String, onChange: (Int) -> Unit) =
    SettingRow(title, subtitle) {
        Row(
            Modifier.background(EnsoColors.FieldFill, RoundedCornerShape(12.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepButton(R.drawable.ic_minus, enabled = value - step >= range.first) { onChange(value - step) }
            Text(label(value), style = EnsoType.MenuItem.copy(fontSize = 15.sp), textAlign = TextAlign.Center, modifier = Modifier.width(52.dp))
            StepButton(R.drawable.ic_plus, enabled = value + step <= range.last) { onChange(value + step) }
        }
    }

@Composable
private fun StepButton(@DrawableRes icon: Int, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            Modifier.size(18.dp),
            tint = EnsoColors.TextPrimary.copy(alpha = if (enabled) 1f else 0.3f),
        )
    }
}

/** A value along a range, in steps: the space menu's hold time. */
@Composable
internal fun SliderRow(title: String, value: Int, range: IntRange, step: Int, label: (Int) -> String, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 54.dp, end = 16.dp, top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = EnsoType.Row, modifier = Modifier.weight(1f))
            Text(label(value), style = EnsoType.Secondary)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange((it / step).roundToInt() * step) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) / step - 1,
            colors = SliderDefaults.colors(
                thumbColor = EnsoColors.TextPrimary,
                activeTrackColor = EnsoColors.TextPrimary,
                inactiveTrackColor = EnsoColors.FieldFill,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
    }
}
