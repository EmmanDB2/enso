package app.enso.browser.reader

import android.content.Context
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.enso.browser.R
import app.enso.browser.ui.EnsoColors
import app.enso.browser.ui.EnsoType
import mozilla.components.feature.readerview.ReaderViewFeature.ColorScheme
import mozilla.components.feature.readerview.ReaderViewFeature.FontType
import mozilla.components.feature.readerview.view.ReaderViewControlsView

/**
 * Reader's appearance controls as Android Components' reader view sees them (polish pass). AC keeps the settings and
 * sets them here when the controls show ([ReaderViewFeature.showControls]); Ensō draws them in Compose
 * ([ReaderControlsContent]) and passes the user's changes to AC through [listener]. It's a View only because AC may
 * ask for one ([asView]); it's never added to the screen.
 */
class ReaderControls(context: Context) : View(context), ReaderViewControlsView {
    override var listener: ReaderViewControlsView.Listener? = null

    // Named apart from the interface's setters, which the JVM would otherwise see twice.
    var chosenFont by mutableStateOf(FontType.SERIF)
        private set
    var chosenFontSize by mutableIntStateOf(3)
        private set
    var chosenColors by mutableStateOf(ColorScheme.LIGHT)
        private set

    override fun setFont(font: FontType) {
        chosenFont = font
    }

    override fun setFontSize(size: Int) {
        chosenFontSize = size
    }

    override fun setColorScheme(scheme: ColorScheme) {
        chosenColors = scheme
    }

    // Ensō shows the controls itself, in the bar's Reader panel or the unfolded card.
    override fun showControls() = Unit

    override fun hideControls() = Unit

    override fun tryInflate() = false

    fun chooseFont(font: FontType) {
        listener?.onFontChanged(font)
        chosenFont = font
    }

    fun smaller() {
        listener?.onFontSizeDecreased()?.let { chosenFontSize = it }
    }

    fun larger() {
        listener?.onFontSizeIncreased()?.let { chosenFontSize = it }
    }

    fun chooseColors(scheme: ColorScheme) {
        listener?.onColorSchemeChanged(scheme)
        chosenColors = scheme
    }
}

/** "Reader", heading the unfolded Reader card. */
@Composable
fun ReaderTitle() {
    Text(stringResource(R.string.reader_title), style = EnsoType.RowTitle.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold), maxLines = 1)
}

/**
 * The Reader panel's contents: Sans or Serif, smaller or larger text, Light, Sepia or Dark, and Exit Reader.
 * [onColorsChosen] tells the app the user picked colors themselves, so Reader stops following Ensō's theme.
 */
@Composable
fun ReaderControlsContent(controls: ReaderControls, onExit: () -> Unit, onColorsChosen: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(stringResource(R.string.reader_sans), controls.chosenFont == FontType.SANSSERIF, FontFamily.SansSerif, Modifier.weight(1f)) {
                controls.chooseFont(FontType.SANSSERIF)
            }
            Choice(stringResource(R.string.reader_serif), controls.chosenFont == FontType.SERIF, FontFamily.Serif, Modifier.weight(1f)) {
                controls.chooseFont(FontType.SERIF)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("A −", chosen = false, FontFamily.Default, Modifier.weight(1f), description = stringResource(R.string.reader_smaller), textSize = 14) {
                controls.smaller()
            }
            Choice("A +", chosen = false, FontFamily.Default, Modifier.weight(1f), description = stringResource(R.string.reader_larger), textSize = 19) {
                controls.larger()
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally)) {
            Swatch(Color(0xFFFFFFFF), controls.chosenColors == ColorScheme.LIGHT, stringResource(R.string.reader_light)) {
                controls.chooseColors(ColorScheme.LIGHT)
                onColorsChosen()
            }
            Swatch(Color(0xFFF4ECD8), controls.chosenColors == ColorScheme.SEPIA, stringResource(R.string.reader_sepia)) {
                controls.chooseColors(ColorScheme.SEPIA)
                onColorsChosen()
            }
            Swatch(Color(0xFF2A2A2E), controls.chosenColors == ColorScheme.DARK, stringResource(R.string.reader_dark)) {
                controls.chooseColors(ColorScheme.DARK)
                onColorsChosen()
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(23.dp))
                .background(EnsoColors.PillFill)
                .clickable(onClick = onExit),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.reader_exit), style = EnsoType.Row)
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun Choice(
    label: String,
    chosen: Boolean,
    family: FontFamily,
    modifier: Modifier,
    description: String? = null,
    textSize: Int = 16,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .height(44.dp)
            .clip(shape)
            .background(if (chosen) EnsoColors.Chosen else EnsoColors.PillFill, shape)
            .then(if (chosen) Modifier.border(1.5.dp, EnsoColors.TextPrimary.copy(alpha = 0.5f), shape) else Modifier)
            .clickable(onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = EnsoType.Row.copy(fontFamily = family, fontSize = textSize.sp, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal))
    }
}

/** A reader color as a round swatch (the page's own colors, not Ensō's theme), ringed when chosen. */
@Composable
private fun Swatch(color: Color, chosen: Boolean, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(color)
            .border(if (chosen) 2.5.dp else 1.dp, if (chosen) EnsoColors.TextPrimary else EnsoColors.TextSecondary.copy(alpha = 0.4f), CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
    )
}
