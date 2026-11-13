package uk.co.learnerassist

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import uk.co.learnerassist.core.Advice
import java.util.Locale

class AudioCoach(context: Context) {
    private var ready = false
    private var last: Advice? = null
    private var lastTime = 0L
    private val tts = TextToSpeech(context) { status -> ready = status == TextToSpeech.SUCCESS }
    fun speak(advice: Advice, settings: Settings, now: Long) {
        if (!settings.audio || !ready || advice !in setOf(Advice.UP, Advice.DOWN, Advice.PREPARE) ||
            now - lastTime < settings.coaching.intervalSeconds * 1000L || advice == last) return
        val result = tts.setLanguage(Locale.UK)
        if (result < 0 || tts.voice?.isNetworkConnectionRequired == true) return
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, settings.volume) }
        tts.speak(advice.text, TextToSpeech.QUEUE_FLUSH, params, "advisory")
        last = advice; lastTime = now
    }
    fun stop() { tts.stop(); last = null }
    fun close() { tts.stop(); tts.shutdown() }
}
