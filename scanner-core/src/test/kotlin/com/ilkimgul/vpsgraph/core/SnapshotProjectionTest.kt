package com.ilkimgul.vpsgraph.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SnapshotProjectionTest {
    @Test fun `projection and fingerprint are deterministic and exclude capture and unknown runtime metadata`() {
        val graph = realisticGraph()
        val reordered = InfraGraph(
            graph.nodes.reversed().map { it.copy(metadata = it.metadata.entries.reversed().associate { entry -> entry.toPair() }) },
            graph.edges.reversed(),
        )
        val first = SnapshotProjection.capture(graph, SERVER_ID, "11111111-1111-1111-1111-111111111111", Instant.parse("2026-08-30T10:00:00Z"))
        val second = SnapshotProjection.capture(reordered, SERVER_ID, "22222222-2222-2222-2222-222222222222", Instant.parse("2026-08-31T10:00:00Z"))

        assertEquals(first.content, second.content)
        assertEquals(first.graphFingerprint, second.graphFingerprint)
        assertNotEquals(first.snapshotId, second.snapshotId)
        assertNotEquals(first.capturedAt, second.capturedAt)

        val withRuntimeMetadata = graph.copy(nodes = graph.nodes.mapIndexed { index, node ->
            if (index == 0) node.copy(metadata = node.metadata + ("ui selection" to "selected") + ("PID" to "1234")) else node
        })
        assertEquals(first.graphFingerprint, SnapshotProjection.capture(withRuntimeMetadata, SERVER_ID).graphFingerprint)
        val serialized = Json.encodeToString(first)
        assertFalse("ui selection" in serialized)
        assertFalse("\"PID\"" in serialized)
    }

    @Test fun `meaningful nodes edges and metadata change the fingerprint`() {
        val graph = realisticGraph()
        val original = SnapshotProjection.capture(graph, SERVER_ID).graphFingerprint
        val changedState = graph.copy(nodes = graph.nodes.map { node ->
            if (node.type == NodeType.DOCKER_CONTAINER && node.metadata["state"] != null) {
                node.copy(metadata = node.metadata + ("state" to "stopped"))
            } else node
        })
        val extra = InfraNode("$SERVER_ID:test-node", "test", NodeType.DIRECTORY, mapOf("path" to "/srv/test"))
        val withNode = graph.copy(nodes = graph.nodes + extra)
        val withEdge = withNode.copy(edges = withNode.edges + InfraEdge("$SERVER_ID:test-edge", graph.nodes.first().id, extra.id, RelationType.CONTAINS))

        assertNotEquals(original, SnapshotProjection.capture(changedState, SERVER_ID).graphFingerprint)
        assertNotEquals(original, SnapshotProjection.capture(withNode, SERVER_ID).graphFingerprint)
        assertNotEquals(SnapshotProjection.capture(withNode, SERVER_ID).graphFingerprint, SnapshotProjection.capture(graph, SERVER_ID).graphFingerprint)
        assertNotEquals(SnapshotProjection.capture(withEdge, SERVER_ID).graphFingerprint, SnapshotProjection.capture(withNode, SERVER_ID).graphFingerprint)
        assertNotEquals(SnapshotProjection.capture(withNode, SERVER_ID).graphFingerprint, SnapshotProjection.capture(withNode.copy(nodes = withNode.nodes - extra), SERVER_ID).graphFingerprint)
        assertNotEquals(SnapshotProjection.capture(withEdge, SERVER_ID).graphFingerprint, SnapshotProjection.capture(withEdge.copy(edges = withEdge.edges.dropLast(1)), SERVER_ID).graphFingerprint)
    }

    @Test fun `retains discovery quality and established infrastructure identities`() {
        val snapshot = SnapshotProjection.capture(realisticGraph(), SERVER_ID)

        assertEquals("AVAILABLE", snapshot.content.discoveryQuality.single { it.subsystem == "DOCKER" }.state)
        assertEquals("DISCOVERED", snapshot.content.discoveryQuality.single { it.subsystem == "SYSTEMD" }.state)
        assertEquals("DISCOVERED", snapshot.content.discoveryQuality.single { it.subsystem == "HOST_LISTENERS" }.state)
        assertTrue(snapshot.content.nodes.any { it.metadata["unit"] == "systemd-fsck@dev-disk-by\\x2duuid-352C\\x2dFCAB.service" })
        assertTrue(snapshot.content.nodes.any { it.metadata["local address"] == "fe80::eac9:da0a:d32:2190%eth0" })
        assertTrue(snapshot.content.nodes.any { it.type == NodeType.DOCKER_CONTAINER.name && it.id.contains(":container:") })
        assertTrue(snapshot.content.nodes.any { it.type == NodeType.DOCKER_COMPOSE_PROJECT.name && it.id.contains(":compose:") })
        assertTrue(snapshot.content.nodes.any { it.type == NodeType.DOMAIN.name && it.label == "monitor.example.net" })
        assertTrue(snapshot.content.nodes.any { it.type == NodeType.UPSTREAM.name && it.label == "host.docker.internal:19999" })
        assertTrue(snapshot.content.edges.any { it.relation == RelationType.TARGETS.name })
        assertTrue(snapshot.content.edges.any { it.relation == RelationType.OWNED_BY.name })
        assertTrue(listOf("caddy", "dialens-api", "n8n", "portfolio-api", "portfolio-web").all { expected ->
            snapshot.content.nodes.any { it.type == NodeType.DOCKER_CONTAINER.name && it.label == expected }
        })
        assertTrue(listOf("ssh.service", "netdata.service", "networking.service").all { expected ->
            snapshot.content.nodes.any { it.type == NodeType.SYSTEMD_SERVICE.name && it.label == expected }
        })
        assertTrue(listOf(22, 80, 443, 19999, 546).all { expected ->
            snapshot.content.nodes.any { it.type == NodeType.HOST_LISTENER.name && it.metadata["port"] == expected.toString() }
        })
        assertTrue(listOf("admin.example.com", "api.example.com", "api.example.net", "example.com", "www.example.com", "monitor.example.net", "automation.example.net").all { expected ->
            snapshot.content.nodes.any { it.type == NodeType.DOMAIN.name && it.label == expected }
        })
    }

    @Test fun `discovery failures retain fixed status and reason codes without raw reason text`() {
        val graph = InfraGraph(
            nodes = listOf(
                InfraNode(
                    "server:test",
                    "test",
                    NodeType.SERVER,
                    mapOf(
                        "systemd discovery status" to "COMMAND_FAILED",
                        "systemd status reasons" to "COMMAND_FAILED,TRUNCATED,raw detail",
                        "listener discovery status" to "PARTIAL",
                        "listener status reasons" to "LISTENER_ROW_MALFORMED",
                    ),
                ),
                InfraNode(
                    "server:test:docker",
                    "Docker unavailable",
                    NodeType.DOCKER_ENGINE,
                    mapOf("docker discovery state" to "SCAN_FAILED", "caddy discovery state" to "CONFIG_UNAVAILABLE"),
                ),
            ),
            edges = emptyList(),
        )

        val quality = SnapshotProjection.content(graph).discoveryQuality.associateBy { it.subsystem }
        assertEquals("COMMAND_FAILED", quality.getValue("SYSTEMD").state)
        assertEquals(listOf("COMMAND_FAILED", "TRUNCATED"), quality.getValue("SYSTEMD").reasons)
        assertEquals("PARTIAL", quality.getValue("HOST_LISTENERS").state)
        assertEquals("SCAN_FAILED", quality.getValue("DOCKER").state)
        assertEquals("CONFIG_UNAVAILABLE", quality.getValue("CADDY").state)
        assertFalse(Json.encodeToString(quality).contains("raw detail"))
    }

    private fun realisticGraph(): InfraGraph {
        val docker = DockerHelperParser.parse(checkNotNull(javaClass.getResource("/docker/helper-caddy-realistic.json")).readText())
        val hostResources = DockerHelperParser.parse(checkNotNull(javaClass.getResource("/docker/helper-systemd-realistic.json")).readText())
        return HostDockerGraphFactory.create(
            HostDiscoveryResult(
                HostInfo("vps-prod", "Debian GNU/Linux", "13", "Linux", "6.12", "x86_64"),
                docker.copy(systemd = hostResources.systemd, listeners = hostResources.listeners),
            ),
            SshTarget("vps.example", 22, "vpsgraph", "/keys/never-persisted"),
        )
    }

    private companion object {
        const val SERVER_ID = "11111111-2222-3333-4444-555555555555"
    }
}
