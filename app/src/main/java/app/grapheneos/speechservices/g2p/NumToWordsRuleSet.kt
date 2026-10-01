package app.grapheneos.speechservices.g2p

enum class NumToWordsRuleSet(val value: String) {
    Ordinal("%spellout-ordinal"),
    Cardinal("%spellout-cardinal"),
    Numbering("%spellout-numbering"),
    NumberingYear("%spellout-numbering-year"),
}
