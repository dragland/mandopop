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
    fun rejectsUnknownWordAndNamesIt() {
        val verdict = verify("你今天有一个会议。")
        assertTrue(verdict is BriefingVerifier.Verdict.Fail)
        assertEquals(listOf("会议"), (verdict as BriefingVerifier.Verdict.Fail).unknownWords)
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
}
