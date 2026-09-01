package com.ilkimgul.vpsgraph.core

data class OsRelease(
    val name: String? = null,
    val version: String? = null,
)

object OsReleaseParser {
    fun parse(input: String): OsRelease {
        val fields = mutableMapOf<String, String>()
        input.lineSequence().take(MAX_LINES).forEach { line ->
            if (line.length > MAX_LINE_CHARS) return@forEach
            val separator = line.indexOf('=')
            if (separator <= 0) return@forEach
            val key = line.substring(0, separator)
            if (key !in FIELDS) return@forEach
            value(line.substring(separator + 1))?.let { fields[key] = it }
        }

        return OsRelease(
            name = fields["PRETTY_NAME"] ?: fields["NAME"],
            version = fields["VERSION_ID"] ?: fields["VERSION"],
        )
    }

    private fun value(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.length > MAX_VALUE_CHARS || trimmed.any { it == '\u0000' }) return null
        if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
            return trimmed.substring(1, trimmed.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        }
        return trimmed
    }

    private val FIELDS = setOf("PRETTY_NAME", "NAME", "VERSION_ID", "VERSION")
    private const val MAX_LINES = 256
    private const val MAX_LINE_CHARS = 4096
    private const val MAX_VALUE_CHARS = 2048
}
