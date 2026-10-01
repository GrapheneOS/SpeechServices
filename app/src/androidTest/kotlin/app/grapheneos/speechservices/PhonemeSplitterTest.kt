package app.grapheneos.speechservices

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.grapheneos.speechservices.tts.PhonemeSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhonemeSplitterTest {
    @Test
    fun longDigitPronunciationsKeepEverySpokenWord() {
        val input = "mˈInəs " + "nˈIn zˈiɹO sˈɛvən ".repeat(4000) + "dˈɑləɹz"
        val chunks = PhonemeSplitter(input).asSequence().toList()
        assertEquals(input, chunks.joinToString(""))
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.isNotEmpty() && it.length <= 500 })
        assertTrue(chunks.dropLast(1).all { it.last().isWhitespace() })
    }

    @Test
    fun shortPronunciationsAreUnchanged() {
        for (input in listOf("həlˈO wˈɜɹld.", "wˈʌn", "n".repeat(500))) {
            assertEquals(listOf(input), PhonemeSplitter(input).asSequence().toList())
        }
    }

    @Test
    fun wordsLongerThanTheLimitStillHaveBoundedChunks() {
        val input = "a".repeat(1400)
        val chunks = PhonemeSplitter(input).asSequence().toList()
        assertEquals(listOf(500, 500, 400), chunks.map { it.length })
        assertEquals(input, chunks.joinToString(""))
    }

    @Test
    fun whitespaceAndPunctuationArePreserved() {
        val input = "ab, cd.\nef\tgh! ij"
        val chunks = PhonemeSplitter(input, maxLength = 7).asSequence().toList()
        assertEquals(listOf("ab, ", "cd.\nef\t", "gh! ij"), chunks)
    }

    @Test
    fun emptyPronunciationsHaveNoChunks() {
        assertFalse(PhonemeSplitter("").hasNext())
    }

    @Test(expected = NoSuchElementException::class)
    fun exhaustedIteratorThrows() {
        val chunks = PhonemeSplitter("one")
        chunks.next()
        assertFalse(chunks.hasNext())
        chunks.next()
    }
}
