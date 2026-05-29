package app.grapheneos.speechservices.tts

import ai.onnxruntime.OrtEnvironment
import android.media.AudioFormat
import android.os.SystemClock
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import app.grapheneos.speechservices.verboseLog
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.ceil
import kotlin.math.min

private const val TAG = "TextToSpeechServiceImpl"

private val voiceInitExecutor = Executors.newCachedThreadPool {
    Thread(it).apply {
        priority = Thread.MAX_PRIORITY
    }
}

typealias CancellationCheck = () -> Unit

class CancelledRequestException : Exception()

@androidx.media3.common.util.UnstableApi
class TextToSpeechServiceImpl : TextToSpeechService() {
    private val voiceResourcesInitLock = ReentrantLock()
    private var voiceResources: VoiceResources? = null

    private val symbolTokenizer = SymbolTokenizer()

    private val sonicAudioProcessor = SonicAudioProcessor()

    override fun onGetVoices(): List<Voice> {
        return supportedVoices
    }

    override fun onIsValidVoiceName(voiceName: String): Int {
        return if (isVoiceAvailable(voiceName)) {
            TextToSpeech.SUCCESS
        } else {
            TextToSpeech.ERROR
        }
    }

    override fun onLoadVoice(voiceName: String): Int {
        Log.d(TAG, "onLoadVoice parameters: voiceName: $voiceName")
        prepareVoiceResourcesAsync(voiceName)
        return TextToSpeech.SUCCESS
    }

    private fun prepareVoiceResourcesAsync(voiceName: String): VoiceResources? {
        val voice = getAvailableVoiceByName(voiceName) ?: return null
        return prepareVoiceResourcesAsync(voice)
    }

    private fun prepareVoiceResourcesAsync(voice: Voice): VoiceResources {
        return voiceResourcesInitLock.withLock {
            voiceResources?.let {
                if (it.voice == voice) {
                    return it
                }
                it.close()
                voiceResources = null
            }
            return@withLock loadVoiceResources(voiceInitExecutor, resources, voice).also {
                voiceResources = it
            }
        }
    }

    override fun onGetDefaultVoiceNameFor(
        lang: String,
        country: String?,
        variant: String?,
    ): String? {
        verboseLog(TAG) {
            "onGetDefaultVoiceNameFor parameters: lang: $lang, country: $country, variant: $variant"
        }

        return checkLanguageAvailability(lang, country, variant).voice?.name
    }

    override fun onIsLanguageAvailable(lang: String, country: String?, variant: String?): Int {
        verboseLog(TAG) {
            "onIsLanguageAvailable parameters: lang: $lang, country: $country, variant: $variant"
        }

        return checkLanguageAvailability(lang, country, variant).status
    }

    override fun onLoadLanguage(lang: String, country: String?, variant: String?): Int {
        Log.d(TAG, "onLoadLanguage parameters: lang: $lang, country: $country, variant: $variant")

        val (languageAvailability, defaultVoice) = checkLanguageAvailability(
            lang,
            country,
            variant,
        )

        when (languageAvailability) {
            TextToSpeech.LANG_NOT_SUPPORTED, TextToSpeech.LANG_MISSING_DATA -> {
                return languageAvailability
            }
        }
        if (defaultVoice == null) {
            return languageAvailability
        }

        prepareVoiceResourcesAsync(defaultVoice)

        return languageAvailability
    }

    override fun onGetLanguage(): Array<String> {
        Log.w(
            TAG,
            "onGetLanguage called, returning emptyArray(). Method is not supposed " +
                "to be called on modern Android versions (API > 17).",
        )
        return emptyArray()
    }

    @Volatile
    private var currentSynthesisRequest: SynthesisRequest? = null

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        verboseLog(TAG) {
            "onSynthesizeText parameters: charSequenceText: ${request.charSequenceText}, " +
                "voiceName: ${request.voiceName}, language: ${request.language}, " +
                "country: ${request.country}, variant: ${request.variant}, " +
                "speechRate: ${request.speechRate}, pitch: ${request.pitch}, " +
                "params.keySet(): ${request.params.keySet()}, " +
                "callerUid: ${request.callerUid}, " +
                "callback.maxBufferSize: ${callback.maxBufferSize}"
        }

        this.currentSynthesisRequest = request

        val cancellationCheck = {
            if (this.currentSynthesisRequest !== request) {
                throw CancelledRequestException()
            }
        }

        try {
            synthesizeText(request, callback, cancellationCheck)
        } catch (_: CancelledRequestException) {
            verboseLog(TAG) { "onSynthesizeText: request cancelled" }
        }

        if (callback.hasStarted()) {
            val res = callback.done()
            if (res != TextToSpeech.SUCCESS && request === this.currentSynthesisRequest) {
                Log.d(TAG, "onSynthesizeText: callback.done() returned ${statusToString(res)}")
            }
        }
        currentSynthesisRequest = null
    }

    override fun onStop() {
        val curRequest = currentSynthesisRequest
        currentSynthesisRequest = null
        verboseLog(TAG) {
            if (curRequest != null) {
                "onStop: canceled current SynthesisRequest"
            } else {
                "onStop: no current SynthesisRequest"
            }
        }
    }

    @Throws(CancelledRequestException::class)
    private fun synthesizeText(
        request: SynthesisRequest,
        callback: SynthesisCallback,
        cancellationCheck: CancellationCheck,
    ) {
        var timeToFirstAudio = -1L
        val startTime = SystemClock.elapsedRealtime()

        val requestVoice = getAvailableVoiceByName(request.voiceName)
            ?: checkLanguageAvailability(
                request.language,
                request.country,
                request.variant,
            ).voice
        if (requestVoice == null) {
            callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
            return
        }

        val voiceResources = prepareVoiceResourcesAsync(requestVoice)

        val startRet = callback.start(22050, AudioFormat.ENCODING_PCM_16BIT, 1)

        if (startRet != TextToSpeech.SUCCESS) {
            if (startRet != TextToSpeech.STOPPED) {
                Log.e(TAG, "SynthesisCallback.start() returned ${statusToString(startRet)}")
            }
            return
        }

        // TODO: support rangeStart()

        val maxCallbackBufSize = callback.maxBufferSize
        val callbackBuffer = cachedAudioCallbackByteBuf.let { cache ->
            if (cache != null && cache.size >= maxCallbackBufSize) {
                cache
            } else {
                ByteArray(maxCallbackBufSize).also { cachedAudioCallbackByteBuf = it }
            }
        }

        sonicAudioProcessor.apply {
            setOutputSampleRateHz(SonicAudioProcessor.SAMPLE_RATE_NO_CHANGE)
            configure(
                AudioProcessor.AudioFormat(
                    22050,
                    1,
                    C.ENCODING_PCM_FLOAT,
                ),
            )
            setSpeed(request.speechRate / 100F)
            setPitch(request.pitch / 100F)
        }

        TextSplitter(request.charSequenceText).forEach { chunk ->
            cancellationCheck()

            val phonemeText = voiceResources.englishPhonemizer.get().main(chunk, cancellationCheck)

            verboseLog(TAG) { "Queued phonemes: ${phonemeText.first}" }
            val queuePhoneIds =
                symbolTokenizer.encodeToIds(phonemeText.first)

            // Should be set to the input length that's supported by the decoder.
            val yMaxLengthInBatch = 64

            val encoderRes = voiceResources.encoder.get().run(queuePhoneIds)
            val yLengthBatchesSize =
                ceil(encoderRes.yLength.toFloat() / yMaxLengthInBatch.toFloat()).toInt()
            for (splitIndex in 0..<yLengthBatchesSize) {
                val startOfRange = splitIndex * yMaxLengthInBatch
                val rangeLength = yMaxLengthInBatch
                verboseLog(TAG) {
                    "onSynthesizeText decoding: startOfRange: $startOfRange, rangeLength: $rangeLength"
                }
                val pcmFloats = voiceResources.decoder.get().run(
                    encoderRes,
                    startOfRange,
                    rangeLength,
                    ::getCachedFloatByteBuffer1,
                    ::getCachedFloatByteBuffer2,
                )

                val result: ByteBuffer
                if (!sonicAudioProcessor.isActive) {
                    // no processing is needed
                    result = pcmFloats
                } else {
                    sonicAudioProcessor.flush(AudioProcessor.StreamMetadata.DEFAULT)
                    sonicAudioProcessor.queueInput(pcmFloats)
                    sonicAudioProcessor.queueEndOfStream()
                    result = sonicAudioProcessor.getOutput()
                }

                val resultFloat = result.asFloatBuffer()

                val floatBufferSize = maxCallbackBufSize / 2
                for (i in 0 until resultFloat.limit() step floatBufferSize) {
                    cancellationCheck()

                    val chunkLength = min(floatBufferSize, resultFloat.limit() - i)

                    // convert audio to PCM_S16LE since PCM_FLOAT is not supported by
                    // SynthesisCallback.audioAvailable() as of SDK 36.1
                    for (chunkIdx in 0 until chunkLength) {
                        val sample = (
                            resultFloat.get(i + chunkIdx)
                                .coerceIn(-1f, 1f) * 32767.0f
                            ).toInt()
                        val off = chunkIdx shl 1
                        callbackBuffer[off] = (sample and 0xFF).toByte()
                        callbackBuffer[off + 1] = ((sample ushr 8) and 0xFF).toByte()
                    }

                    if (timeToFirstAudio == -1L) {
                        timeToFirstAudio =
                            SystemClock.elapsedRealtime() - startTime
                        Log.d(TAG, "time-to-first-audio: $timeToFirstAudio")
                    }

                    val result = callback.audioAvailable(callbackBuffer, 0, chunkLength shl 1)

                    if (result != TextToSpeech.SUCCESS) {
                        if (currentSynthesisRequest === request) {
                            Log.d(TAG, "audioAvailable returned ${statusToString(result)}")
                        } else {
                            verboseLog(TAG) { "audioAvailable returned ${statusToString(result)}" }
                        }
                        if (result != TextToSpeech.STOPPED) {
                            callback.error(TextToSpeech.ERROR_OUTPUT)
                        }
                        return
                    }
                }
                check(!sonicAudioProcessor.output.hasRemaining())
            }
        }
    }

    // should be used only on the synthesis thread
    private var cachedAudioCallbackByteBuf: ByteArray? = null
    private var cachedFloatByteBuffer1: FloatByteBuffer? = null
    private var cachedFloatByteBuffer2: FloatByteBuffer? = null

    // should be called on the synthesis thread
    private fun getCachedFloatByteBuffer1(requiredCapacity: Int): FloatByteBuffer {
        return FloatByteBuffer.getOrAlloc(cachedFloatByteBuffer1, requiredCapacity) {
            cachedFloatByteBuffer1 =
                it
        }
    }

    // should be called on the synthesis thread
    private fun getCachedFloatByteBuffer2(requiredCapacity: Int): FloatByteBuffer {
        return FloatByteBuffer.getOrAlloc(cachedFloatByteBuffer2, requiredCapacity) {
            cachedFloatByteBuffer2 =
                it
        }
    }

    override fun onTrimMemory(level: Int) {
        Log.d(TAG, "onTrimMemory: $level")
        if (level >= TRIM_MEMORY_BACKGROUND) {
            cachedFloatByteBuffer1 = null
            cachedFloatByteBuffer2 = null
            cachedAudioCallbackByteBuf = null
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        super.onDestroy()
        currentSynthesisRequest = null
        voiceResourcesInitLock.withLock {
            voiceResources?.let {
                voiceInitExecutor.submit<Unit> {
                    it.close()
                }
                voiceResources = null
            }
        }
        sonicAudioProcessor.reset()
    }

    private fun statusToString(status: Int): String {
        return when (status) {
            TextToSpeech.SUCCESS -> "SUCCESS"
            TextToSpeech.ERROR -> "ERROR"
            TextToSpeech.STOPPED -> "STOPPED"
            else -> status.toString()
        }
    }
}
