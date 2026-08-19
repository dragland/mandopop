package com.mandopop.briefing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BriefingVerifierTest {

    private val dictionary = setOf("今天", "下午", "会议", "一个", "新闻", "消息", "朋友")
    private val known = setOf("你", "今天", "下午", "有", "一", "个", "一个", "朋友", "消息", "的", "新", "了")

    private fun verify(sentence: String, allowed: Set<String> = known) =
        BriefingVerifier.verify(
            sentence,
            isWord = { it in dictionary || it in allowed },
            isAllowed = { it in allowed },
        )

    @Test
    fun acceptsSentenceOfKnownWords() {
        assertEquals(BriefingVerifier.Verdict.Pass, verify("你今天下午有一个消息。"))
    }

    @Test
    fun rejectsUnknownWordAndNamesItsParts() {
        // 会议 is unknown as a unit and decomposes to 会 and 议, also unknown — the failure
        // names the irreducible characters, which is what the retry's avoid-list carries.
        val verdict = verify("你今天有一个会议。")
        assertTrue(verdict is BriefingVerifier.Verdict.Fail)
        val unknown = (verdict as BriefingVerifier.Verdict.Fail).unknownWords
        assertTrue("会" in unknown && "议" in unknown)
    }

    @Test
    fun frontierWordIsTheOneAllowedException() {
        val verdict = verify("你今天有一个会议。", allowed = known + "会议")
        assertEquals(BriefingVerifier.Verdict.Pass, verdict)
    }

    @Test
    fun rejectsLatinLeakage() {
        val verdict = verify("你今天有一个 meeting。")
        assertTrue((verdict as BriefingVerifier.Verdict.Fail).reason.contains("Latin"))
    }

    @Test
    fun rejectsEmptyAndPureEnglish() {
        assertTrue(verify("") is BriefingVerifier.Verdict.Fail)
        assertTrue(verify("!?…") is BriefingVerifier.Verdict.Fail)
    }

    @Test
    fun rejectsOverlongOutput() {
        val verdict = verify("你今天下午有一个消息。".repeat(5))
        assertTrue((verdict as BriefingVerifier.Verdict.Fail).reason.contains("long"))
    }

    @Test
    fun unknownSingleCharactersAreNamedIndividually() {
        // 猫 is a dictionary word but not known; it must surface in the rejection so the
        // model retry can carry a "do not use" list.
        val verdict = verify("你今天有猫。")
        assertEquals(listOf("猫"), (verdict as BriefingVerifier.Verdict.Fail).unknownWords)
    }

    @Test
    fun chinesePunctuationAndDigitsAreNotWords() {
        assertEquals(BriefingVerifier.Verdict.Pass, verify("你今天有3个消息！"))
    }

    @Test
    fun inventedCompoundDecomposesIntoKnownCharacters() {
        // 有事 is a real CC-CEDICT compound the user hasn't learned as a unit — but 有 and 事
        // are both known, so the sentence reads fine under the decomposed segmentation.
        val verdict = BriefingVerifier.verify(
            "你今天有事。",
            isWord = { it in setOf("今天", "有事") || it in setOf("你", "今天", "有", "事") },
            isAllowed = { it in setOf("你", "今天", "有", "事") },
        )
        assertEquals(BriefingVerifier.Verdict.Pass, verdict)
    }

    @Test
    fun compoundWithAGenuinelyUnknownCharacterStillFails() {
        // 安排 decomposes to 安 and 排, neither known — decomposition must not launder it.
        val verdict = BriefingVerifier.verify(
            "你今天有安排。",
            isWord = { it in setOf("今天", "安排") || it in setOf("你", "今天", "有") },
            isAllowed = { it in setOf("你", "今天", "有") },
        )
        assertTrue(verdict is BriefingVerifier.Verdict.Fail)
        val unknown = (verdict as BriefingVerifier.Verdict.Fail).unknownWords
        assertTrue("安" in unknown && "排" in unknown)
    }
}
