package com.mandopop.dictionary

import org.junit.Assert.assertEquals
import org.junit.Test

class CedictDefinitionFormatterTest {
    @Test
    fun matchesSharedDefinitionFormattingFixtures() {
        for (case in SharedFixtures.definitionFormattingCases()) {
            assertEquals(case.name, case.expected, CedictDefinitionFormatter.format(case.input))
        }
    }

    @Test
    fun keepsSimplifiedSideOfBracketedReferences() {
        assertEquals("see also 鲽[die2]", CedictDefinitionFormatter.format("see also 鰈|鲽[die2]"))
    }

    @Test
    fun formatsMultipleClassifierReferences() {
        assertEquals(
            "CL:个[ge4],块[kuai4]",
            CedictDefinitionFormatter.format("CL:個|个[ge4],塊|块[kuai4]"),
        )
    }

    @Test
    fun keepsSimplifiedSideOfUnbracketedReferences() {
        assertEquals(
            "one of the Six Methods 六书",
            CedictDefinitionFormatter.format("one of the Six Methods 六書|六书"),
        )
    }

    @Test
    fun handlesMixedSymbolReferenceTokens() {
        assertEquals("95后[jiu3 wu3 hou4]", CedictDefinitionFormatter.format("95後|95后[jiu3 wu3 hou4]"))
        assertEquals(
            "B型超声[B xing2 chao1 sheng1]",
            CedictDefinitionFormatter.format("B型超聲|B型超声[B xing2 chao1 sheng1]"),
        )
        assertEquals("γ射线[gamma she4 xian4]", CedictDefinitionFormatter.format("γ射線|γ射线[gamma she4 xian4]"))
        assertEquals(
            "∼的大门[xx5 de5 da4 men2]",
            CedictDefinitionFormatter.format("∼的大門|∼的大门[xx5 de5 da4 men2]"),
        )
    }

    @Test
    fun preservesDefinitionLimit() {
        assertEquals("一; 二", CedictDefinitionFormatter.formatList(listOf("一", "二", "三")))
    }
}
