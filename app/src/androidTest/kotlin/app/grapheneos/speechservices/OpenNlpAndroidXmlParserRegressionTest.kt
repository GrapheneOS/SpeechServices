package app.grapheneos.speechservices

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import opennlp.tools.postag.POSModel
import opennlp.tools.postag.POSTaggerME
import opennlp.tools.tokenize.TokenizerME
import opennlp.tools.tokenize.TokenizerModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class OpenNlpAndroidXmlParserRegressionTest {
    @Test
    fun bundledOpenNlpModelsLoadAndRunOnAndroidXmlParser() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resources = context.resources

        val tokenizer = resources.openRawResource(
            R.raw.opennlp_en_ud_ewt_tokens__1_3__2_5_4,
        ).buffered().use {
            TokenizerME(TokenizerModel(it))
        }
        val tokens = tokenizer.tokenize("GrapheneOS tests OpenNLP on Android.")
        assertTrue("tokenizer produced no tokens", tokens.isNotEmpty())

        val posTagger = resources.openRawResource(
            R.raw.opennlp_en_ud_ewt_pos__1_3__2_5_4,
        ).buffered().use {
            POSTaggerME(POSModel(it))
        }
        val tags = posTagger.tag(tokens)
        assertEquals("POS tag count differs from token count", tokens.size, tags.size)
        assertTrue("POS tagger produced a blank tag", tags.all { it.isNotBlank() })
    }

    @Test
    fun textToSpeechEngineSynthesizesWithBundledOpenNlpModels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val initLatch = CountDownLatch(1)
        val initStatus = AtomicInteger(Int.MIN_VALUE)
        var textToSpeech: TextToSpeech? = null

        try {
            textToSpeech = TextToSpeech(
                context,
                { status ->
                    initStatus.set(status)
                    initLatch.countDown()
                },
                context.packageName,
            )

            assertTrue(
                "TextToSpeech engine did not initialize",
                initLatch.await(30, TimeUnit.SECONDS),
            )
            assertEquals(
                "TextToSpeech engine initialization failed",
                TextToSpeech.SUCCESS,
                initStatus.get(),
            )

            val languageStatus = textToSpeech.setLanguage(Locale.US)
            assertTrue(
                "English language load failed with status $languageStatus",
                languageStatus >= TextToSpeech.LANG_AVAILABLE,
            )

            val utteranceLatch = CountDownLatch(1)
            val utteranceStatus = AtomicInteger(Int.MIN_VALUE)
            textToSpeech.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        utteranceStatus.set(TextToSpeech.SUCCESS)
                        utteranceLatch.countDown()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        utteranceStatus.set(TextToSpeech.ERROR)
                        utteranceLatch.countDown()
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        utteranceStatus.set(errorCode)
                        utteranceLatch.countDown()
                    }
                },
            )

            val outputFile = File(context.cacheDir, "opennlp-regression-tts.wav").also {
                it.delete()
            }
            val synthesizeStatus = textToSpeech.synthesizeToFile(
                "GrapheneOS OpenNLP synthesis test.",
                Bundle(),
                outputFile,
                "opennlp-regression",
            )
            assertEquals("synthesizeToFile was rejected", TextToSpeech.SUCCESS, synthesizeStatus)
            assertTrue("synthesis did not finish", utteranceLatch.await(120, TimeUnit.SECONDS))
            assertEquals("synthesis failed", TextToSpeech.SUCCESS, utteranceStatus.get())
            assertTrue("synthesis output file is empty", outputFile.length() > 44L)
        } finally {
            textToSpeech?.shutdown()
        }
    }
}
