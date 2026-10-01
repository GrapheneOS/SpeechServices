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
