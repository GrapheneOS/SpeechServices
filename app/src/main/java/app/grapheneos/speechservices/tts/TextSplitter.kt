package app.grapheneos.speechservices.tts

import app.grapheneos.speechservices.g2p.NUMBER_SUFFIXES
import java.text.Normalizer

class TextSplitter(text: CharSequence) : Iterator<String> {
    private val text = normalizeZeroWidthSpaces(text)
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
        var lastWordBoundary = chunkStart
        var lastNonWhitespace: Char? = null
        var numberEnd = chunkStart
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
            if (index > numberEnd) {
                numberEnd = findNumberEnd(index - 1)
            }

            if (!char.isWhitespace()) {
                lastNonWhitespace = char
            }
            val currencyPrefix = index < numberEnd &&
                char.isWhitespace() &&
                lastNonWhitespace?.category == CharCategory.CURRENCY_SYMBOL
            if (char.isWhitespace() && !currencyPrefix) {
                lastWordBoundary = index
            }
            if (currentQueueScore >= 500) {
                // Prefer whole words, but only extend the limit for a number.
                // Long compound text can make tokenization and lookup expensive.
                if (lastWordBoundary > chunkStart) {
                    index = lastWordBoundary
                } else {
                    index = maxOf(index, numberEnd)
                    // The phonemizer trims leading whitespace, so keep the
                    // separator with the preceding spoken word.
                    while (index < text.length && text[index].isWhitespace()) {
                        index++
                    }
                }
                break
            }
            if (!currencyPrefix && shouldBreakQueue(char, currentQueueScore)) {
                break
            }
        }

        val chunkStart = this.chunkStart
        this.chunkStart = this.index
        return text.substring(chunkStart, this.index)
    }

    private fun findNumberEnd(start: Int): Int {
        var end = start
        if (text[end].category == CharCategory.CURRENCY_SYMBOL) {
            end++
            while (end < text.length && text[end].isWhitespace()) {
                end++
            }
        }
        if (numberChar(end) == '-') {
            end = nextCharacter(end)
        }
        if (numberChar(end) == '.' || numberChar(end) == ',') {
            end = nextCharacter(end)
        }
        if (numberChar(end) != '0') {
            return start
        }
        end = nextCharacter(end)
        while (end < text.length) {
            val char = numberChar(end)
            val next = nextCharacter(end)
            if (char == '0') {
                end = next
            } else if ((char == '.' || char == ',') &&
                numberChar(next) == '0'
            ) {
                end = nextCharacter(next)
            } else {
                break
            }
        }
        if ((numberChar(end) == '.' || numberChar(end) == ',') &&
            text.getOrNull(nextCharacter(end))?.isWhitespace() != false
        ) {
            end = nextCharacter(end)
        }
        var suffixEnd = end
        var suffixText = ""
        while (suffixEnd < text.length) {
            val next = nextCharacter(suffixEnd)
            val char = text.substring(suffixEnd, next)
            suffixEnd = next
            suffixText += Normalizer.normalize(
                if (char == "‘" || char == "’") "'" else char,
                Normalizer.Form.NFKC,
            )
            if (NUMBER_SUFFIXES.none { it.startsWith(suffixText) }) {
                break
            }
            if (suffixText in NUMBER_SUFFIXES && !isNormalizedLetterAt(suffixEnd)) {
                return suffixEnd
            }
        }
        return end
    }

    private fun isNormalizedLetterAt(index: Int): Boolean {
        if (index == text.length) {
            return false
        }
        // Suffix boundaries must use the same normalization as the suffix.
        val normalized = Normalizer.normalize(
            text.substring(index, nextCharacter(index)),
            Normalizer.Form.NFKC,
        )
        return normalized.isNotEmpty() && Character.isLetter(normalized.codePointAt(0))
    }

    private fun numberChar(index: Int): Char? {
        val char = text.getOrNull(index) ?: return null
        if (char.isDigit()) {
            // The value does not affect where a number can be split.
            return '0'
        }
        if (char.code < 128) {
            return char
        }
        // Match the phonemizer's normalization without changing source offsets.
        // Only complete digit expansions qualify; fractions such as ½ do not.
        val normalized = Normalizer.normalize(
            text.substring(index, nextCharacter(index)),
            Normalizer.Form.NFKC,
        )
        return if (normalized.isNotEmpty() && normalized.all { it in '0'..'9' }) {
            '0'
        } else {
            normalized.singleOrNull()
        }
    }

    private fun nextCharacter(index: Int): Int {
        return index + Character.charCount(text.codePointAt(index))
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

// Some apps prefix announcements with U+200B ZERO WIDTH SPACE (to wake up the
// Google TTS engine), expecting TTS engines to stay silent on it, but the
// phonemizer fallback would read out its Unicode name. Elsewhere in the text
// it marks a word boundary, so it becomes a regular space there.
fun normalizeZeroWidthSpaces(text: CharSequence): String {
    val normalized = text.toString().trimStart { it == '\u200B' }.replace('\u200B', ' ')
    return if (normalized.isBlank()) "" else normalized
}
