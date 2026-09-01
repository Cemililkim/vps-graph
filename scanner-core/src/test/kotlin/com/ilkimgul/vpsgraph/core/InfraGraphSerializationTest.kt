package com.ilkimgul.vpsgraph.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class InfraGraphSerializationTest {
    @Test
    fun `serializes stable node ids and relationships`() {
        val graph = InfraGraph(
            nodes = listOf(
                InfraNode("server:production-vps", "Production VPS", NodeType.SERVER),
                InfraNode("container:portfolio", "portfolio-container", NodeType.DOCKER_CONTAINER),
            ),
            edges = listOf(
                InfraEdge(
                    id = "server-runs-container",
                    source = "server:production-vps",
                    target = "container:portfolio",
                    relation = RelationType.RUNS,
                ),
            ),
        )

        val json = Json.encodeToString(graph)
        val decoded = Json.decodeFromString<InfraGraph>(json)

        assertEquals("server:production-vps", decoded.nodes.first().id)
        assertEquals(RelationType.RUNS, decoded.edges.single().relation)
        assertTrue(json.contains("container:portfolio"))
    }
}

