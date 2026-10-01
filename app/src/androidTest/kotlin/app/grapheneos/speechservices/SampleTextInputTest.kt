package app.grapheneos.speechservices

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SampleTextInputTest {
    @Test
    fun malformedLanguageReturnsWithoutCrashing() {
        val result = sampleText("x", "USA", "")
        assertEquals(Activity.RESULT_OK, result.resultCode)
        assertFalse(result.resultData.hasExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT))
    }

    @Test
    fun malformedCountryOrVariantCanUseTheEnglishSample() {
        for ((country, variant) in listOf("x" to "", "USA" to "x")) {
            val result = sampleText("eng", country, variant)
            assertEquals(TextToSpeech.LANG_AVAILABLE, result.resultCode)
            assertTrue(
                result.resultData.getStringExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT)!!
                    .isNotBlank(),
            )
        }
    }

    private fun sampleText(
        language: String,
        country: String,
        variant: String,
    ): Instrumentation.ActivityResult {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, GetSampleTextActivity::class.java)
            .putExtra("language", language)
            .putExtra("country", country)
            .putExtra("variant", variant)
        ActivityScenario.launchActivityForResult<GetSampleTextActivity>(intent).use { scenario ->
            return scenario.result
        }
    }
}
