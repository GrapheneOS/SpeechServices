package app.grapheneos.speechservices

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.grapheneos.speechservices.tts.TextSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextSplitterNumberTest {
    @Test
    fun numbersStayIntactAcrossChunkBoundaries() {
        val numbers = listOf(
            "12345",
            "-12345.67",
            ".000001",
            "1,000,001st",
            "$9223372036854775808.01",
            "£-12345.67",
            "€12345.67",
            "$ 12345.67",
            "$\n12345.67",
            "€ \t12345.67",
            "€\t-12345.67",
            "€\n-12345.67",
            "1000000000000000000th",
            "１２３４５.６７",
        )
        for (number in numbers) {
            for (offset in 0..500) {
                val prefix = "a ".repeat(offset / 2) + if (offset % 2 == 0) "" else " "
                val input = prefix + number + " after."
                val chunks = TextSplitter(input).asSequence().toList()
                assertEquals(input, input, chunks.joinToString(""))
                assertTrue("$number at $offset: $chunks", chunks.any { number in it })
            }
        }
    }

    @Test
    fun numbersLongerThanATextChunkStayIntact() {
        for (number in listOf(
            "9".repeat(144),
            "$" + "9".repeat(144) + ".01",
            "0." + "0".repeat(600) + "1",
            "9".repeat(600) + "th",
            "9".repeat(4000),
            "1.".repeat(2000),
            "9".repeat(144) + "ｓｔ",
            "9".repeat(144) + "sⓣ",
            "9".repeat(144) + "ⓢⓣ",
            "9".repeat(144) + "ｓⓣ",
            "$" + "９".repeat(144) + "．０５",
            "①".repeat(1000),
            "𝟡".repeat(1000) + "𝕥𝕙",
        )) {
            val chunks = TextSplitter(number).asSequence().toList()
            assertEquals(listOf(number), chunks)
        }
    }

    @Test
    fun oversizedNumberDoesNotDelayEarlierProse() {
        val prefix = "hello ".repeat(71) + " "
        val number = "$" + "9".repeat(144)
        val chunks = TextSplitter(prefix + number + " after").asSequence().toList()
        assertEquals(prefix, chunks.first())
        assertTrue(chunks[1].startsWith(number))
        assertEquals(prefix + number + " after", chunks.joinToString(""))
    }

    @Test
    fun existingSentenceBoundariesArePreserved() {
        val input = "Hello. Next! Really? Yes.\nLast."
        assertEquals(
            listOf("Hello. ", "Next! ", "Really? ", "Yes.\n", "Last."),
            TextSplitter(input).asSequence().toList(),
        )
    }

    @Test
    fun standaloneCurrencySymbolsKeepLineBreaks() {
        for (symbol in listOf('$', '£', '€', '＄')) {
            for (whitespace in listOf("\n", " \t\n")) {
                val prefix = "hello $symbol$whitespace"
                assertEquals(
                    listOf(prefix, "world"),
                    TextSplitter(prefix + "world").asSequence().toList(),
                )
            }
        }
    }

    @Test
    fun hardLimitUsesThePreviousWordBoundary() {
        val prefix = "word ".repeat(88)
        val word = "a".repeat(80)
        assertEquals(
            listOf(prefix, "$word after"),
            TextSplitter(prefix + word + " after").asSequence().toList(),
        )
    }

    @Test
    fun longCompoundTextKeepsTheExistingLimit() {
        for (part in listOf("hello/", "a-a-", "a1", "1-2/", ".", "qzxw", "½")) {
            val input = part.repeat(4000 / part.length + 1).take(4000)
            val chunks = TextSplitter(input).asSequence().toList()
            assertEquals(input, chunks.joinToString(""))
            assertTrue(part, chunks.size > 1)
            assertTrue(
                part,
                chunks.all { chunk ->
                    chunk.sumOf {
                        if (it.isDigit()) {
                            4
                        } else if (it == '-') {
                            11
                        } else {
                            1
                        }
                    } <= 510
                },
            )
        }
    }

    @Test
    fun forcedCutPreservesNumericPrefixesAndSuffixes() {
        val numbers = listOf(
            "12345.67",
            "-12345.67",
            ".000001",
            "$ 12345.67",
            "€\t-12345.67",
            "１２３４５.６７",
            "－１２，３４５．６７",
            "١٢٣٤٥.٦٧",
            "¹²³⁴⁵.⑥⑦",
            "𝟙𝟚𝟛𝟜𝟝.𝟞𝟟",
        ) + listOf(
            "st", "nd", "rd", "th", "ing", "'d", "ed", "'s", "s", "’s", "‘d", "ｓｔ", "ﬆ",
            "sⓣ", "ⓢⓣ", "ｓⓣ",
        )
            .map { "123$it" }
        for (number in numbers) {
            for (offset in 400..510) {
                val input = "a".repeat(offset) + number + "/after"
                val chunks = TextSplitter(input).asSequence().toList()
                assertEquals(input, input, chunks.joinToString(""))
                assertTrue("$number at $offset: $chunks", chunks.any { number in it })
            }
        }
    }

    @Test
    fun forcedCutFinishesOnlyOneNumber() {
        val number = "-" + "9".repeat(600) + "th"
        val prefix = "a".repeat(499)
        val input = prefix + number + "/" + "hello/".repeat(500)
        val chunks = TextSplitter(input).asSequence().toList()
        assertEquals(prefix + number, chunks.first())
        assertEquals(input, chunks.joinToString(""))
        assertTrue(chunks.drop(1).all { it.length <= 500 })
    }

    @Test
    fun forcedCutKeepsFollowingWhitespace() {
        for (word in listOf("9".repeat(144), "9".repeat(144) + "th", "a".repeat(500))) {
            assertEquals(
                listOf("$word \t", "after"),
                TextSplitter("$word \tafter").asSequence().toList(),
            )
        }
    }
}
