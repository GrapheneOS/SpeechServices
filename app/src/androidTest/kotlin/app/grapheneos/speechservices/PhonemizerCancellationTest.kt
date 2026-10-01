package app.grapheneos.speechservices

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.grapheneos.speechservices.g2p.MToken
import app.grapheneos.speechservices.g2p.fallback_network.FallbackNetwork
import app.grapheneos.speechservices.g2p.fallback_network.G2PTokenizer
import app.grapheneos.speechservices.g2p.fallback_network.G2PTokenizerConfig
import app.grapheneos.speechservices.tts.CancelledRequestException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhonemizerCancellationTest {
    @Test
    fun longUnknownWordCanBeCancelledBetweenModelCalls() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val config = resources.openRawResource(R.raw.en_us__g2p__config).bufferedReader().use {
            JSONObject(it.readText())
        }
        val tokenizer = G2PTokenizer(
            G2PTokenizerConfig(
                config.getString("grapheme_chars"),
                config.getString("phoneme_chars"),
            ),
        )
        resources.openRawResourceFd(R.raw.en_us__g2p).use { model ->
            FallbackNetwork(model, tokenizer).use { fallback ->
                var checks = 0
                assertThrows(CancelledRequestException::class.java) {
                    fallback.main(MToken("qzxw".repeat(1000), "NOUN", "")) {
                        checks++
                        if (checks == 3) {
                            throw CancelledRequestException()
                        }
                    }
                }
                assertEquals(3, checks)
            }
        }
    }
}
