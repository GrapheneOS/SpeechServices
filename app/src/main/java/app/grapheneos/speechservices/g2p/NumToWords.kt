package app.grapheneos.speechservices.g2p

import com.ibm.icu.text.RuleBasedNumberFormat
import com.ibm.icu.util.ULocale
import java.util.Locale

class NumToWords(locale: Locale) {
    private val numberFormat =
        RuleBasedNumberFormat(ULocale.forLocale(locale), RuleBasedNumberFormat.SPELLOUT)

    fun format(num: Long, ruleSet: NumToWordsRuleSet = NumToWordsRuleSet.Cardinal): String {
        return numberFormat.format(num, ruleSet.value)
    }
}
