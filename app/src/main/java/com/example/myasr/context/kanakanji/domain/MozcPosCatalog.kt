package com.example.myasr.context.kanakanji.domain

/**
 * Centralized catalog of Mozc OSS POS (Part-of-Speech) IDs and O(1) bitmask categories.
 * Replaces scattered hardcoded POS ID ranges and anchor constants across KanaKanjiEngine and dictionary loaders.
 */
object MozcPosCatalog {
    const val POS_SIZE = 2672

    // Named anchor POS IDs (Mozc id.def)
    const val POS_AUX_DESU = 172
    const val POS_AUX_MASU = 240
    const val POS_ARCHAIC_NO = 385
    const val POS_SAHEN_SURU_MASU = 1825
    const val POS_NOUN_SAHEN_BASE = 1841
    const val POS_NOUN_GENERAL = 1851
    const val POS_SUFFIX_CHUU = 2003
    const val POS_SYMBOL = 2632

    private const val FLAG_CONTENT_WORD = 1 shl 0
    private const val FLAG_SUFFIX = 1 shl 1
    private const val FLAG_FUNCTIONAL = 1 shl 2
    private const val FLAG_AUXILIARY_VERB = 1 shl 3
    private const val FLAG_PREDICATE = 1 shl 4
    private const val FLAG_PROPER_NOUN = 1 shl 5
    private const val FLAG_SAHEN_NOUN = 1 shl 6
    private const val FLAG_NOUN_RANGE = 1 shl 7

    private val posFlags = IntArray(POS_SIZE).apply {
        for (id in 0 until POS_SIZE) {
            var mask = 0
            val isSuf = (id == 2032 || id == 1941 || id == 1951 || id == 1975 || id in 1941..2034)
            if (isSuf) mask = mask or FLAG_SUFFIX
            if (id in 29..433) mask = mask or FLAG_FUNCTIONAL
            if (id in 29..267) mask = mask or FLAG_AUXILIARY_VERB
            if (id in 434..1840 || id in 2196..2590) mask = mask or FLAG_PREDICATE
            if (id in 1917..1940) mask = mask or FLAG_PROPER_NOUN
            if (id == 1841 || id in 2035..2150) mask = mask or FLAG_SAHEN_NOUN
            if (id in 1841..2195) mask = mask or FLAG_NOUN_RANGE
            if (!isSuf && (id in 1841..2195 || id in 434..1840 || id in 2196..2590 || id in 12..28 || id in 2659..2671)) {
                mask = mask or FLAG_CONTENT_WORD
            }
            this[id] = mask
        }
    }

    private fun hasFlag(id: Int, flag: Int): Boolean {
        return id in 0 until POS_SIZE && (posFlags[id] and flag) != 0
    }

    fun isContentWord(id: Int): Boolean = hasFlag(id, FLAG_CONTENT_WORD)
    fun isSuffix(id: Int): Boolean = hasFlag(id, FLAG_SUFFIX)
    fun isFunctionalWord(id: Int): Boolean = hasFlag(id, FLAG_FUNCTIONAL)
    fun isAuxiliaryVerb(id: Int): Boolean = hasFlag(id, FLAG_AUXILIARY_VERB)
    fun isPredicatePos(id: Int): Boolean = hasFlag(id, FLAG_PREDICATE)
    fun isProperNoun(id: Int): Boolean = hasFlag(id, FLAG_PROPER_NOUN)
    fun isSahenNoun(id: Int): Boolean = hasFlag(id, FLAG_SAHEN_NOUN)
    fun isInNounRange(id: Int): Boolean = hasFlag(id, FLAG_NOUN_RANGE)
}
