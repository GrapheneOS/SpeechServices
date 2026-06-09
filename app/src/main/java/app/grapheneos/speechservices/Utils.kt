package app.grapheneos.speechservices

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.content.res.AssetFileDescriptor
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import java.util.BitSet
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.system.measureTimeMillis

inline fun verboseLog(tag: String, msg: () -> String) {
    if (Log.isLoggable(tag, Log.VERBOSE)) {
        Log.v(tag, msg.invoke())
    }
}

inline fun <T> verboseLogTime(tag: String, prefix: String, block: () -> T): T {
    val res: T
    val time = measureTimeMillis {
        res = block()
    }
    verboseLog(tag) { "$prefix took $time ms" }
    return res
}

fun String.toBitSet(): BitSet {
    val bitSet = BitSet(128)
    for (ch in this) {
        bitSet.set(ch.code)
    }
    return bitSet
}

operator fun BitSet.contains(ch: Char) = get(ch.code)

fun String.hasNoneOf(chars: BitSet): Boolean {
    return none { it in chars }
}

fun String.isOneOf(a: String, b: String): Boolean {
    return this == a || this == b
}

fun String.isOneOf(a: String, b: String, c: String): Boolean {
    return this == a || this == b || this == c
}

fun allocateDirectFloatBuffer(capacity: Int): FloatBuffer {
    return ByteBuffer.allocateDirect(
        capacity * Float.SIZE_BYTES,
    ).order(ByteOrder.nativeOrder()).asFloatBuffer()
}

class OrtSessionWrapper(
    val env: OrtEnvironment,
    private val inner: OrtSession,
    private val opts: OrtSession.SessionOptions,
) : AutoCloseable {
    private val lock = ReentrantReadWriteLock(true)
    private var closed = false

    fun run(inputs: Map<String, OnnxTensor>): OrtSession.Result {
        val readLock = lock.readLock()
        readLock.lock()
        try {
            if (closed) {
                throw OrtException("OrtSession is closed")
            }
            return inner.run(inputs)
        } finally {
            readLock.unlock()
        }
    }

    override fun close() {
        val writeLock = lock.writeLock()
        writeLock.lock()
        try {
            if (closed) {
                return
            }

            closed = true

            try {
                inner.close()
            } catch (e: OrtException) {
                Log.e(TAG, "unable to close OrtSession", e)
            } finally {
                opts.close()
            }
        } finally {
            writeLock.unlock()
        }
    }

    private companion object {
        private const val TAG = "OrtSessionWrapper"
    }
}

fun createOrtSession(
    env: OrtEnvironment = OrtEnvironment.getEnvironment(),
    modelFd: AssetFileDescriptor,
    optsSupplier: () -> OrtSession.SessionOptions = { OrtSession.SessionOptions() },
): OrtSessionWrapper {
    modelFd.createInputStream().use { inputStream ->
        val modelBuf = inputStream.channel
            .map(FileChannel.MapMode.READ_ONLY, modelFd.startOffset, modelFd.declaredLength)
        val opts = optsSupplier()
        var closeOpts = true
        try {
            val session = env.createSession(modelBuf, opts)
            closeOpts = false
            return OrtSessionWrapper(env, session, opts)
        } finally {
            if (closeOpts) {
                opts.close()
            }
        }
    }
}
