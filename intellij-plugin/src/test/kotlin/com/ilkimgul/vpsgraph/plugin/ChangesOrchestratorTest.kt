package com.ilkimgul.vpsgraph.plugin

import com.ilkimgul.vpsgraph.core.InfraEdge
import com.ilkimgul.vpsgraph.core.InfraGraph
import com.ilkimgul.vpsgraph.core.InfraNode
import com.ilkimgul.vpsgraph.core.InfrastructureDiffStatus
import com.ilkimgul.vpsgraph.core.NodeType
import com.ilkimgul.vpsgraph.core.RelationType
import com.ilkimgul.vpsgraph.core.SshTarget
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ChangesOrchestratorTest {
    private val root = createTempDirectory("vps-graph-changes-")
    private val target = SshTarget("vps.example", 22, "vpsgraph", "/keys/id_ed25519")

    @AfterTest fun cleanup() {
        root.toFile().deleteRecursively()
    }

    @Test fun `first changed and deduplicated captures produce baseline changed and no-change payloads`() {
        val orchestrator = orchestrator()
        val baseline = assertNotNull(orchestrator.capture(target, graph()).payload)
        assertEquals(InfrastructureDiffStatus.NO_BASELINE, baseline.diff.status)
        assertEquals(1, baseline.history.size)

        val changed = assertNotNull(orchestrator.capture(target, graph(containerState = "running")).payload)
        assertEquals(InfrastructureDiffStatus.CHANGED, changed.diff.status)
        assertEquals(baseline.comparison.current.snapshotId, changed.comparison.previous?.snapshotId)
        assertTrue(changed.resources.any { it.after?.label == "worker" })

        val deduplicated = assertNotNull(orchestrator.capture(target, graph(containerState = "running")).payload)
        assertEquals(InfrastructureDiffStatus.NO_CHANGES, deduplicated.diff.status)
        assertFalse(deduplicated.comparison.current.persisted)
        assertEquals(2, deduplicated.history.size)
    }

    @Test fun `history survives recreation and requested same-server comparison stays typed`() {
        val first = assertNotNull(orchestrator().capture(target, graph()).payload).comparison.current.snapshotId
        val second = assertNotNull(orchestrator().capture(target, graph("stopped")).payload).comparison.current.snapshotId
        val recreated = orchestrator()
        val afterRestart = assertNotNull(recreated.capture(target, graph("stopped")).payload)
        assertEquals(2, afterRestart.history.size)

        val comparison = recreated.compare(first, second)
        assertTrue(comparison.ok)
        assertEquals(InfrastructureDiffStatus.CHANGED, comparison.payload?.diff?.status)
        assertEquals(first, comparison.payload?.comparison?.previous?.snapshotId)
        assertEquals(second, comparison.payload?.comparison?.current?.snapshotId)
    }

    @Test fun `Kotlin Changes wire contract retains empty diff collections and default summary values`() {
        val orchestrator = orchestrator()
        val baseline = assertNotNull(orchestrator.capture(target, graph()).payload)
        val noChanges = assertNotNull(orchestrator.capture(target, graph()).payload)
        val changed = assertNotNull(orchestrator.capture(target, graph("running")).payload)

        listOf(baseline, noChanges, changed).forEach { payload ->
            val encoded = Json.parseToJsonElement(Json.encodeToString(ChangesResponse.success(payload))).jsonObject
                .getValue("payload").jsonObject
            val diff = encoded.getValue("diff").jsonObject
            assertEquals(1, encoded.getValue("schemaVersion").jsonPrimitive.int)
            assertTrue(diff.getValue("nodeChanges") is JsonArray)
            assertTrue(diff.getValue("relationshipChanges") is JsonArray)
            assertTrue(diff.getValue("uncertainties") is JsonArray)
            assertTrue(diff.getValue("summary").jsonObject.containsKey("addedNodes"))
            assertTrue(diff.getValue("summary").jsonObject.containsKey("isComplete"))
            assertTrue(encoded.getValue("history").jsonArray.isNotEmpty())
        }
        val noChangesSummary = Json.parseToJsonElement(Json.encodeToString(ChangesResponse.success(noChanges))).jsonObject
            .getValue("payload").jsonObject.getValue("diff").jsonObject.getValue("summary").jsonObject
        assertEquals(0, noChangesSummary.getValue("addedNodes").jsonPrimitive.int)
        assertTrue(noChangesSummary.getValue("isComplete").jsonPrimitive.content.toBoolean())
    }

    @Test fun `wrong-server missing unsafe and retention-removed identifiers fail closed`() {
        val repository = FileSnapshotRepository(root, retentionLimit = 1)
        val orchestrator = ChangesOrchestrator(SnapshotPersistence(ServerIdentityStore(root), repository))
        val old = assertNotNull(orchestrator.capture(target, graph()).payload).comparison.current.snapshotId
        val current = assertNotNull(orchestrator.capture(target, graph("running")).payload).comparison.current.snapshotId
        assertEquals("SNAPSHOT_UNAVAILABLE", orchestrator.compare(old, current).error?.code)
        assertEquals("INVALID_SNAPSHOT_ID", orchestrator.compare("../index", current).error?.code)
        assertEquals("SNAPSHOT_UNAVAILABLE", orchestrator.compare(MISSING_ID, current).error?.code)

        val other = ChangesOrchestrator(SnapshotPersistence(ServerIdentityStore(root), repository))
        val otherId = assertNotNull(other.capture(target.copy(host = "other.example"), graph("other")).payload).comparison.current.snapshotId
        assertEquals("SNAPSHOT_UNAVAILABLE", orchestrator.compare(otherId, current).error?.code)
    }

    @Test fun `corrupt and unsupported snapshots return safe errors without raw data or paths`() {
        val orchestrator = orchestrator()
        val first = assertNotNull(orchestrator.capture(target, graph()).payload).comparison.current.snapshotId
        val second = assertNotNull(orchestrator.capture(target, graph("running")).payload).comparison.current.snapshotId
        val serverId = ServerIdentityStore(root).resolve(target)
        val path = root.resolve("snapshots").resolve(serverId).resolve("$second.json")

        Files.writeString(path, "{corrupt-secret-content")
        val corrupt = orchestrator.compare(first, second)
        assertFalse(corrupt.ok)
        assertEquals("SNAPSHOT_UNAVAILABLE", corrupt.error?.code)
        assertTrue("corrupt-secret-content" !in Json.encodeToString(corrupt) && path.toString() !in Json.encodeToString(corrupt))

        val restored = assertNotNull(orchestrator.capture(target, graph("stopped")).payload).comparison.current.snapshotId
        val restoredPath = root.resolve("snapshots").resolve(serverId).resolve("$restored.json")
        Files.writeString(restoredPath, Files.readString(restoredPath).replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":99"))
        val unsupported = orchestrator.compare(first, restored)
        assertFalse(unsupported.ok)
        assertEquals("SNAPSHOT_UNAVAILABLE", unsupported.error?.code)
    }

    private fun orchestrator(): ChangesOrchestrator {
        val repository = FileSnapshotRepository(root)
        return ChangesOrchestrator(SnapshotPersistence(ServerIdentityStore(root), repository))
    }

    private fun graph(containerState: String? = null): InfraGraph {
        val server = InfraNode(
            "server:stable",
            "vps",
            NodeType.SERVER,
            mapOf("hostname" to "vps", "systemd discovery status" to "DISCOVERED", "listener discovery status" to "DISCOVERED"),
        )
        val docker = InfraNode("server:stable:docker", "Docker", NodeType.DOCKER_ENGINE, mapOf("docker discovery state" to "AVAILABLE", "caddy discovery state" to "NOT_DETECTED"))
        val nodes = mutableListOf(server, docker)
        val edges = mutableListOf(InfraEdge("runs:docker", server.id, docker.id, RelationType.RUNS))
        containerState?.let {
            val worker = InfraNode("container:worker", "worker", NodeType.DOCKER_CONTAINER, mapOf("state" to it, "image" to "worker:1.0"))
            nodes += worker
            edges += InfraEdge("runs:worker", docker.id, worker.id, RelationType.RUNS)
        }
        return InfraGraph(nodes, edges)
    }

    private companion object {
        const val MISSING_ID = "99999999-9999-4999-8999-999999999999"
    }
}
