package app.enso.browser.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Where voice search is. */
sealed interface VoiceState {
    /** Not listening: bottom search types as usual. */
    data object Off : VoiceState

    /** Asked to listen; the microphone's permission or the recognizer is on its way. */
    data object Starting : VoiceState

    /** Listening, and nothing heard yet. */
    data object Waiting : VoiceState

    /** Hearing speech. */
    data object Hearing : VoiceState

    /** Finished, with what was said. */
    data class Heard(val text: String) : VoiceState

    /** Nothing understood, or the recognizer failed. */
    data object Missed : VoiceState
}

/**
 * Voice search inside Ensō, through Android's own speech recognizer, rather than the recognizer's popup: Ensō records
 * (it asks for the microphone once) and shows its own listening view. [state], [heard] (the words so far) and [level]
 * (the voice's loudness, 0 to 1) are snapshot state for that view. Main thread only.
 */
class VoiceInput(private val context: Context) {
    var state by mutableStateOf<VoiceState>(VoiceState.Off)
        private set

    var heard by mutableStateOf("")
        private set

    var level by mutableFloatStateOf(0f)
        private set

    private var recognizer: SpeechRecognizer? = null

    /** Whether a recognizer is installed that Ensō can use directly. Without one, the old popup is the way. */
    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    val active: Boolean get() = state != VoiceState.Off

    /** Shows the listening view while the microphone's permission is asked for. */
    fun prepare() {
        release()
        heard = ""
        level = 0f
        state = VoiceState.Starting
    }

    fun start() {
        prepare()
        val listening = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = listening
        listening.setRecognitionListener(object : RecognitionListener {
            private val current get() = recognizer === listening

            override fun onReadyForSpeech(params: Bundle?) {
                if (current) state = VoiceState.Waiting
            }

            override fun onBeginningOfSpeech() {
                if (current) state = VoiceState.Hearing
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (!current) return
                // The recognizer reports about −2 dB in silence to 10 for loud speech; eased so the glow doesn't flicker.
                val target = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                level += (target - level) * 0.35f
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!current) return
                partialResults?.words()?.let { heard = it }
            }

            override fun onResults(results: Bundle?) {
                if (!current) return
                val text = results?.words() ?: heard.trim()
                heard = text
                state = if (text.isBlank()) VoiceState.Missed else VoiceState.Heard(text)
                release()
            }

            override fun onError(error: Int) {
                if (!current) return
                state = VoiceState.Missed
                release()
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() = Unit

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        listening.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName),
        )
    }

    /** Stops listening and goes back to typing. */
    fun cancel() {
        recognizer?.cancel()
        release()
        state = VoiceState.Off
        level = 0f
    }

    private fun release() {
        val done = recognizer ?: return
        recognizer = null
        done.destroy()
    }

    private fun Bundle.words(): String? =
        getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}
