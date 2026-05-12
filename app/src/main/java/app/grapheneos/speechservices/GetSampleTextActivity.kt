package app.grapheneos.speechservices

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import app.grapheneos.speechservices.tts.checkLanguageAvailability

class GetSampleTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = this.intent
        // as of Android 16 QPR2, these identifiers are hardcoded:
        // https://github.com/GrapheneOS/platform_packages_apps_Settings/blob/826089f4e79b4c4a1079a0d60454c508a015fa9f/src/com/android/settings/tts/TextToSpeechSettings.java#L491
        val language = intent.getStringExtra("language") ?: ""
        val country = intent.getStringExtra("country")
        val variant = intent.getStringExtra("variant")

        val (isAvailableLanguage, defaultVoice) = checkLanguageAvailability(
            language,
            country,
            variant,
        )
        val isAvailable = when (isAvailableLanguage) {
            TextToSpeech.LANG_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE,
            -> true
            TextToSpeech.LANG_NOT_SUPPORTED,
            TextToSpeech.LANG_MISSING_DATA,
            -> false
            else -> false
        }

        val result = Intent()
        if (isAvailable) {
            result.putExtra(
                TextToSpeech.Engine.EXTRA_SAMPLE_TEXT,
                "This is an example of speech synthesis in ${defaultVoice!!.locale.displayName}.",
            )
        }
        val resultCode = // No granularity; only accepts LANG_AVAILABLE for availability.
            if (isAvailable) TextToSpeech.LANG_AVAILABLE else RESULT_OK

        setResult(resultCode, result)
        finish()
    }
}
