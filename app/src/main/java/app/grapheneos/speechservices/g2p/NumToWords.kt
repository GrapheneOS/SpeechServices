package app.grapheneos.speechservices.g2p

import com.ibm.icu.text.RuleBasedNumberFormat
import com.ibm.icu.util.ULocale
import java.util.Locale

fun numToWords(
    num: Long,
    ruleSet: NumToWordsRuleSet = NumToWordsRuleSet.Cardinal,
    locale: Locale,
): String {
    val numberFormat =
        RuleBasedNumberFormat(ULocale.forLocale(locale), RuleBasedNumberFormat.SPELLOUT)
    return numberFormat.format(num, ruleSet.value)
}
