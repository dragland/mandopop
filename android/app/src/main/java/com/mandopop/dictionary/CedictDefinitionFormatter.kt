package com.mandopop.dictionary

object CedictDefinitionFormatter {
    private val referencePattern = Regex(
        """([^\s,;:()\[\]/|]*[\p{script=Han}][^\s,;:()\[\]/|]*)\|([^\s,;:()\[\]/|]*[\p{script=Han}][^\s,;:()\[\]/|]*)(\[[^\]]+\])?""",
    )

    fun formatList(definitions: List<String>, limit: Int = 2): String =
        definitions.take(limit).joinToString("; ", transform = ::format)

    fun format(definition: String): String =
        referencePattern.replace(definition) { match ->
            "${match.groupValues[2]}${match.groupValues[3]}"
        }
}
