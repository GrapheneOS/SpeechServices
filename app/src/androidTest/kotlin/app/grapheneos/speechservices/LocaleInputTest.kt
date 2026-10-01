package app.grapheneos.speechservices

import android.speech.tts.TextToSpeech
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.grapheneos.speechservices.tts.checkLanguageAvailability
import app.grapheneos.speechservices.tts.deprecatedLocaleToModern
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocaleInputTest {
    @Test
    fun validIsoCodesKeepTheirMeaning() {
        assertEquals(Locale.US, deprecatedLocaleToModern("eng", "USA", ""))
        assertEquals(Locale.US, deprecatedLocaleToModern("en", "US", null))
        assertEquals(Locale.UK, deprecatedLocaleToModern("eng", "GBR", null))
        assertEquals(
            "POSIX",
            deprecatedLocaleToModern("eng", "USA", "POSIX").variant,
        )
        assertEquals(Locale.ROOT, deprecatedLocaleToModern("", null, null))
    }

    @Test
    fun malformedLanguagesAreUnsupported() {
        for (language in listOf("x", "en_US", "en-US", "123", " ", "a".repeat(100))) {
            val result = checkLanguageAvailability(language, "USA", null)
            assertEquals(language, TextToSpeech.LANG_NOT_SUPPORTED, result.status)
            assertNull(language, result.voice)
        }
    }

    @Test
    fun malformedCountryKeepsTheSupportedLanguage() {
        for (country in listOf("x", "US!", "United States", "a".repeat(100))) {
            assertEquals(Locale.ENGLISH, deprecatedLocaleToModern("eng", country, null))
            val result = checkLanguageAvailability("eng", country, null)
            assertEquals(country, TextToSpeech.LANG_AVAILABLE, result.status)
            assertEquals(country, "en_US", result.voice?.name)
        }
    }

    @Test
    fun malformedVariantKeepsTheSupportedCountry() {
        for (variant in listOf("x", "!", "bad-variant", "a".repeat(100))) {
            assertEquals(Locale.US, deprecatedLocaleToModern("eng", "USA", variant))
            val result = checkLanguageAvailability("eng", "USA", variant)
            assertEquals(variant, TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE, result.status)
            assertEquals(variant, "en_US", result.voice?.name)
        }
    }
}
