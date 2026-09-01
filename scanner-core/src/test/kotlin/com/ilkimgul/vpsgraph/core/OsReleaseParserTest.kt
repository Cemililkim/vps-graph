package com.ilkimgul.vpsgraph.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OsReleaseParserTest {
    @Test fun `parses Debian fixture`() = assertEquals(
        OsRelease("Debian GNU/Linux 12 (bookworm)", "12"), parse("debian"),
    )

    @Test fun `parses Ubuntu fixture`() = assertEquals(OsRelease("Ubuntu", "24.04"), parse("ubuntu"))

    @Test fun `parses supported Debian and Ubuntu release variants`() {
        assertEquals(OsRelease("Debian GNU/Linux 13 (trixie)", "13"), parse("debian13"))
        assertEquals(OsRelease("Ubuntu 22.04.5 LTS", "22.04"), parse("ubuntu2204"))
        assertEquals(OsRelease("Ubuntu 24.04.3 LTS", "24.04"), parse("ubuntu2404"))
    }

    @Test fun `allows incomplete fields`() = assertEquals(OsRelease("Alpine", null), parse("incomplete"))

    @Test fun `allows empty input`() = assertEquals(OsRelease(), parse("empty"))

    @Test fun `ignores additional fields`() = assertEquals(OsRelease("Fedora Linux", "41"), parse("extra"))

    @Test fun `bounded arbitrary input never escapes the allowlist or crashes`() {
        repeat(256) { seed ->
            val text = buildString {
                repeat(40) { index -> append("FUTURE_${seed}_$index=").append(('!'..'~').map { it }.joinToString("")).append('\n') }
                append("PRETTY_NAME=Linux-$seed\nVERSION_ID=${seed % 100}\n")
            }
            assertEquals(OsRelease("Linux-$seed", (seed % 100).toString()), OsReleaseParser.parse(text))
        }
        assertEquals(OsRelease(), OsReleaseParser.parse("PRETTY_NAME=${"x".repeat(5000)}"))
    }

    private fun parse(name: String): OsRelease = OsReleaseParser.parse(
        checkNotNull(javaClass.getResource("/os-release/$name.txt")).readText(),
    )
}
