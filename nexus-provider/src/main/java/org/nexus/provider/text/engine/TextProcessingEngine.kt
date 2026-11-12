package org.nexus.provider.text.engine

/**
 * Pure text processing engine implementing the 4 required MVP capabilities.
 */
object TextProcessingEngine {

    fun toUppercase(input: String): String = input.uppercase()

    fun toLowercase(input: String): String = input.lowercase()

    fun reverse(input: String): String = input.reversed()

    /**
     * Standardized Word Count output as required by Correction 6:
     * {"wordCount": 5, "characters": 23}
     */
    fun wordCountJson(input: String): String {
        val trimmed = input.trim()
        val words = if (trimmed.isEmpty()) 0 else trimmed.split("\\s+".toRegex()).size
        val characters = input.length
        return """{"wordCount": $words, "characters": $characters}"""
    }
}
