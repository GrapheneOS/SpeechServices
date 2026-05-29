package app.grapheneos.speechservices.g2p.fallback_network

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetFileDescriptor
import app.grapheneos.speechservices.closeAll
import app.grapheneos.speechservices.createOrtSession
import app.grapheneos.speechservices.OrtSessionOwner
import app.grapheneos.speechservices.g2p.MToken

/**
 * Converts graphemes to phonemes.
 */
class FallbackNetwork(
    modelFileDescriptor: AssetFileDescriptor,
    private val tokenizer: G2PTokenizer,
) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val sessionOwner = OrtSessionOwner(
        optionsSupplier = { OrtSession.SessionOptions() },
        sessionSupplier = { options -> createOrtSession(env, modelFileDescriptor, options) },
    )

    /**
     * Convert the token text to input IDs and run them through the model to get phonemes.
     */
    fun main(token: MToken): Pair<String, Int> {
        // The model that's currently used struggles with too many characters at once. Chunking
        //  makes it at least try to pronounce longer words, even if it sometimes doesn't do well
        //  due to losing context and bad chunk timing.
        // TODO: A model trained with RoPE should not have these issues.
        val outputText = token.text.chunked(11).joinToString("") { chunk ->
            var inputTensor: OnnxTensor? = null
            var runResult: OrtSession.Result? = null
            try {
                inputTensor = OnnxTensor.createTensor(env, arrayOf(tokenizer.encodeWord(chunk)))
                runResult = this.run(inputTensor)
                val outputIds = runResult[0].value as Array<*>
                tokenizer.decodePhonemes(outputIds[0] as LongArray)
            } finally {
                closeAll(runResult, inputTensor)
            }
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
    fun run(inputIds: OnnxTensor): OrtSession.Result {
        val inputs = HashMap<String, OnnxTensor>()
        inputs["input_ids"] = inputIds

        return sessionOwner.session.run(inputs)
    }

    override fun close() {
        sessionOwner.close()
    }
}
