package com.mandopop.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NormalizerTest {
    @Test
    fun basicBehavior() {
        assertEquals("cat", Normalizer.normalizeWord("CAT")?.first())
        assertEquals("cat", Normalizer.normalizeWord("  cat  ")?.first())
        assertNull(Normalizer.normalizeWord(""))
        assertNull(Normalizer.normalizeWord("a".repeat(101)))
    }

    @Test
    fun removesTrailingPunctuation() {
        assertContains("cat", Normalizer.normalizeWord("cat."))
        assertContains("cat", Normalizer.normalizeWord("cat..."))
        assertContains("cat", Normalizer.normalizeWord("cat,"))
    }

    @Test
    fun handlesPluralForms() {
        assertContains("cat", Normalizer.normalizeWord("cats"))
        assertContains("cat", Normalizer.normalizeWord("cats."))
        assertContains("box", Normalizer.normalizeWord("boxes"))
        assertContains("bus", Normalizer.normalizeWord("buses"))
        assertContains("buzz", Normalizer.normalizeWord("buzzes"))
        assertContains("study", Normalizer.normalizeWord("studies"))
        assertEquals("is", Normalizer.normalizeWord("is")?.first())
    }

    @Test
    fun handlesVerbForms() {
        assertContains("eat", Normalizer.normalizeWord("eating"))
        assertContains("run", Normalizer.normalizeWord("running."))
        assertContains("make", Normalizer.normalizeWord("making"))
        assertContains("run", Normalizer.normalizeWord("running"))
        assertContains("stop", Normalizer.normalizeWord("stopping"))
        assertContains("walk", Normalizer.normalizeWord("walked"))
        assertContains("like", Normalizer.normalizeWord("liked,"))
        assertContains("like", Normalizer.normalizeWord("liked"))
        assertContains("plan", Normalizer.normalizeWord("planned"))
    }

    @Test
    fun handlesComparativesAndAdverbs() {
        assertContains("fast", Normalizer.normalizeWord("faster"))
        assertContains("nice", Normalizer.normalizeWord("nicer"))
        assertContains("big", Normalizer.normalizeWord("bigger"))
        assertContains("fast", Normalizer.normalizeWord("fastest"))
        assertContains("nice", Normalizer.normalizeWord("nicest"))
        assertContains("quick", Normalizer.normalizeWord("quickly"))
    }

    @Test
    fun handlesPhrases() {
        assertEquals("ice cream", Normalizer.normalizeWord("ice cream")?.first())
        assertContains("ice cream", Normalizer.normalizeWord("ice creams"))
        assertContains("running water", Normalizer.normalizeWord("running waters"))
        assertContains("run water", Normalizer.normalizeWord("running waters"))
        assertNull(Normalizer.normalizeWord("one two three four"))
        assertTrue(requireNotNull(Normalizer.normalizeWord("running waters")).size <= 20)
    }

    private fun assertContains(expected: String, actual: List<String>?) {
        assertNotNull(actual)
        assertTrue("Expected $actual to contain $expected", actual!!.contains(expected))
    }
}
