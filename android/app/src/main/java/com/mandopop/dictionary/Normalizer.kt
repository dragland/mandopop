package com.mandopop.dictionary

object Normalizer {
    const val MAX_SELECTION_LENGTH = 100

    fun normalizeWord(word: String): List<String>? {
        val cleaned = word.lowercase().trim()
        if (cleaned.isEmpty() || cleaned.length > MAX_SELECTION_LENGTH) return null

        if (cleaned.contains(" ")) {
            val words = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.size !in 2..3) return null

            val perWord = words.map { normalizeSingleWord(it) }
            return cartesian(perWord).take(20)
        }

        return normalizeSingleWord(cleaned)
    }

    private fun normalizeSingleWord(cleaned: String): List<String> {
        val variations = mutableListOf(cleaned)
        val withoutPunct = cleaned.replace(Regex("""[.,!?;:'"]+$"""), "")
        if (withoutPunct != cleaned) variations += withoutPunct

        val morphologyInputs = listOf(cleaned, withoutPunct).distinct()
        for (input in morphologyInputs) {
            addMorphologyVariations(input, variations)
        }

        return variations.distinct()
    }

    private fun addMorphologyVariations(cleaned: String, variations: MutableList<String>) {
        if (cleaned.endsWith("ies") && cleaned.length > 4) {
            variations += cleaned.dropLast(3) + "y"
        }

        if (cleaned.endsWith("s") && cleaned.length > 2) {
            variations += cleaned.dropLast(1)
            if (cleaned.endsWith("es") && cleaned.length > 3) {
                variations += cleaned.dropLast(2)
            }
            if (cleaned.endsWith("ses") || cleaned.endsWith("zes")) {
                variations += cleaned.dropLast(2)
            }
        }

        if (cleaned.endsWith("ing") && cleaned.length > 4) {
            val base = cleaned.dropLast(3)
            variations += base
            variations += base + "e"
            if (base.hasDoubledLastChar()) variations += base.dropLast(1)
        }

        if (cleaned.endsWith("ed") && cleaned.length > 3) {
            val base = cleaned.dropLast(2)
            variations += base
            variations += cleaned.dropLast(1)
            if (base.hasDoubledLastChar()) variations += base.dropLast(1)
        }

        if (cleaned.endsWith("er") && cleaned.length > 3) {
            val base = cleaned.dropLast(2)
            variations += base
            variations += cleaned.dropLast(1)
            if (base.hasDoubledLastChar()) variations += base.dropLast(1)
        }

        if (cleaned.endsWith("est") && cleaned.length > 4) {
            variations += cleaned.dropLast(3)
            variations += cleaned.dropLast(2)
        }

        if (cleaned.endsWith("ly") && cleaned.length > 3) {
            variations += cleaned.dropLast(2)
        }
    }

    private fun cartesian(arrays: List<List<String>>): List<String> {
        var results = arrays.first().toList()
        for (index in 1 until arrays.size) {
            val next = mutableListOf<String>()
            for (previous in results) {
                for (value in arrays[index]) {
                    next += "$previous $value"
                }
            }
            results = next
        }
        return results
    }

    private fun String.hasDoubledLastChar(): Boolean {
        return length > 1 && this[length - 1] == this[length - 2]
    }
}
