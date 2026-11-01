package app.enso.browser.bottombar

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.enso.browser.settings.AllSettingsTitle
import app.enso.browser.settings.SettingsHeader
import app.enso.browser.settings.SiteSettingsActions
import app.enso.browser.settings.SiteSettingsContent
import app.enso.browser.settings.SiteState
import app.enso.browser.settings.SiteTitle

/**
 * What the bar's settings panels show (step 10): the site on screen, if there is one, and All settings, which the
 * activity draws since it applies what changes.
 */
class BarSettings(
    val site: SiteState?,
    val siteActions: SiteSettingsActions,
    val all: @Composable (Modifier) -> Unit,
)

/** The site's settings, where Settings in the actions sheet opens. Back returns to the actions sheet. */
@Composable
internal fun SiteSettingsPanel(site: SiteState, actions: SiteSettingsActions, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SettingsHeader(onBack) { SiteTitle(site) }
        SiteSettingsContent(site, actions)
        Spacer(Modifier.height(16.dp))
        BarDivider()
    }
}

/** All settings, grown to just below the status bar, as the Quick Tabs view is. */
@Composable
internal fun AllSettingsPanel(height: Dp, onBack: () -> Unit, content: @Composable (Modifier) -> Unit) {
    Column(Modifier.fillMaxWidth().height(height)) {
        SettingsHeader(onBack) { AllSettingsTitle() }
        content(Modifier.weight(1f).verticalScroll(rememberScrollState()))
        BarDivider()
    }
}
