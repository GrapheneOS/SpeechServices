package app.grapheneos.speechservices.tts

import android.content.res.Resources
import android.speech.tts.Voice
import app.grapheneos.speechservices.R
import app.grapheneos.speechservices.g2p.DictionaryValue
import app.grapheneos.speechservices.g2p.EnglishPhonemizer
import app.grapheneos.speechservices.g2p.Lexicon
import app.grapheneos.speechservices.g2p.fallback_network.FallbackNetwork
import app.grapheneos.speechservices.g2p.fallback_network.G2PTokenizer
import app.grapheneos.speechservices.g2p.fallback_network.G2PTokenizerConfig
import app.grapheneos.speechservices.verboseLogTime
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import opennlp.tools.postag.POSModel
import opennlp.tools.postag.POSTaggerME
import opennlp.tools.tokenize.TokenizerME
import opennlp.tools.tokenize.TokenizerModel
import org.json.JSONObject

private const val TAG = "VoiceResources"

class VoiceResources(
    val voice: Voice,
    val encoder: Future<Encoder>,
    val decoder: Future<Decoder>,
    val englishPhonemizer: Future<EnglishPhonemizer>,
) {
    fun close() {
        encoder.get().close()
        decoder.get().close()
        englishPhonemizer.get().close()
    }
}

fun loadVoiceResources(
    executor: ExecutorService,
    resources: Resources,
    voice: Voice,
): VoiceResources {
    check(voice == DefaultVoice.EnUs.voice)
    val encoder = executor.submit<Encoder> {
        verboseLogTime(TAG, "encoder loading") {
            resources.openRawResourceFd(R.raw.encoder).use {
                Encoder(it)
            }
        }
    }
    val decoder = executor.submit<Decoder> {
        verboseLogTime(TAG, "decoder loading") {
            resources.openRawResourceFd(R.raw.decoder).use {
                Decoder(it)
            }
        }
    }
    val lexicon = executor.submit<Lexicon> {
        verboseLogTime(TAG, "lexicon loading") {
            val dict = resources.openRawResource(R.raw.us_gold).buffered().use { inputStream ->
                @OptIn(ExperimentalSerializationApi::class)
                Json.decodeFromStream<Map<String, DictionaryValue>>(inputStream)
            }
            Lexicon(false, dict)
        }
    }
    val fallbackNetwork = executor.submit<FallbackNetwork> {
        verboseLogTime(TAG, "fallbackNetwork total loading") {
            val g2PTokenizer = verboseLogTime(TAG, "fallbackNetwork tokenizer loading") {
                G2PTokenizer(loadG2PTokenizerConfig(resources))
            }
            resources.openRawResourceFd(R.raw.en_us__g2p).use {
                FallbackNetwork(it, g2PTokenizer)
            }
        }
    }
    val englishPhonemizer = executor.submit<EnglishPhonemizer> {
        verboseLogTime(TAG, "englishPhonemizer loading") {
            val tokenizer = executor.submit<TokenizerME> {
                resources.openRawResource(
                    R.raw.opennlp_en_ud_ewt_tokens__1_3__2_5_4,
                ).buffered().use {
                    TokenizerME(TokenizerModel(it))
                }
            }
            val posTagger = executor.submit<POSTaggerME> {
                resources.openRawResource(R.raw.opennlp_en_ud_ewt_pos__1_3__2_5_4).buffered().use {
                    POSTaggerME(POSModel(it))
                }
            }
            EnglishPhonemizer(
                lexicon.get(),
                "ˌʌnnˈOn", // "unknown"
                tokenizer.get(),
                posTagger.get(),
                fallbackNetwork.get(),
            )
        }
    }
    return VoiceResources(voice, encoder, decoder, englishPhonemizer)
}

private fun loadG2PTokenizerConfig(resources: Resources): G2PTokenizerConfig {
    resources.openRawResource(R.raw.en_us__g2p__config).use { inputStream ->
        val config = JSONObject(inputStream.readAllBytes().toString(UTF_8))
        return G2PTokenizerConfig(
            config.getString("grapheme_chars"),
            config.getString("phoneme_chars"),
        )
    }
}
