package app.grapheneos.speechservices

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.channels.FileChannel
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnnxSessionCloseRaceTest {
    private val env = OrtEnvironment.getEnvironment()
    private val random = Random(0xc105eL)

    @Test
    fun closeWaitsForRunningDecoderInference() {
        repeat(ITERATIONS) { iteration ->
            Log.i(TAG, "iteration $iteration")
            raceCloseAgainstRun()
        }
    }

    private fun raceCloseAgainstRun() {
        val session = openDecoder()
        val runStarting = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val runner = Thread(
            { runDecoder(session, runStarting, failure) },
            "decoder-close-race",
        )

        session.use { session ->
            runner.start()
            if (!runStarting.await(5, TimeUnit.SECONDS)) {
                fail("decoder run did not start")
            }

            SystemClock.sleep(CLOSE_DELAY_MS)
            session.close()

            runner.join(30_000)
            assertFalse("decoder thread did not finish", runner.isAlive)
            assertNull("decoder run failed", failure.get())
        }
    }

    private fun runDecoder(
        session: OrtSessionWrapper,
        runStarting: CountDownLatch,
        failure: AtomicReference<Throwable>,
    ) {
        try {
            OnnxTensor.createTensor(
                session.env,
                randomFloats(),
                longArrayOf(1, FEATURE_COUNT.toLong(), RANGE_LENGTH.toLong()),
            ).use { muY ->
                OnnxTensor.createTensor(
                    session.env,
                    yMask(),
                    longArrayOf(1, 1, RANGE_LENGTH.toLong()),
                ).use { yMask ->
                    OnnxTensor.createTensor(
                        session.env,
                        LongBuffer.wrap(longArrayOf(5)),
                        longArrayOf(1),
                    ).use { nTimesteps ->
                        OnnxTensor.createTensor(
                            session.env,
                            temperature(),
                            longArrayOf(1),
                        ).use { temperature ->
                            val inputs = HashMap<String, OnnxTensor>()
                            inputs["mu_y"] = muY
                            inputs["y_mask"] = yMask
                            inputs["n_timesteps"] = nTimesteps
                            inputs["temperature"] = temperature

                            runStarting.countDown()
                            session.run(inputs).close()
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            failure.set(t)
        }
    }

    private fun openDecoder(): OrtSessionWrapper {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val id = context.resources.getIdentifier("decoder", "raw", context.packageName)
        if (id == 0) {
            error("decoder resource not found")
        }

        val options = OrtSession.SessionOptions()
        var closeOptions = true
        try {
            context.resources.openRawResourceFd(id).use { fd ->
                fd.createInputStream().use { inputStream ->
                    val model = inputStream.channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        fd.startOffset,
                        fd.declaredLength,
                    )
                    val session = env.createSession(model, options)
                    closeOptions = false
                    return OrtSessionWrapper(env, session, options)
                }
            }
        } finally {
            if (closeOptions) {
                options.close()
            }
        }
    }

    private fun randomFloats(): FloatBuffer {
        val buffer = directFloatBuffer(FEATURE_COUNT * RANGE_LENGTH)
        repeat(FEATURE_COUNT * RANGE_LENGTH) {
            buffer.put((random.nextFloat() - 0.5f) * 2.0f)
        }
        buffer.flip()
        return buffer
    }

    companion object {
        private const val TAG = "OrtCloseRace"
        private const val ITERATIONS = 40
        private const val CLOSE_DELAY_MS = 20L
        private const val FEATURE_COUNT = 80
        private const val RANGE_LENGTH = 64

        private fun yMask(): FloatBuffer {
            val buffer = directFloatBuffer(RANGE_LENGTH)
            repeat(RANGE_LENGTH) {
                buffer.put(0f)
            }
            buffer.flip()
            return buffer
        }

        private fun temperature(): FloatBuffer {
            val buffer = directFloatBuffer(1)
            buffer.put(0.667f)
            buffer.flip()
            return buffer
        }

        private fun directFloatBuffer(size: Int): FloatBuffer {
            return ByteBuffer.allocateDirect(size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
        }
    }
}
