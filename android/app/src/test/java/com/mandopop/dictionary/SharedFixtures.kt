package com.mandopop.dictionary

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object SharedFixtures {
    data class NormalizationCase(
        val name: String,
        val input: String,
        val expected: List<String>?,
    )

    data class DefinitionFormattingCase(
        val name: String,
        val input: String,
        val expected: String,
    )

    fun normalizationCases(): List<NormalizationCase> =
        readRows("normalization_cases.tsv", 3).map { row ->
            NormalizationCase(
                name = row[0],
                input = row[1],
                expected = row[2].takeUnless { it == "<null>" }?.split("|"),
            )
        }

    fun definitionFormattingCases(): List<DefinitionFormattingCase> =
        readRows("definition_formatting_cases.tsv", 3).map { row ->
            DefinitionFormattingCase(
                name = row[0],
                input = row[1],
                expected = row[2],
            )
        }

    private fun readRows(fileName: String, columns: Int): List<List<String>> {
        return Files.readAllLines(fixturePath(fileName))
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val row = line.split("\t", limit = columns)
                check(row.size == columns) { "Invalid fixture row in $fileName: $line" }
                row
            }
    }

    private fun fixturePath(fileName: String): Path {
        var current: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (current != null) {
            val candidate = current.resolve("testdata").resolve(fileName)
            if (Files.exists(candidate)) return candidate
            current = current.parent
        }
        error("Unable to find testdata/$fileName from ${System.getProperty("user.dir")}")
    }
}
