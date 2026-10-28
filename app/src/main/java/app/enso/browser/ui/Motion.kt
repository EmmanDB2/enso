package app.enso.browser.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Ensō's shared motion (step 10): how cards come and go, and how fast things fade. */
object EnsoMotion {
    const val CARD_IN_MS = 180
    const val CARD_OUT_MS = 140

    /** The start screen and pages cross-fading. */
    const val PAGE_FADE_MS = 200

    /** A favicon replacing its letter once it loads. */
    const val FAVICON_FADE_MS = 160

    /** A card's dim layer fading in and out. */
    val CardEnter: EnterTransition = fadeIn(tween(CARD_IN_MS))
    val CardExit: ExitTransition = fadeOut(tween(CARD_OUT_MS))

    /** The card itself, growing slightly into place over its dim layer. */
    val CardScaleIn: EnterTransition = scaleIn(tween(CARD_IN_MS), initialScale = CARD_SCALE)
    val CardScaleOut: ExitTransition = scaleOut(tween(CARD_OUT_MS), targetScale = CARD_SCALE)

    private const val CARD_SCALE = 0.95f
}

/**
 * Shows [content] for [value] while it isn't null, fading in and out with [EnsoMotion.CardEnter]. It keeps the last
 * value through the fade out, after [value] has gone. Inside, `Modifier.animateEnterExit(EnsoMotion.CardScaleIn,
 * EnsoMotion.CardScaleOut)` scales the card itself.
 */
@Composable
fun <T : Any> AnimatedCard(value: T?, content: @Composable AnimatedVisibilityScope.(T) -> Unit) {
    // Not state: it only needs to outlast [value] while the card fades out.
    val last = remember { Shown<T>() }
    if (value != null) last.value = value
    AnimatedVisibility(visible = value != null, enter = EnsoMotion.CardEnter, exit = EnsoMotion.CardExit) {
        last.value?.let { content(it) }
    }
}

private class Shown<T> {
    var value: T? = null
}
