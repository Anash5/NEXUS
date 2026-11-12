package org.nexus.provider.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nexus.provider.text.engine.TextProcessingEngine

class TextProcessingEngineTest {

    @Test
    fun testUppercase() {
        val result = TextProcessingEngine.toUppercase("nexus interop")
        assertEquals("NEXUS INTEROP", result)
    }

    @Test
    fun testLowercase() {
        val result = TextProcessingEngine.toLowercase("NEXUS FRAMEWORK")
        assertEquals("nexus framework", result)
    }

    @Test
    fun testReverse() {
        val result = TextProcessingEngine.reverse("Interoperability")
        assertEquals("ytilibareporetnI", result)
    }

    @Test
    fun testWordCountStandardizedFormat() {
        val input = "The nexus framework hub" // 4 words, 23 chars
        val jsonString = TextProcessingEngine.wordCountJson(input)

        // Strict verification of standardized JSON formatting {"wordCount": 4, "characters": 23}
        assertTrue(jsonString.contains("\"wordCount\": 4") || jsonString.contains("\"wordCount\":4"))
        assertTrue(jsonString.contains("\"characters\": 23") || jsonString.contains("\"characters\":23"))
    }

    @Test
    fun testWordCountAuditConsistency() {
        val input = "One two three four five" // 5 words, 23 chars
        val jsonString = TextProcessingEngine.wordCountJson(input)

        // Expected output per Correction 2: {"wordCount": 5, "characters": 23}
        assertEquals("""{"wordCount": 5, "characters": 23}""", jsonString)
    }
}
