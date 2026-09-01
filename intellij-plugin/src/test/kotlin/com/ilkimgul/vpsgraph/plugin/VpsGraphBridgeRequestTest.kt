package com.ilkimgul.vpsgraph.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VpsGraphBridgeRequestTest {
    @Test
    fun `accepts only bounded JSON objects`() {
        assertEquals("GET_GRAPH", parseBridgeRequest("""{"type":"GET_GRAPH"}""")?.get("type")?.toString()?.trim('"'))
        assertNull(parseBridgeRequest("not json"))
        assertNull(parseBridgeRequest("[]"))
        assertNull(parseBridgeRequest("""{"type":"GET_GRAPH","padding":"${"é".repeat(40_000)}"}"""))
    }
}
