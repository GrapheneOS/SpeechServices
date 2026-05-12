package app.grapheneos.speechservices.tts

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class FloatByteBuffer(val floatBuffer: FloatBuffer, val byteBuffer: ByteBuffer) {
    companion object {
        fun allocate(capacity: Int): FloatByteBuffer {
            val bb = ByteBuffer.allocateDirect(
                capacity * Float.SIZE_BYTES,
            ).order(ByteOrder.nativeOrder())
            return FloatByteBuffer(bb.asFloatBuffer(), bb)
        }

        inline fun getOrAlloc(
            current: FloatByteBuffer?,
            requiredCapacity: Int,
            update: (FloatByteBuffer) -> Unit,
        ): FloatByteBuffer {
            return if (current == null || current.floatBuffer.capacity() < requiredCapacity) {
                allocate(requiredCapacity).also { update(it) }
            } else {
                current.floatBuffer.clear()
                current.byteBuffer.clear()
                current
            }
        }
    }
}
