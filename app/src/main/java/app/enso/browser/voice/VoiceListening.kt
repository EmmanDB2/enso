package app.enso.browser.voice

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.enso.browser.R
import app.enso.browser.start.ENSO_GAP_CLOSED
import app.enso.browser.start.EnsoInk
import app.enso.browser.start.FidgetHaptics
import app.enso.browser.ui.EnsoType
import kotlinx.coroutines.delay
import kotlin.math.exp

/**
 * Voice search's listening view, where the keyboard would be under bottom search's field: the ensō, breathing faintly
 * while it waits. As you speak, its head extends round, and a glow in the space's colors ([glow]) rises and falls with
 * your voice. When you pause, the stroke closes the gap with the fidget's click, and [onHeard] sends what was said. If
 * nothing was understood the ink unwinds, and a tap on the ensō listens again ([onRetry]).
 */
@Composable
internal fun VoiceListening(voice: VoiceInput, glow: List<Color>, onHeard: (String) -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val state = voice.state
    val sweep = remember { Animatable(0f) }
    val context = LocalContext.current
    val view = LocalView.current
    val haptics = remember { FidgetHaptics(context, view) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { keyboard?.hide() }

    val breath by rememberInfiniteTransition(label = "breath").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(BREATH_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    val waiting = state == VoiceState.Waiting || state == VoiceState.Starting
    val ink by animateFloatAsState(
        when (state) {
            VoiceState.Hearing -> 0.8f
            is VoiceState.Heard -> 0.9f
            VoiceState.Missed -> 0.3f
            else -> 0.35f
        },
        tween(INK_FADE_MS),
        label = "ink",
    )
    val glowStrength by animateFloatAsState(
        when (state) {
            VoiceState.Hearing -> 0.2f + 0.8f * voice.level
            is VoiceState.Heard -> 1f
            else -> 0f
        },
        spring(stiffness = Spring.StiffnessLow),
        label = "glow",
    )

    LaunchedEffect(state) {
        when (state) {
            VoiceState.Hearing -> {
                // The head creeps round as you speak, slowing as it nears the gap, which only closes once you pause.
                val from = sweep.value
                val began = withFrameNanos { it }
                while (true) {
                    val seconds = (withFrameNanos { it } - began) / 1e9f
                    sweep.snapTo(from + (ENSO_GAP_CLOSED * HEARING_REACH - from) * (1f - exp(-seconds / HEARING_SECONDS)))
                }
            }
            is VoiceState.Heard -> {
                sweep.animateTo(ENSO_GAP_CLOSED, tween(CLOSE_MS, easing = FastOutSlowInEasing))
                haptics.click(heavy = false)
                delay(SEND_AFTER_CLOSE_MS)
                onHeard(state.text)
            }
            VoiceState.Missed -> sweep.animateTo(0f, tween(UNWIND_MS))
            else -> Unit
        }
    }

    Column(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        EnsoInk(
            sweep = sweep.value,
            inkAlpha = ink + if (waiting) BREATH_DEPTH * breath else 0f,
            glow = glow,
            glowStrength = glowStrength,
            modifier = Modifier
                .size(176.dp)
                .clickable(
                    enabled = state == VoiceState.Missed,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onRetry,
                ),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            when (state) {
                VoiceState.Missed -> stringResource(R.string.voice_missed)
                VoiceState.Waiting, VoiceState.Starting -> stringResource(R.string.voice_listening)
                else -> ""
            },
            style = EnsoType.Secondary.copy(fontSize = 13.sp),
            textAlign = TextAlign.Center,
            modifier = Modifier.heightIn(min = 18.dp).padding(horizontal = 24.dp),
        )
    }
}

/** The ink's breath while it waits, on top of its resting 35%. */
private const val BREATH_DEPTH = 0.15f
private const val BREATH_MS = 2400

private const val INK_FADE_MS = 260

/** How far toward the gap the head creeps while you speak (it closes only when you pause), and how slowly. */
private const val HEARING_REACH = 0.8f
private const val HEARING_SECONDS = 2.5f

private const val CLOSE_MS = 380
private const val SEND_AFTER_CLOSE_MS = 220L
private const val UNWIND_MS = 360
