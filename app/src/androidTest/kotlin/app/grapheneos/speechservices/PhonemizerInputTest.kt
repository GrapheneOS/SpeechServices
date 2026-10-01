package app.grapheneos.speechservices

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.grapheneos.speechservices.g2p.DictionaryValue
import app.grapheneos.speechservices.g2p.EnglishPhonemizer
import app.grapheneos.speechservices.g2p.EnglishPhonemizer.FeatureValue
import app.grapheneos.speechservices.g2p.Lexicon
import app.grapheneos.speechservices.g2p.TokenContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import opennlp.tools.postag.POSModel
import opennlp.tools.postag.POSTaggerME
import opennlp.tools.tokenize.TokenizerME
import opennlp.tools.tokenize.TokenizerModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhonemizerInputTest {
    @Test
    fun ordinaryCardinalNumbersKeepTheirMeaning() {
        val cases = mapOf(
            "0" to "zero",
            "1" to "one",
            "-42" to "minus forty two",
            "101" to "one hundred one",
            "1,234" to "one thousand two hundred thirty four",
            "12345" to "twelve thousand three hundred forty five",
            "00001" to "one",
            "999999999999999999" to
                "nine hundred ninety nine quadrillion nine hundred ninety nine trillion " +
                "nine hundred ninety nine billion nine hundred ninety nine million " +
                "nine hundred ninety nine thousand nine hundred ninety nine",
        )
        for ((number, words) in cases) {
            assertNumber(number, words)
        }
    }

    @Test
    fun largeCardinalNumbersKeepEveryDigit() {
        for (number in listOf(
            "1000000000000000000",
            "9223372036854775807",
            "9223372036854775808",
            "123456789012345678901234567890",
            "9".repeat(125),
            "9".repeat(400),
        )) {
            val words = number.map { DIGITS[it.digitToInt()] }.joinToString(" ")
            assertNumber(number, words)
            assertNumber("-$number", "minus $words")
            assertNumber("00$number", "zero zero $words")
            assertNumber(number.reversed().chunked(3).joinToString(",").reversed(), words)
        }
    }

    @Test
    fun otherNumberFormatsKeepTheirMeaning() {
        val cases = mapOf(
            "1999" to "nineteen ninety nine",
            "2026" to "twenty twenty six",
            "21st" to "twenty first",
            "1,001st" to "one thousand first",
            "1.50" to "one point five",
            ".05" to "point zero five",
            "12.05" to "twelve point zero five",
            "1.2.3" to "one two three",
        )
        for ((number, words) in cases) {
            assertNumber(number, words)
        }
        assertNumber("123", "one twenty three", isHead = false)
        assertNumber("12345", "one two three four five", isHead = false)
    }

    @Test
    fun fullPhonemizerPreservesLargeCardinalDigits() {
        for (number in listOf("1000000000000000000", "9223372036854775808")) {
            val words = number.map { DIGITS[it.digitToInt()] }.joinToString(" ")
            val cases = mapOf(
                number to words,
                "-$number" to "minus $words",
                number.reversed().chunked(3).joinToString(",").reversed() to words,
                "The value is $number." to "The value is $words.",
            )
            for ((input, expected) in cases) {
                assertEquals(
                    input,
                    phonemizer.main(expected, {}).first,
                    phonemizer.main(input, {}).first,
                )
            }
        }
    }

    @Test
    fun decimalNumbersKeepEverySignificantDigit() {
        val fraction = "1234567890123456789"
        val fractionWords = fraction.map { DIGITS[it.digitToInt()] }.joinToString(" ")
        assertNumber("1.$fraction", "one point $fractionWords")
        assertNumber(
            "9007199254740993.01",
            "nine quadrillion seven trillion one hundred ninety nine billion " +
                "two hundred fifty four million seven hundred forty thousand " +
                "nine hundred ninety three point zero one",
        )
        assertNumber(
            "0." + "0".repeat(324) + "1",
            "zero point " + "zero ".repeat(324) + "one",
        )
        for (whole in listOf("1000000000000000000", "9223372036854775808", "9".repeat(400))) {
            val words = whole.map { DIGITS[it.digitToInt()] }.joinToString(" ")
            assertNumber("$whole.$fraction", "$words point $fractionWords")
            assertNumber("-$whole.01", "minus $words point zero one")
            assertNumber("$whole.0", words)
            assertNumber(
                whole.reversed().chunked(3).joinToString(",").reversed() + ".5",
                "$words point five",
            )
        }
        val powerOfTen = "1000000000000000000"
        val digits = "one " + "zero ".repeat(18)
        assertNumber("$powerOfTen.0", digits + "zero", isHead = false)
        assertNumber("$powerOfTen.1.2", digits + "one two")
    }

    @Test
    fun decimalZerosKeepTheirMeaning() {
        val cases = mapOf(
            "1.50" to "one point five",
            "1.00" to "one",
            "1." to "one",
            "0.00" to "zero",
            "-0.000" to "minus zero",
            "0001.0500" to "one point zero five",
            ".050" to "point zero five zero",
            ".000" to "point zero zero zero",
        )
        for ((number, words) in cases) {
            assertNumber(number, words)
        }
    }

    @Test
    fun decimalPronunciationFlagsKeepTheirMeaning() {
        val fraction = lexicon.getNumber(".5", null, true, "").first
        for (whole in listOf("1", "01", "0001")) {
            assertEquals(
                "$whole.5",
                "ə $fraction",
                lexicon.getNumber("$whole.5", null, true, "a").first,
            )
        }
        assertEquals(
            lexicon.getNumber("1", null, true, "a"),
            lexicon.getNumber("1.0", null, true, "a"),
        )
    }

    @Test
    fun fullPhonemizerPreservesDecimalDigits() {
        val point = requireNotNull(lexicon.getWord("point", "NUM", null, TokenContext()).first)
        val decimalPoint = requireNotNull(
            lexicon.getWord("point", "NUM", -2.0, TokenContext()).first,
        )
        fun expectedPhonemes(text: String): String {
            // Decimal points are unstressed, unlike the standalone word "point".
            return phonemizer.main(text, {}).first.replace(point, decimalPoint)
        }
        assertEquals(expectedPhonemes("one point five"), phonemizer.main("1.5", {}).first)
        for (whole in listOf("1000000000000000000", "9223372036854775808")) {
            val words = whole.map { DIGITS[it.digitToInt()] }.joinToString(" ")
            val cases = mapOf(
                "$whole.01" to "$words point zero one",
                "-$whole.01" to "minus $words point zero one",
                "The value is $whole.01." to "The value is $words point zero one.",
            )
            for ((input, expected) in cases) {
                assertEquals(
                    input,
                    expectedPhonemes(expected),
                    phonemizer.main(input, {}).first,
                )
            }
        }
        val fraction = "1234567890123456789"
        val words = fraction.map { DIGITS[it.digitToInt()] }.joinToString(" ")
        assertEquals(
            expectedPhonemes("one point $words"),
            phonemizer.main("1.$fraction", {}).first,
        )
    }

    @Test
    fun ordinaryOrdinalsKeepTheirMeaning() {
        val cases = mapOf(
            "0th" to "zeroth",
            "1st" to "first",
            "2nd" to "second",
            "3rd" to "third",
            "4th" to "fourth",
            "11th" to "eleventh",
            "12th" to "twelfth",
            "13th" to "thirteenth",
            "21st" to "twenty first",
            "100th" to "one hundredth",
            "1,001st" to "one thousand first",
            "00001st" to "first",
            "999999999999999999th" to
                "nine hundred ninety nine quadrillion nine hundred ninety nine trillion " +
                "nine hundred ninety nine billion nine hundred ninety nine million " +
                "nine hundred ninety nine thousand nine hundred ninety ninth",
        )
        for ((number, words) in cases) {
            assertNumber(number, words)
        }
    }

    @Test
    fun largeOrdinalsKeepDigitsAndSuffix() {
        val cases = mapOf(
            "1000000000000000000" to "th",
            "1000000000000000001" to "st",
            "1000000000000000002" to "nd",
            "1000000000000000003" to "rd",
            "1000000000000000011" to "th",
            "1000000000000000012" to "th",
            "1000000000000000013" to "th",
            "9223372036854775807" to "th",
            "9223372036854775808" to "th",
            "9".repeat(125) to "th",
            "9".repeat(400) to "th",
        )
        for ((number, suffix) in cases) {
            val digits = number.map { DIGITS[it.digitToInt()] }.joinToString(" ")
            val letters = suffix.map { it.uppercaseChar() }.joinToString(" ")
            val words = "$digits $letters"
            assertNumber(number + suffix, words)
            assertNumber("-$number$suffix", "minus $words")
            assertNumber("00$number$suffix", "zero zero $words")
            assertNumber(number + suffix, words, isHead = false)
            assertNumber(
                number.reversed().chunked(3).joinToString(",").reversed() + suffix,
                words,
            )
        }
    }

    @Test
    fun groupedOrdinalsKeepTheSameMeaning() {
        for (number in listOf("1001", "1000000000000000001")) {
            val grouped = number.reversed().chunked(3).joinToString(",").reversed()
            for (head in listOf(true, false, null)) {
                for (currency in listOf(null, "$", "£", "€")) {
                    assertEquals(
                        "$grouped, head=$head, currency=$currency",
                        lexicon.getNumber(number + "st", currency, head, ""),
                        lexicon.getNumber(grouped + "st", currency, head, ""),
                    )
                }
            }
        }
    }

    @Test
    fun fullPhonemizerPreservesLargeOrdinalDigitsAndSuffix() {
        val ordinals = mapOf("1000000000000000001" to "st", "9223372036854775808" to "th")
        for ((number, suffix) in ordinals) {
            val digits = number.map { DIGITS[it.digitToInt()] }.joinToString(" ")
            val letters = suffix.map { it.uppercaseChar() }.joinToString(" ")
            val words = "$digits $letters"
            val cases = mapOf(
                number + suffix to words,
                "-$number$suffix" to "minus $words",
                number.reversed().chunked(3).joinToString(",").reversed() + suffix to words,
                "This is the $number$suffix entry." to "This is the $words entry.",
            )
            for ((input, expected) in cases) {
                assertEquals(
                    input,
                    phonemizer.main(expected, {}).first,
                    phonemizer.main(input, {}).first,
                )
            }
        }
    }

    @Test
    fun ordinaryCurrencyAmountsKeepTheirMeaning() {
        val cases = mapOf(
            "0" to "zero dollars",
            "1" to "one dollar",
            "2" to "two dollars",
            "-1" to "minus one dollar",
            "-2.01" to "minus two dollars and one cent",
            ".01" to "one cent",
            "0.01" to "one cent",
            "0.00" to "zero dollars",
            "0.10" to "ten cents",
            "1.01" to "one dollar and one cent",
            "1.00" to "one dollar",
            "0001.01" to "one dollar and one cent",
            "3,000,000,000.00" to "three billion dollars",
            "1." + "0".repeat(40) to "one dollar",
            "0".repeat(40) + "1" to "one dollar",
        )
        for ((amount, words) in cases) {
            assertCurrency(amount, "$", words)
        }
        assertCurrency("1.02", "£", "one pound and two pence")
        assertCurrency("2.02", "€", "two euros and two cents")
    }

    @Test
    fun singleFractionalDigitsRepresentTenthsOfTheCurrencyUnit() {
        assertCurrency("1.5", "$", "one dollar and fifty cents")
        assertCurrency("-1.5", "$", "minus one dollar and fifty cents")
        assertCurrency("0.1", "£", "ten pence")
        assertCurrency(".9", "€", "ninety cents")
        for (currency in listOf("$", "£", "€")) {
            for (whole in listOf("0", "1", "-1", "12", "-12", "9223372036854775808")) {
                for (digit in '0'..'9') {
                    assertEquals(
                        "$currency$whole.$digit",
                        lexicon.getNumber("$whole.${digit}0", currency, true, ""),
                        lexicon.getNumber("$whole.$digit", currency, true, ""),
                    )
                }
            }
        }
    }

    @Test
    fun fullPhonemizerScalesSingleFractionalDigits() {
        for (currency in listOf("$", "£", "€")) {
            for (amount in listOf("1.5", "-1.5", "0.1", ".9")) {
                assertEquals(
                    "$currency$amount",
                    phonemizer.main("$currency${amount}0", {}).first,
                    phonemizer.main("$currency$amount", {}).first,
                )
            }
        }
    }

    @Test
    fun lastSpellableCurrencyAmountIsStillSpelledNormally() {
        assertCurrency(
            "999999999999999999",
            "$",
            "nine hundred ninety nine quadrillion nine hundred ninety nine trillion " +
                "nine hundred ninety nine billion nine hundred ninety nine million " +
                "nine hundred ninety nine thousand nine hundred ninety nine dollars",
        )
    }

    @Test
    fun amountsBeyondSpelloutOrLongRangeKeepEveryDigit() {
        val amounts = listOf(
            "1000000000000000000",
            "9223372036854775807",
            "9223372036854775808",
            "123456789012345678901234567890",
            "9".repeat(125),
            "9".repeat(400),
        )
        for ((currency, unit) in mapOf("$" to "dollars", "£" to "pounds", "€" to "euros")) {
            for (amount in amounts) {
                val words = amount.map { DIGITS[it.digitToInt()] }.joinToString(" ")
                assertCurrency(amount, currency, "$words $unit")
                assertCurrency("-$amount", currency, "minus $words $unit")
            }
        }
    }

    @Test
    fun overflowingWholeAmountDoesNotDisappearWhenThereAreCents() {
        val amount = "123456789012345678901234567890"
        val words = amount.map { DIGITS[it.digitToInt()] }.joinToString(" ")
        assertCurrency("$amount.01", "$", "$words dollars and one cent")
        assertCurrency("$amount.99", "$", "$words dollars and ninety nine cents")
        assertCurrency("$amount.00", "$", "$words dollars")
    }

    @Test
    fun fullPhonemizerPreservesLargeCurrencyDigits() {
        for (amount in listOf("1000000000000000000", "9223372036854775808")) {
            val words = amount.map { DIGITS[it.digitToInt()] }.joinToString(" ")
            val actual = phonemizer.main("$$amount", {}).first
            val expected = phonemizer.main("$words dollars", {}).first
            assertEquals(amount, expected, actual)
        }
    }

    @Test
    fun linksDoNotIntroduceEmptyTokensOrShiftFeatures() {
        assertEquals(
            Triple("hello", listOf("hello"), mapOf(0 to FeatureValue.IntValue(1))),
            phonemizer.preprocess("[hello](1)"),
        )
        assertEquals(
            Triple(
                "one two ",
                listOf("one", "two"),
                mapOf(0 to FeatureValue.IntValue(1), 1 to FeatureValue.DoubleValue(-0.5)),
            ),
            phonemizer.preprocess("  [one](1) [two](-0.5) "),
        )
        assertEquals(
            Triple(
                "onetwo",
                listOf("one", "two"),
                mapOf(0 to FeatureValue.IntValue(1), 1 to FeatureValue.IntValue(2)),
            ),
            phonemizer.preprocess("[one](1)[two](2)"),
        )
    }

    @Test
    fun invalidAndOverflowingLinkFeaturesAreIgnored() {
        for (feature in listOf("", "+", "-", "2147483648", "-2147483649", "word", "0.50")) {
            val result = phonemizer.preprocess("[hello]($feature)")
            assertEquals(feature, "hello", result.first)
            assertEquals(feature, listOf("hello"), result.second)
            assertTrue(feature, result.third.isEmpty())
        }
    }

    @Test
    fun validLinkFeaturesArePreserved() {
        val cases = mapOf(
            "2147483647" to FeatureValue.IntValue(Int.MAX_VALUE),
            "-2147483648" to FeatureValue.IntValue(Int.MIN_VALUE),
            "+1" to FeatureValue.IntValue(1),
            "0.5" to FeatureValue.DoubleValue(0.5),
            "+0.5" to FeatureValue.DoubleValue(0.5),
            "-0.5" to FeatureValue.DoubleValue(-0.5),
            "/hello///" to FeatureValue.StringValue("/hello"),
            "#hello##" to FeatureValue.StringValue("#hello"),
        )
        for ((feature, expected) in cases) {
            assertEquals(
                feature,
                mapOf(0 to expected),
                phonemizer.preprocess("[hello]($feature)").third,
            )
        }
    }

    private fun assertNumber(number: String, words: String, isHead: Boolean = true) {
        val expected = words.split(' ').joinToString(" ") { word ->
            val stress = if (word == "point") -2.0 else null
            requireNotNull(lexicon.getWord(word, "NUM", stress, TokenContext()).first) {
                "No pronunciation for $word"
            }
        }
        assertEquals(number, expected, lexicon.getNumber(number, null, isHead, "").first)
    }

    private fun assertCurrency(amount: String, currency: String, words: String) {
        val expected = words.split(' ').joinToString(" ") { word ->
            requireNotNull(lexicon.getWord(word, "NUM", null, TokenContext()).first) {
                "No pronunciation for $word"
            }
        }
        assertEquals(
            "$currency$amount",
            expected,
            lexicon.getNumber(amount, currency, true, "").first,
        )
    }

    companion object {
        private val DIGITS = listOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        )

        private val resources
            get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

        private val lexicon by lazy {
            val dictionary = resources.openRawResource(R.raw.us_gold).buffered().use { stream ->
                @OptIn(ExperimentalSerializationApi::class)
                Json.decodeFromStream<Map<String, DictionaryValue>>(stream)
            }
            Lexicon(false, dictionary)
        }

        private val phonemizer by lazy {
            val tokenizer = resources.openRawResource(
                R.raw.opennlp_en_ud_ewt_tokens__1_3__2_5_4,
            ).buffered().use { TokenizerME(TokenizerModel(it)) }
            val tagger = resources.openRawResource(
                R.raw.opennlp_en_ud_ewt_pos__1_3__2_5_4,
            ).buffered().use { POSTaggerME(POSModel(it)) }
            EnglishPhonemizer(lexicon, "unknown", tokenizer, tagger)
        }
    }
}
