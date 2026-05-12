package app.grapheneos.speechservices.tts

class TextSplitter(text: CharSequence) : Iterator<String> {
    private val text = text.toString()
    private var chunkStart: Int = 0
    private var index: Int = 0

    override fun hasNext(): Boolean {
        return index != text.length
    }

    override fun next(): String {
        if (index == text.length) {
            throw NoSuchElementException()
        }

        // Score based on some characters likely being longer in phonemes, meant to
        // ensure fast time-to-first-audio.
        var currentQueueScore = 0
        while (true) {
            val char = text[index++]
            if (index == text.length) {
                break
            }
            currentQueueScore += if (char.isDigit()) {
                4
            } else if (char == '-') {
                11
            } else {
                1
            }

            if (shouldBreakQueue(char, currentQueueScore)) {
                break
            }
        }

        val chunkStart = this.chunkStart
        this.chunkStart = this.index
        return text.substring(chunkStart, this.index)
    }

    private fun chunkEndsWith(s: String): Boolean {
        val start = this.chunkStart
        val end = this.index
        if (end - start < s.length) {
            return false
        }
        return text.startsWith(s, end - s.length)
    }

    private fun shouldBreakQueue(char: Char, currentQueueScore: Int): Boolean {
        val bestQueueBreakOnlyThreshold = 250
        val midAndUpQueueBreakOnlyThreshold = 350
        val mehAndUpQueueBreakOnlyThreshold = 450
        val worstAndUpQueueBreakOnlyThreshold = 500

        if (currentQueueScore >= worstAndUpQueueBreakOnlyThreshold) {
            return true
        }

        val isLetter = (char in 'a'..'z') || (char in 'A'..'Z')
        if (isLetter) {
            return false
        }

        if (chunkEndsWith(". ") ||
            chunkEndsWith("? ") ||
            chunkEndsWith("! ") ||
            chunkEndsWith("\n")
        ) {
            return true
        }

        if (currentQueueScore >= midAndUpQueueBreakOnlyThreshold) {
            if (chunkEndsWith(", ")) {
                return true
            }
        }
        if (currentQueueScore >= mehAndUpQueueBreakOnlyThreshold) {
            if (char.isWhitespace()) {
                return true
            }
        }

        if (currentQueueScore >= bestQueueBreakOnlyThreshold) {
            if (chunkEndsWith(": ") ||
                chunkEndsWith(" ;") ||
                chunkEndsWith("—") ||
                chunkEndsWith(" ...") ||
                chunkEndsWith("... ") ||
                chunkEndsWith(" …") ||
                chunkEndsWith("… ") ||
                chunkEndsWith(""" """") ||
                chunkEndsWith("""" """) ||
                chunkEndsWith(" “") ||
                chunkEndsWith("” ") ||
                chunkEndsWith(" (") ||
                chunkEndsWith(") ")
            ) {
                return true
            }
        }

        return false
    }
}
