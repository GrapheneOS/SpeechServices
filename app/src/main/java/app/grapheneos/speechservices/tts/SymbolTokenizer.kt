package app.grapheneos.speechservices.tts

import app.grapheneos.speechservices.verboseLog

private const val TAG: String = "SymbolTokenizer"

class SymbolTokenizer {
    /**
     * Encode a string of phoneme and punctuation to Matcha symbol IDs (Long) including padding.
     *
     * Skips unknown characters.
     */
    fun encodeToIds(phonemeText: String): LongArray {
        val res = LongArray(phonemeText.length * 2 + 1)
        var index = 0

        charLoop@ for (char in phonemeText) {
            val id = Symbols.index.getOrElse(char.code) {
                verboseLog(TAG) { "Unknown character: $char" }
                continue@charLoop
            }
            res[index] = Symbols.PAD_ID
            res[index + 1] = id.toLong()
            index += 2
        }
        res[index++] = Symbols.PAD_ID

        if (index == res.size) {
            return res
        } else {
            return res.copyOf(index)
        }
    }
}
