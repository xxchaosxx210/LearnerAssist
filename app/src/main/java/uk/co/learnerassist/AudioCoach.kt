package uk.co.learnerassist

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import uk.co.learnerassist.core.*
import java.util.Locale

class AudioCoach(context: Context) {
    private var ready = false
    private val policy = VoicePromptPolicy()
    private val tts = TextToSpeech(context) { status -> ready = status == TextToSpeech.SUCCESS }
    fun speak(decision: CoachingDecision, settings: Settings) {
        if (!settings.audio || settings.coaching.mode == DrivingMode.INDEPENDENT || decision.advice in setOf(Advice.UNKNOWN, Advice.WAIT, Advice.OBSERVE, Advice.MAINTAIN)) tts.stop()
        if (!settings.audio || !ready || settings.coaching.mode == DrivingMode.INDEPENDENT) return
        val result = tts.setLanguage(Locale.UK)
        if (result < 0) return
        val voice = tts.voices?.filter { !it.isNetworkConnectionRequired && it.locale.language == "en" }
            ?.sortedBy { if (it.locale.country == "GB") 0 else 1 }?.firstOrNull() ?: return
        tts.voice = voice
        val text = policy.next(decision, settings.audio, settings.coaching) ?: return
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, settings.volume) }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "advisory")
    }
    fun stop() { tts.stop(); policy.reset() }
    fun close() { tts.stop(); tts.shutdown() }
}
