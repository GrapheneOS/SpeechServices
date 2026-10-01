package app.grapheneos.speechservices

import android.media.AudioFormat
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InputSynthesisTest {
    @Test
    fun currencyAndLinkInputsProduceAudio() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val enginePackage = InstrumentationRegistry.getArguments().getString("enginePackage")
            ?: context.packageName
        val initialized = CountDownLatch(1)
        val initializationStatus = AtomicInteger(Int.MIN_VALUE)
        val tts = TextToSpeech(
            context,
            { status ->
                initializationStatus.set(status)
                initialized.countDown()
            },
            enginePackage,
        )
        try {
            assertTrue("TTS initialization timed out", initialized.await(30, TimeUnit.SECONDS))
            assertEquals(TextToSpeech.SUCCESS, initializationStatus.get())
            // TextToSpeech can initialize a fallback if the requested engine is unavailable.
            assertTrue(
                "Requested TTS engine is unavailable: $enginePackage",
                tts.engines.any { it.name == enginePackage },
            )
            assertTrue(tts.setLanguage(Locale.US) >= TextToSpeech.LANG_AVAILABLE)
            val cases = mapOf(
                "ordinary" to "The total is $3,000,000,000.00.",
                "spellout-limit" to "$1000000000000000000",
                "long-overflow-cents" to "$9223372036854775808.01",
                "negative" to "$-9223372036854775808",
                "links" to "[Hello](-) [world](2147483648).",
                "long-digits" to "$" + "9".repeat(125),
            )
            for ((name, text) in cases) {
                synthesize(tts, text, name, context.cacheDir)
            }
        } finally {
            tts.shutdown()
        }
    }

    private fun synthesize(tts: TextToSpeech, text: String, name: String, outputDir: File) {
        val finished = CountDownLatch(1)
        val status = AtomicInteger(Int.MIN_VALUE)
        val bytes = AtomicLong()
        val peak = AtomicInteger()
        val sampleRate = AtomicInteger()
        val channelCount = AtomicInteger()
        val audioFormat = AtomicInteger()
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onBeginSynthesis(
                    utteranceId: String?,
                    rate: Int,
                    format: Int,
                    channels: Int,
                ) {
                    sampleRate.set(rate)
                    audioFormat.set(format)
                    channelCount.set(channels)
                }

                override fun onAudioAvailable(utteranceId: String?, audio: ByteArray) {
                    bytes.addAndGet(audio.size.toLong())
                    for (index in 0 until audio.size - 1 step 2) {
                        val sample = (
                            (audio[index].toInt() and 255) or
                                (audio[index + 1].toInt() shl 8)
                            ).toShort().toInt()
                        peak.set(maxOf(peak.get(), kotlin.math.abs(sample)))
                    }
                }

                override fun onDone(utteranceId: String?) {
                    status.set(TextToSpeech.SUCCESS)
                    finished.countDown()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    status.set(TextToSpeech.ERROR)
                    finished.countDown()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    status.set(errorCode)
                    finished.countDown()
                }
            },
        )
        val output = File.createTempFile("tts-$name-", ".wav", outputDir)
        try {
            assertEquals(
                name,
                TextToSpeech.SUCCESS,
                tts.synthesizeToFile(text, Bundle(), output, name),
            )
            assertTrue("$name timed out", finished.await(120, TimeUnit.SECONDS))
            assertEquals(name, TextToSpeech.SUCCESS, status.get())
            assertEquals(name, AudioFormat.ENCODING_PCM_16BIT, audioFormat.get())
            assertEquals(name, 22050, sampleRate.get())
            assertEquals(name, 1, channelCount.get())
            assertTrue("$name produced no audio", bytes.get() > 0)
            assertTrue("$name produced silence", peak.get() > 0)
            assertTrue("$name produced an empty WAV", output.length() > 44)
        } finally {
            output.delete()
        }
    }
}
