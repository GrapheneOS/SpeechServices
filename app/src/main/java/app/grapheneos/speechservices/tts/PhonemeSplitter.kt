package app.grapheneos.speechservices.tts

// Text must be phonemized before a long number can be split without changing
// its meaning. Bound encoder inputs using the resulting spoken words instead.
class PhonemeSplitter(private val phonemes: String, private val maxLength: Int = 500) :
    Iterator<String> {
    private var index = 0

    init {
        require(maxLength > 0)
    }

    override fun hasNext(): Boolean = index < phonemes.length

    override fun next(): String {
        if (!hasNext()) {
            throw NoSuchElementException()
        }
        val start = index
        var end = minOf(phonemes.length, start + maxLength)
        if (end < phonemes.length) {
            for (boundary in end downTo start + 1) {
                if (phonemes[boundary - 1].isWhitespace()) {
                    end = boundary
                    break
                }
            }
        }
        index = end
        return phonemes.substring(start, end)
    }
}
