package app.grapheneos.speechservices.tts

import androidx.collection.IntIntMap
import androidx.collection.IntList
import androidx.collection.MutableIntList
import androidx.collection.buildIntIntMap
import androidx.collection.buildIntList

object Symbols {
    private const val PAD = '_'
    private const val PUNCTUATION = ";:,.!?¡¿—…\"«»“” "
    private const val LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private const val LETTERS_IPA =
        "ɑɐɒæɓʙβɔɕçɗɖðʤəɘɚɛɜɝɞɟʄɡɠɢʛɦɧħɥʜɨɪʝɭɬɫɮʟɱɯɰŋɳɲɴøɵɸθœɶʘɹɺɾɻʀʁɽʂʃʈʧʉʊʋⱱʌɣɤʍχʎʏʑʐʒʔʡʕʢǀǁǂǃˈˌːˑʼʴʰʱʲʷˠˤ˞↓↑→↗↘'̩'ᵻ"
    private const val LETTERS_IPA_NONSTANDARD = "ᵊ"

    private fun MutableIntList.add(ch: Char) = add(ch.code)
    private fun MutableIntList.addAll(chars: String) = chars.forEach { add(it.code) }

    val index: IntIntMap = buildIntIntMap {
        val symbols: IntList = buildIntList {
            add(PAD)
            addAll(PUNCTUATION)
            addAll(LETTERS)
            addAll(LETTERS_IPA)
            addAll(LETTERS_IPA_NONSTANDARD)
        }

        symbols.forEachIndexed { idx, value ->
            put(value, idx)
        }
    }

    val PAD_ID = index[PAD.code].toLong()
}
