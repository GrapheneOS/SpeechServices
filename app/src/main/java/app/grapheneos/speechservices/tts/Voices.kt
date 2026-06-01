package app.grapheneos.speechservices.tts

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import app.grapheneos.speechservices.verboseLog
import java.util.Locale

private const val TAG = "Voices"

enum class DefaultVoice(val voice: Voice) {
    EnUs(
        Voice(
            "en_US",
            Locale.US,
            Voice.QUALITY_VERY_HIGH,
            Voice.LATENCY_NORMAL,
            false,
            emptySet<String>(),
        ),
    ),
}

val supportedVoices = DefaultVoice.entries.map { it.voice }

val availableVoices = supportedVoices

fun getAvailableVoiceByName(voiceName: String?): Voice? {
    return voiceName?.let {
        availableVoices.find { availableVoice -> availableVoice.name == voiceName }
    }
}

fun isVoiceAvailable(voice: Voice?): Boolean {
    return availableVoices.find { availableVoice -> availableVoice == voice } != null
}

fun isVoiceAvailable(voiceName: String?): Boolean {
    return getAvailableVoiceByName(voiceName) != null
}

data class LanguageAvailability(val status: Int, val voice: Voice?)

fun checkLanguageAvailability(
    lang: String,
    country: String?,
    variant: String?,
): LanguageAvailability {
    verboseLog(TAG) {
        "isLanguageAvailableWithDefaultVoiceName parameters: lang: $lang, country: $country, variant: $variant"
    }

    val modernizedLocale = deprecatedLocaleToModern(lang, country, variant)
    val lang = modernizedLocale.language
    val country = modernizedLocale.country
    val variant = modernizedLocale.variant

    verboseLog(TAG) {
        "isLanguageAvailableWithDefaultVoiceName converted parameters: lang: $lang, country: $country, variant: $variant"
    }

    val defaultVoiceEntries = DefaultVoice.entries.map { it.voice }
    val best =
        defaultVoiceEntries.fold(
            LanguageAvailability(
                TextToSpeech.LANG_NOT_SUPPORTED,
                null,
            ),
        ) { best, candidateVoice ->
            val bestVoice = best.voice
            val bestLocale = bestVoice?.locale
            val candidateLocale = candidateVoice.locale

            val isBestVoiceAvailable =
                bestVoice != null && bestLocale != null && isVoiceAvailable(bestVoice)
            val isCandidateVoiceAvailable =
                candidateLocale != null && isVoiceAvailable(candidateVoice)

            if (isBestVoiceAvailable &&
                bestLocale.language == lang &&
                bestLocale.country == country &&
                bestLocale.variant == variant
            ) {
                LanguageAvailability(TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE, bestVoice)
            } else if (isCandidateVoiceAvailable &&
                candidateLocale.language == lang &&
                candidateLocale.country == country &&
                candidateLocale.variant == variant
            ) {
                LanguageAvailability(TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE, candidateVoice)
            } else if (isBestVoiceAvailable &&
                bestLocale.language == lang &&
                bestLocale.country == country
            ) {
                LanguageAvailability(TextToSpeech.LANG_COUNTRY_AVAILABLE, best.voice)
            } else if (isCandidateVoiceAvailable &&
                candidateLocale.language == lang &&
                candidateLocale.country == country
            ) {
                LanguageAvailability(TextToSpeech.LANG_COUNTRY_AVAILABLE, candidateVoice)
            } else if (bestLocale != null && bestLocale.language == lang) {
                if (isBestVoiceAvailable) {
                    LanguageAvailability(TextToSpeech.LANG_AVAILABLE, best.voice)
                } else {
                    LanguageAvailability(TextToSpeech.LANG_MISSING_DATA, null)
                }
            } else if (candidateLocale != null && candidateLocale.language == lang) {
                if (isCandidateVoiceAvailable) {
                    LanguageAvailability(TextToSpeech.LANG_AVAILABLE, candidateVoice)
                } else {
                    LanguageAvailability(TextToSpeech.LANG_MISSING_DATA, null)
                }
            } else {
                LanguageAvailability(TextToSpeech.LANG_NOT_SUPPORTED, null)
            }
        }

    return best
}
