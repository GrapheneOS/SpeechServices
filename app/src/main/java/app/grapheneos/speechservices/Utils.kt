package app.grapheneos.speechservices

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetFileDescriptor
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import java.util.BitSet
import kotlin.system.measureTimeMillis

inline fun verboseLog(tag: String, msg: () -> String) {
    if (Log.isLoggable(tag, Log.VERBOSE)) {
        Log.v(tag, msg.invoke())
    }
}


fun allocateDirectFloatBuffer(capacity: Int): FloatBuffer {
    return ByteBuffer.allocateDirect(
        capacity * Float.SIZE_BYTES,
    ).order(ByteOrder.nativeOrder()).asFloatBuffer()
}

fun createOrtSession(
    env: OrtEnvironment,
    modelFd: AssetFileDescriptor,
    opts: OrtSession.SessionOptions = OrtSession.SessionOptions(),
): OrtSession {
    return modelFd.createInputStream().use { inputStream ->
        val modelBuf = inputStream.channel
            .map(FileChannel.MapMode.READ_ONLY, modelFd.startOffset, modelFd.declaredLength)
        env.createSession(modelBuf, opts)
    }
}
