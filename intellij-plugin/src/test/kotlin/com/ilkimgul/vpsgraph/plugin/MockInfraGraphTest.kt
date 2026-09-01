package com.ilkimgul.vpsgraph.plugin

import com.ilkimgul.vpsgraph.core.RelationType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MockInfraGraphTest {
    @Test
    fun `mock graph keeps stable ids and expected relationships`() {
        val graph = MockInfraGraph.value

        assertEquals("server:production-vps", graph.nodes.first().id)
        assertTrue(graph.edges.any { it.relation == RelationType.ROUTES_TO })
        assertTrue(MockInfraGraph.asJson().contains("container-exposes-port"))
    }

    @Test
    fun `bundled frontend is inlined for JCEF`() {
        val html = BundledFrontend.html()

        assertTrue(html.contains("<style>"))
        assertTrue(html.contains("<script type=\"module\">"))
        assertTrue(!html.contains("./assets/"))
    }
}
