package app.grapheneos.speechservices.g2p.fallback_network

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.res.AssetFileDescriptor
import app.grapheneos.speechservices.OrtSessionWrapper
import app.grapheneos.speechservices.createOrtSession
import app.grapheneos.speechservices.g2p.MToken

/**
 * Converts graphemes to phonemes.
 */
class FallbackNetwork(
    modelFileDescriptor: AssetFileDescriptor,
    private val tokenizer: G2PTokenizer,
) : AutoCloseable {
    private val session: OrtSessionWrapper = createOrtSession(modelFd = modelFileDescriptor)

    /**
     * Convert the token text to input IDs and run them through the model to get phonemes.
     */
    fun main(token: MToken): Pair<String, Int> {
        // The model that's currently used struggles with too many characters at once. Chunking
        //  makes it at least try to pronounce longer words, even if it sometimes doesn't do well
        //  due to losing context and bad chunk timing.
        // TODO: A model trained with RoPE should not have these issues.
        val outputText = token.text.chunked(11).joinToString("") { chunk ->
            val outputIds: LongArray
            OnnxTensor.createTensor(
                session.env,
                arrayOf(tokenizer.encodeWord(chunk)),
            ).use { inputIds ->
                runInner(inputIds).use { result ->
                    check(result.size() == 1)
                    val wrapper = result[0].value as Array<*>
                    check(wrapper.size == 1)
                    outputIds = wrapper[0] as LongArray
                }
            }

            return@joinToString tokenizer.decodePhonemes(outputIds)
        }
        return Pair(outputText, 1)
    }

    /**
     * @param inputIds [Array] (length = batch size) of [LongArray] where each item in the first
     * dimension represents the input IDs of the item with the same first dimensional index in
     * return value `outputIds`.
     *
     * @return [OrtSession.Result] where each index represents a value from the return result.
     * Return result index to value list:
     * 1. `outputIds` - Output phoneme IDs. [Array] (length = batch size) of [LongArray].
     */
    private fun runInner(inputIds: OnnxTensor): OrtSession.Result {
        val inputs = HashMap<String, OnnxTensor>()
        inputs["input_ids"] = inputIds

        return session.inner.run(inputs)
    }

    override fun close() {
        session.close()
    }
}
