package app.grapheneos.speechservices.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensor.createTensor
import ai.onnxruntime.OrtSession
import android.content.res.AssetFileDescriptor
import app.grapheneos.speechservices.OrtSessionWrapper
import app.grapheneos.speechservices.allocateDirectFloatBuffer
import app.grapheneos.speechservices.createOrtSession
import java.nio.ByteBuffer
import java.nio.LongBuffer
import kotlin.math.min
import kotlin.use

/**
 * Converts encoder output into audio.
 *
 * @see Encoder
 */
class Decoder(modelFileDescriptor: AssetFileDescriptor) : AutoCloseable {
    private val session: OrtSessionWrapper = createOrtSession(modelFd = modelFileDescriptor)

    fun run(
        encoderRes: Encoder.Result,
        startOfRange: Int,
        rangeLength: Int,
        floatBufSupplier1: (Int) -> FloatByteBuffer,
        floatBufSupplier2: (Int) -> FloatByteBuffer,
    ): ByteBuffer {
        val endOfRange = startOfRange + rangeLength
        val muYb = floatBufSupplier1(encoderRes.muY.size * rangeLength).floatBuffer
        for (secondDimObject in encoderRes.muY) {
            muYb.put(
                secondDimObject,
                startOfRange,
                min(
                    rangeLength,
                    secondDimObject.size - startOfRange,
                ),
            )
            repeat(endOfRange - secondDimObject.size) {
                muYb.put(0f)
            }
        }
        muYb.flip()
        val yMaskBuf = floatBufSupplier2(encoderRes.yMask.size * rangeLength).floatBuffer
        for (secondDimObject in encoderRes.yMask) {
            val copyLength = min(rangeLength, secondDimObject.size - startOfRange)
            yMaskBuf.put(secondDimObject, startOfRange, copyLength)
            repeat(rangeLength - copyLength) {
                yMaskBuf.put(1f)
            }
        }
        yMaskBuf.flip()

        val ortEnv = session.env

        val pcmFloats: FloatArray
        createTensor(
            ortEnv,
            muYb,
            longArrayOf(1, encoderRes.muY.size.toLong(), rangeLength.toLong()),
        ).use { muY ->
            createTensor(
                ortEnv,
                yMaskBuf,
                longArrayOf(1, encoderRes.yMask.size.toLong(), rangeLength.toLong()),
            ).use { yMask ->
                createTensor(
                    ortEnv,
                    LongBuffer.wrap(longArrayOf(5)),
                    longArrayOf(1),
                ).use { nTimesteps ->
                    runInner(
                        muY,
                        yMask,
                        nTimesteps,
                    ).use { result ->
                        check(result.size() == 1)
                        @Suppress("UNCHECKED_CAST")
                        val wrapper = result[0].value as Array<FloatArray>
                        check(wrapper.size == 1)
                        pcmFloats = wrapper[0]
                    }
                }
            }
        }

        val unpaddedSize =
            (min(endOfRange, encoderRes.yLength.toInt()) - startOfRange) * 256

        val input = floatBufSupplier1(unpaddedSize)
        input.floatBuffer.put(pcmFloats, 0, unpaddedSize)
        input.byteBuffer.limit(unpaddedSize * Float.SIZE_BYTES)
        return input.byteBuffer
    }

    /**
     * @param muY Actual encoder output. See [Encoder.run].
     * @param yMask Mask for encoder output. See [Encoder.run].
     * @param nTimesteps [LongArray] (length = 1) which should only contain 1 item that represents
     * how many synthesis steps the decoder will take. A value of 5 seems to be a good balance of
     * speed and quality.
     * @param spks See [Encoder.run].
     *
     * @return [OrtSession.Result] where each index represents a value from the return result.
     * Return result index to value list:
     * 1. `pcmFloatWav` - [Array] (length = batch size) of [FloatArray] (length = length of the
     * longest item in the first dimension) where each item in the first dimension is audio encoded
     * in PCM Float (-1.0 to 1.0 range for each subitem). Non-padded length of each item in the
     * first dimension corresponds to [Encoder.run] return value `yLengths` multiplied by the hop
     * length of the mel-spectogram.
     */
    private fun runInner(
        muY: OnnxTensor,
        yMask: OnnxTensor,
        nTimesteps: OnnxTensor,
        spks: OnnxTensor? = null,
    ): OrtSession.Result {
        createTensor(
            session.env,
            allocateDirectFloatBuffer(1).apply {
                put(0.667f)
                flip()
            },
            longArrayOf(1),
        ).use { temperature ->
            val inputs = HashMap<String, OnnxTensor>()
            inputs["mu_y"] = muY
            inputs["y_mask"] = yMask
            inputs["n_timesteps"] = nTimesteps
            inputs["temperature"] = temperature
            if (spks != null) {
                inputs["spks"] = spks
            }

            return session.inner.run(inputs)
        }
    }

    override fun close() {
        session.close()
    }
}
