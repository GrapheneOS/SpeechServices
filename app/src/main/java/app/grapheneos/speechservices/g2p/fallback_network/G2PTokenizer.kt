package app.grapheneos.speechservices.g2p.fallback_network

import androidx.collection.IntIntMap
import androidx.collection.buildIntIntMap

data class G2PTokenizerConfig(val graphemeChars: String, val phonemeChars: String)

private const val MAX_SPECIAL_ID = 3 // see 'unk' below

class G2PTokenizer(config: G2PTokenizerConfig) {
    private val pad = Pair(0, "<pad>")
    private val bos = Pair(1, "<s>")
    private val eos = Pair(2, "</s>")
    private val unk = Pair(3, "<unk>")

    private val special = listOf(pad, bos, eos, unk)

    private val graphemeList =
        special.map { it.second } + config.graphemeChars.trimStart('_').map { it.toString() }
    private val phonemeList =
        special.map { it.second } + config.phonemeChars.trimStart('_').map { it.toString() }

    private val tokenToId: IntIntMap = buildIntIntMap {
        graphemeList.forEachIndexed { index, token ->
            if (token.length == 1) {
                put(token.first().code, index)
            }
        }
        phonemeList.forEachIndexed { index, token ->
            if (token.length == 1) {
                put(token.first().code, index)
            }
        }
    }

    /**
     * Encode a single word into grapheme token IDs: [bos] + characters + [eos]
     *
     * Encodes unknown characters as [unk].
     */
    fun encodeWord(word: String): LongArray {
        val ids = LongArray(word.length + 2)
        val unkId = unk.first
        var index = 0
        ids[index++] = bos.first.toLong()
        for (char in word) {
            ids[index++] = tokenToId.getOrDefault(char.code, unkId).toLong()
        }
        ids[index++] = eos.first.toLong()
        check(index == ids.size)
        return ids
    }

    /**
     * Decode an array of symbol IDs (Long) to a string of phonemes and punctuation.
     *
     * Throws [IllegalArgumentException] on unknown IDs.
     */
    fun decodePhonemes(ids: LongArray): String {
        val stringBuilder = StringBuilder()
        for (idLong in ids) {
            val id = idLong.toInt()
            // skip special tokens
            if (id <= MAX_SPECIAL_ID) {
                continue
            }
            if (id >= phonemeList.size) {
                error("ID $id not found in idToPhoneme!")
            } else {
                stringBuilder.append(phonemeList[id])
            }
        }
        return stringBuilder.toString()
    }
}
