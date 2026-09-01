package com.ilkimgul.vpsgraph.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class InfrastructureDiffEngineTest {
    @Test fun `identical content fast path ignores capture metadata and first snapshot has no baseline`() {
        val previous = snapshot(SNAPSHOT_1, "2026-08-30T10:00:00Z")
        val current = snapshot(SNAPSHOT_2, "2026-08-30T11:00:00Z")

        assertEquals(previous.graphFingerprint, current.graphFingerprint)
        assertEquals(InfrastructureDiffStatus.NO_CHANGES, InfrastructureDiffEngine.compare(previous, current).status)
        assertEquals(InfrastructureDiffStatus.NO_BASELINE, InfrastructureDiffEngine.compare(null, current).status)
        assertTrue(InfrastructureDiffEngine.compare(previous, current).nodeChanges.isEmpty())
    }

    @Test fun `rejects incompatible schemas server mismatch reversed time and invalid canonical input`() {
        val previous = snapshot(SNAPSHOT_1, "2026-08-30T10:00:00Z")
        val current = snapshot(SNAPSHOT_2, "2026-08-30T11:00:00Z")

        assertEquals(InfrastructureDiffStatus.INCOMPATIBLE_SNAPSHOT_SCHEMA, InfrastructureDiffEngine.compare(previous, current.copy(schemaVersion = 2)).status)
        assertEquals(InfrastructureDiffStatus.SERVER_MISMATCH, InfrastructureDiffEngine.compare(previous, current.copy(serverId = OTHER_SERVER_ID)).status)
        assertEquals(InfrastructureDiffStatus.INVALID_TIME_ORDER, InfrastructureDiffEngine.compare(current, previous).status)
        val duplicate = previous.content.copy(nodes = previous.content.nodes + previous.content.nodes.first())
        assertEquals(InfrastructureDiffStatus.INVALID_SNAPSHOT, InfrastructureDiffEngine.compare(previous, withContent(current, duplicate)).status)
    }

    @Test fun `classifies node edge and metadata changes with deterministic fields`() {
        val oldContainer = node("container:web", NodeType.DOCKER_CONTAINER, "web", mapOf("state" to "running", "image" to "web:v1", "restart policy" to "always"))
        val oldPort = node("port:old", NodeType.PORT, "3000/tcp", mapOf("host binding" to "0.0.0.0:8080"))
        val previous = snapshot(
            SNAPSHOT_1,
            "2026-08-30T10:00:00Z",
            nodes = listOf(server, oldContainer, oldPort),
            edges = listOf(edge("runs:web", server.id, oldContainer.id, RelationType.RUNS), edge("exposes:old", oldContainer.id, oldPort.id, RelationType.EXPOSES)),
        )
        val newContainer = oldContainer.copy(label = "web-api", metadata = mapOf("state" to "stopped", "image" to "web:v2", "compose service" to "api"))
        val worker = node("container:worker", NodeType.DOCKER_CONTAINER, "worker", mapOf("state" to "running"))
        val newPort = node("port:new", NodeType.PORT, "3000/tcp", mapOf("host binding" to "0.0.0.0:8081"))
        val current = snapshot(
            SNAPSHOT_2,
            "2026-08-30T11:00:00Z",
            nodes = listOf(server, newContainer, worker, newPort),
            edges = listOf(edge("runs:web", server.id, newContainer.id, RelationType.RUNS), edge("runs:worker", server.id, worker.id, RelationType.RUNS), edge("exposes:new", newContainer.id, newPort.id, RelationType.EXPOSES)),
        )

        val diff = InfrastructureDiffEngine.compare(previous, current)
        assertEquals(InfrastructureDiffStatus.CHANGED, diff.status)
        assertEquals(2, diff.summary.addedNodes)
        assertEquals(1, diff.summary.removedNodes)
        assertEquals(1, diff.summary.modifiedNodes)
        assertEquals(2, diff.summary.addedRelationships)
        assertEquals(1, diff.summary.removedRelationships)
        val fields = diff.nodeChanges.single { it.kind == NodeChangeKind.MODIFIED }.fields
        assertEquals(fields.sortedBy(FieldChange::field), fields)
        assertTrue(FieldChange("label", "web", "web-api") in fields)
        assertTrue(FieldChange("metadata:compose service", null, "api") in fields)
        assertTrue(FieldChange("metadata:restart policy", "always", null) in fields)
        assertTrue(FieldChange("metadata:state", "running", "stopped") in fields)
    }

    @Test fun `semantic input ordering and repeated comparisons produce identical serialized output`() {
        val a = node("container:a", NodeType.DOCKER_CONTAINER, "a", linkedMapOf("state" to "running", "image" to "a:v1"))
        val b = node("container:b", NodeType.DOCKER_CONTAINER, "b")
        val previous = snapshot(SNAPSHOT_1, "2026-08-30T10:00:00Z", nodes = listOf(server, a), edges = listOf(edge("runs:a", server.id, a.id, RelationType.RUNS)))
        val current = snapshot(SNAPSHOT_2, "2026-08-30T11:00:00Z", nodes = listOf(server, a.copy(metadata = a.metadata.entries.reversed().associate { it.toPair() }), b), edges = listOf(edge("runs:b", server.id, b.id, RelationType.RUNS), edge("runs:a", server.id, a.id, RelationType.RUNS)))
        val reorderedPrevious = withContent(previous, previous.content.copy(nodes = previous.content.nodes.reversed(), edges = previous.content.edges.reversed()))
        val reorderedCurrent = withContent(current, current.content.copy(nodes = current.content.nodes.reversed(), edges = current.content.edges.reversed()))

        val expected = InfrastructureDiffEngine.compare(previous, current)
        val actual = InfrastructureDiffEngine.compare(reorderedPrevious, reorderedCurrent)
        assertEquals(expected, actual)
        assertEquals(Json.encodeToString(expected), Json.encodeToString(InfrastructureDiffEngine.compare(previous, current)))
    }

    @Test fun `current discovery quality controls confirmed and unconfirmed removals for every subsystem`() {
        val cases = listOf(
            Triple(NodeType.DOCKER_CONTAINER, DiffSubsystem.DOCKER, "SCAN_FAILED"),
            Triple(NodeType.DOMAIN, DiffSubsystem.CADDY, "PARTIAL"),
            Triple(NodeType.SYSTEMD_SERVICE, DiffSubsystem.SYSTEMD, "COMMAND_FAILED"),
            Triple(NodeType.HOST_LISTENER, DiffSubsystem.HOST_LISTENERS, "PARTIAL"),
        )
        cases.forEachIndexed { index, (type, subsystem, incompleteState) ->
            val resource = node("resource:$index", type, type.name)
            val previous = snapshot(SNAPSHOT_1, "2026-08-30T10:00:00Z", nodes = listOf(server, resource))
            val confirmed = InfrastructureDiffEngine.compare(previous, snapshot(SNAPSHOT_2, "2026-08-30T11:00:00Z"))
            assertEquals(ChangeEvidence.CONFIRMED, confirmed.nodeChanges.single { it.nodeId == resource.id }.evidence)

            val incomplete = completeQuality().toMutableMap().apply { put(subsystem, incompleteState) }
            val uncertain = InfrastructureDiffEngine.compare(previous, snapshot(SNAPSHOT_2, "2026-08-30T11:00:00Z", quality = incomplete))
            assertEquals(ChangeEvidence.UNCONFIRMED_REMOVAL, uncertain.nodeChanges.single { it.nodeId == resource.id }.evidence)
            assertEquals(0, uncertain.summary.removedNodes)
            assertEquals(InfrastructureDiffStatus.PARTIAL, uncertain.status)
        }
    }

    @Test fun `previous incomplete absence qualifies additions as newly observed`() {
        val dockerNode = node("container:new", NodeType.DOCKER_CONTAINER, "new")
        val serviceNode = node("service:new", NodeType.SYSTEMD_SERVICE, "new.service")
        val dockerPrevious = snapshot(SNAPSHOT_1, "2026-08-30T10:00:00Z", quality = completeQuality() + (DiffSubsystem.DOCKER to "SCAN_FAILED"))
        val servicePrevious = snapshot(SNAPSHOT_1, "2026-08-30T10:00:00Z", quality = completeQuality() + (DiffSubsystem.SYSTEMD to "COMMAND_FAILED"))

        val dockerDiff = InfrastructureDiffEngine.compare(dockerPrevious, snapshot(SNAPSHOT_2, "2026-08-30T11:00:00Z", nodes = listOf(server, dockerNode)))
        val serviceDiff = InfrastructureDiffEngine.compare(servicePrevious, snapshot(SNAPSHOT_2, "2026-08-30T11:00:00Z", nodes = listOf(server, serviceNode)))
        assertEquals(ChangeEvidence.NEWLY_OBSERVED, dockerDiff.nodeChanges.single().evidence)
        assertEquals(ChangeEvidence.NEWLY_OBSERVED, serviceDiff.nodeChanges.single().evidence)
        assertTrue(dockerDiff.uncertainties.any { it.subsystem == DiffSubsystem.DOCKER && it.reason == DiffUncertaintyReason.PREVIOUS_DISCOVERY_INCOMPLETE })

        val partialCurrent = snapshot(
            SNAPSHOT_2,
            "2026-08-30T11:00:00Z",
            nodes = listOf(server, serviceNode),
            quality = completeQuality() + (DiffSubsystem.SYSTEMD to "PARTIAL"),
        )
        assertEquals(ChangeEvidence.CONFIRMED, InfrastructureDiffEngine.compare(snapshot(SNAPSHOT_1, "2026-08-30T10:00:00Z"), partialCurrent).nodeChanges.single().evidence)
    }

    @Test fun `relationship removals require every endpoint subsystem and additions keep positive evidence`() {
        val upstream = node("upstream:host", NodeType.UPSTREAM, "host.docker.internal:19999")
        val listener = node("listener:19999", NodeType.HOST_LISTENER, "172.17.0.1:19999/tcp")
        val relation = edge("targets:listener", upstream.id, listener.id, RelationType.TARGETS)
        val previous = snapshot(SNAPSHOT_1, "2026-08-30T10:00:00Z", nodes = listOf(server, upstream, listener), edges = listOf(relation))

        assertEquals(ChangeEvidence.CONFIRMED, InfrastructureDiffEngine.compare(previous, snapshot(SNAPSHOT_2, "2026-08-30T11:00:00Z", nodes = listOf(server, upstream, listener))).relationshipChanges.single().evidence)
        listOf(
            DiffSubsystem.DOCKER to "SCAN_FAILED",
            DiffSubsystem.CADDY to "PARTIAL",
            DiffSubsystem.HOST_LISTENERS to "COMMAND_FAILED",
        ).forEach { (subsystem, state) ->
            val current = snapshot(SNAPSHOT_2, "2026-08-30T11:00:00Z", nodes = listOf(server, upstream, listener), quality = completeQuality() + (subsystem to state))
            assertEquals(ChangeEvidence.UNCONFIRMED_REMOVAL, InfrastructureDiffEngine.compare(previous, current).relationshipChanges.single().evidence)
        }

        val service = node("service:metrics", NodeType.SYSTEMD_SERVICE, "metrics.service")
        val owner = edge("owner:metrics", listener.id, service.id, RelationType.OWNED_BY)
        val ownershipPrevious = snapshot(SNAPSHOT_1, "2026-08-30T10:00:00Z", nodes = listOf(server, listener, service), edges = listOf(owner))
        val ownershipCurrent = snapshot(
            SNAPSHOT_2,
            "2026-08-30T11:00:00Z",
            nodes = listOf(server, listener, service),
            quality = completeQuality() + (DiffSubsystem.SYSTEMD to "COMMAND_FAILED"),
        )
        assertEquals(ChangeEvidence.UNCONFIRMED_REMOVAL, InfrastructureDiffEngine.compare(ownershipPrevious, ownershipCurrent).relationshipChanges.single().evidence)
    }

    @Test fun `quality transitions remain context rather than resource modifications`() {
        val states = listOf("PARTIAL", "DISCOVERED", "COMMAND_FAILED", "DISCOVERED")
        val pairs = listOf("DISCOVERED" to states[0], "PARTIAL" to states[1], "DISCOVERED" to states[2], "COMMAND_FAILED" to states[3])
        pairs.forEach { (before, after) ->
            val previous = snapshot(SNAPSHOT_1, "2026-08-30T10:00:00Z", quality = completeQuality() + (DiffSubsystem.SYSTEMD to before))
            val current = snapshot(SNAPSHOT_2, "2026-08-30T11:00:00Z", quality = completeQuality() + (DiffSubsystem.SYSTEMD to after))
            val diff = InfrastructureDiffEngine.compare(previous, current)
            assertTrue(diff.nodeChanges.isEmpty())
            assertTrue(diff.relationshipChanges.isEmpty())
            assertEquals(if (after == "DISCOVERED") InfrastructureDiffStatus.NO_CHANGES else InfrastructureDiffStatus.PARTIAL, diff.status)
        }
    }

    @Test fun `degraded current discovery suppresses missing metadata fields but keeps direct value changes`() {
        val before = node("service:web", NodeType.SYSTEMD_SERVICE, "web.service", mapOf("active state" to "active", "user" to "web"))
        val after = before.copy(metadata = mapOf("active state" to "failed"))
        val previous = snapshot(SNAPSHOT_1, "2026-08-30T10:00:00Z", nodes = listOf(server, before))
        val current = snapshot(SNAPSHOT_2, "2026-08-30T11:00:00Z", nodes = listOf(server, after), quality = completeQuality() + (DiffSubsystem.SYSTEMD to "PARTIAL"))

        val fields = InfrastructureDiffEngine.compare(previous, current).nodeChanges.single { it.kind == NodeChangeKind.MODIFIED }.fields
        assertEquals(listOf(FieldChange("metadata:active state", "active", "failed")), fields)

        val listenerCountBefore = before.copy(metadata = mapOf("active state" to "active", "listener count" to "2"))
        val listenerCountAfter = listenerCountBefore.copy(metadata = mapOf("active state" to "active", "listener count" to "0"))
        val listenerPrevious = snapshot(SNAPSHOT_1, "2026-08-30T10:00:00Z", nodes = listOf(server, listenerCountBefore))
        val listenerCurrent = snapshot(SNAPSHOT_2, "2026-08-30T11:00:00Z", nodes = listOf(server, listenerCountAfter), quality = completeQuality() + (DiffSubsystem.HOST_LISTENERS to "COMMAND_FAILED"))
        assertTrue(InfrastructureDiffEngine.compare(listenerPrevious, listenerCurrent).nodeChanges.isEmpty())
    }

    @Test fun `realistic VPS changes retain exact identities and suppress only unprovable removals`() {
        val baseline = realisticSnapshot(SNAPSHOT_1, "2026-08-30T10:00:00Z")
        val worker = node("${server.id}:systemd:worker", NodeType.SYSTEMD_SERVICE, "worker.service", mapOf("unit" to "worker.service", "active state" to "active"))
        val serverNode = baseline.content.nodes.single { it.type == NodeType.SERVER.name }
        val workerEdge = edge("${serverNode.id}:runs:${worker.id}", serverNode.id, worker.id, RelationType.RUNS)
        val n8n = baseline.content.nodes.single { it.type == NodeType.DOCKER_CONTAINER.name && it.label == "n8n" }
        val changedNodes = baseline.content.nodes.map { if (it.id == n8n.id) it.copy(metadata = it.metadata + ("state" to "stopped")) else it } + worker
        val changed = withContent(baseline.copy(snapshotId = SNAPSHOT_2, capturedAt = Instant.parse("2026-08-30T11:00:00Z")), baseline.content.copy(nodes = changedNodes, edges = baseline.content.edges + workerEdge))
        val changedDiff = InfrastructureDiffEngine.compare(baseline, changed)
        assertEquals(ChangeEvidence.CONFIRMED, changedDiff.nodeChanges.single { it.nodeId == worker.id }.evidence)
        assertTrue(changedDiff.nodeChanges.single { it.nodeId == n8n.id }.fields.any { it.field == "metadata:state" })

        val escaped = baseline.content.nodes.single { it.metadata["unit"] == "systemd-fsck@dev-disk-by\\x2duuid-352C\\x2dFCAB.service" }
        val scoped = baseline.content.nodes.single { it.metadata["local address"] == "fe80::eac9:da0a:d32:2190%eth0" }
        val removedNodes = baseline.content.nodes.filterNot { it.id in setOf(escaped.id, scoped.id) }
        val removedEdges = baseline.content.edges.filter { it.source in removedNodes.map(SnapshotNode::id).toSet() && it.target in removedNodes.map(SnapshotNode::id).toSet() }
        val removed = withContent(baseline.copy(snapshotId = SNAPSHOT_2, capturedAt = Instant.parse("2026-08-30T11:00:00Z")), baseline.content.copy(nodes = removedNodes, edges = removedEdges))
        val removedDiff = InfrastructureDiffEngine.compare(baseline, removed)
        assertEquals(setOf(escaped.id, scoped.id), removedDiff.nodeChanges.filter { it.kind == NodeChangeKind.REMOVED }.mapTo(hashSetOf(), NodeChange::nodeId))

        val systemdMissing = baseline.content.nodes.filterNot { it.type == NodeType.SYSTEMD_SERVICE.name }
        val systemdIds = systemdMissing.mapTo(hashSetOf(), SnapshotNode::id)
        val partialContent = baseline.content.copy(
            nodes = systemdMissing,
            edges = baseline.content.edges.filter { it.source in systemdIds && it.target in systemdIds },
            discoveryQuality = baseline.content.discoveryQuality.map { if (it.subsystem == "SYSTEMD") it.copy(state = "PARTIAL") else it },
        )
        val partial = withContent(baseline.copy(snapshotId = SNAPSHOT_2, capturedAt = Instant.parse("2026-08-30T11:00:00Z")), partialContent)
        assertTrue(InfrastructureDiffEngine.compare(baseline, partial).nodeChanges.filter { it.nodeType == NodeType.SYSTEMD_SERVICE.name }.all { it.evidence == ChangeEvidence.UNCONFIRMED_REMOVAL })

        val resolvedEdge = baseline.content.edges.first { it.relation == RelationType.RESOLVES_TO.name }
        val alternateContainer = baseline.content.nodes.first { it.type == NodeType.DOCKER_CONTAINER.name && it.id != resolvedEdge.target }
        val reroutedEdge = resolvedEdge.copy(id = "${resolvedEdge.id}:changed", target = alternateContainer.id)
        val reroutedContent = baseline.content.copy(edges = baseline.content.edges.filterNot { it.id == resolvedEdge.id } + reroutedEdge)
        val rerouted = withContent(baseline.copy(snapshotId = SNAPSHOT_2, capturedAt = Instant.parse("2026-08-30T11:00:00Z")), reroutedContent)
        val reroutedDiff = InfrastructureDiffEngine.compare(baseline, rerouted)
        assertEquals(1, reroutedDiff.relationshipChanges.count { it.kind == RelationshipChangeKind.RELATIONSHIP_REMOVED && it.edge.id == resolvedEdge.id })
        assertEquals(1, reroutedDiff.relationshipChanges.count { it.kind == RelationshipChangeKind.RELATIONSHIP_ADDED && it.edge.id == reroutedEdge.id })

        val ownerEdge = baseline.content.edges.first { it.relation == RelationType.OWNED_BY.name }
        val alternateService = baseline.content.nodes.first { it.type == NodeType.SYSTEMD_SERVICE.name && it.id != ownerEdge.target }
        val reassignedOwner = ownerEdge.copy(id = "${ownerEdge.id}:changed", target = alternateService.id)
        val ownerContent = baseline.content.copy(edges = baseline.content.edges.filterNot { it.id == ownerEdge.id } + reassignedOwner)
        val ownerChanged = withContent(baseline.copy(snapshotId = SNAPSHOT_2, capturedAt = Instant.parse("2026-08-30T11:00:00Z")), ownerContent)
        assertEquals(setOf(RelationshipChangeKind.RELATIONSHIP_ADDED, RelationshipChangeKind.RELATIONSHIP_REMOVED), InfrastructureDiffEngine.compare(baseline, ownerChanged).relationshipChanges.filter { it.edge.id in setOf(ownerEdge.id, reassignedOwner.id) }.mapTo(hashSetOf(), RelationshipChange::kind))

        val listener = baseline.content.nodes.first { it.type == NodeType.HOST_LISTENER.name }
        val withoutListenerNodes = baseline.content.nodes.filterNot { it.id == listener.id }
        val withoutListenerIds = withoutListenerNodes.mapTo(hashSetOf(), SnapshotNode::id)
        val listenerFailedContent = baseline.content.copy(
            nodes = withoutListenerNodes,
            edges = baseline.content.edges.filter { it.source in withoutListenerIds && it.target in withoutListenerIds },
            discoveryQuality = baseline.content.discoveryQuality.map { if (it.subsystem == "HOST_LISTENERS") it.copy(state = "COMMAND_FAILED") else it },
        )
        val listenerFailed = withContent(baseline.copy(snapshotId = SNAPSHOT_2, capturedAt = Instant.parse("2026-08-30T11:00:00Z")), listenerFailedContent)
        assertEquals(ChangeEvidence.UNCONFIRMED_REMOVAL, InfrastructureDiffEngine.compare(baseline, listenerFailed).nodeChanges.single { it.nodeId == listener.id }.evidence)

        val container = baseline.content.nodes.first { it.type == NodeType.DOCKER_CONTAINER.name && it.label == "portfolio-web" }
        val withoutContainerNodes = baseline.content.nodes.filterNot { it.id == container.id }
        val withoutContainerIds = withoutContainerNodes.mapTo(hashSetOf(), SnapshotNode::id)
        val dockerFailedContent = baseline.content.copy(
            nodes = withoutContainerNodes,
            edges = baseline.content.edges.filter { it.source in withoutContainerIds && it.target in withoutContainerIds },
            discoveryQuality = baseline.content.discoveryQuality.map { if (it.subsystem == "DOCKER") it.copy(state = "SCAN_FAILED") else it },
        )
        val dockerFailed = withContent(baseline.copy(snapshotId = SNAPSHOT_2, capturedAt = Instant.parse("2026-08-30T11:00:00Z")), dockerFailedContent)
        assertEquals(ChangeEvidence.UNCONFIRMED_REMOVAL, InfrastructureDiffEngine.compare(baseline, dockerFailed).nodeChanges.single { it.nodeId == container.id }.evidence)
    }

    @Test fun `all current node types have explicit conservative provenance`() {
        NodeType.entries.forEachIndexed { index, type ->
            val resource = node("typed:$index", type, type.name)
            val previous = snapshot(SNAPSHOT_1, "2026-08-30T10:00:00Z", nodes = listOf(server, resource).distinctBy(SnapshotNode::id))
            val current = snapshot(
                SNAPSHOT_2,
                "2026-08-30T11:00:00Z",
                quality = mapOf(DiffSubsystem.DOCKER to "SCAN_FAILED", DiffSubsystem.CADDY to "PARTIAL", DiffSubsystem.SYSTEMD to "COMMAND_FAILED", DiffSubsystem.HOST_LISTENERS to "PARTIAL"),
            )
            val change = InfrastructureDiffEngine.compare(previous, current).nodeChanges.singleOrNull { it.nodeId == resource.id }
            if (type == NodeType.SERVER || type == NodeType.DIRECTORY) assertEquals(ChangeEvidence.CONFIRMED, change?.evidence) else assertEquals(ChangeEvidence.UNCONFIRMED_REMOVAL, change?.evidence)
        }
    }

    private fun realisticSnapshot(snapshotId: String, capturedAt: String): InfrastructureSnapshot {
        val docker = DockerHelperParser.parse(checkNotNull(javaClass.getResource("/docker/helper-caddy-realistic.json")).readText())
        val hostResources = DockerHelperParser.parse(checkNotNull(javaClass.getResource("/docker/helper-systemd-realistic.json")).readText())
        val graph = HostDockerGraphFactory.create(
            HostDiscoveryResult(HostInfo("vps-prod", "Debian GNU/Linux", "13", "Linux", "6.12", "x86_64"), docker.copy(systemd = hostResources.systemd, listeners = hostResources.listeners)),
            SshTarget("vps.example", 22, "vpsgraph", "/key/not-persisted"),
        )
        return SnapshotProjection.capture(graph, SERVER_ID, snapshotId, Instant.parse(capturedAt))
    }

    private fun snapshot(
        snapshotId: String,
        capturedAt: String,
        serverId: String = SERVER_ID,
        nodes: List<SnapshotNode> = listOf(server),
        edges: List<SnapshotEdge> = emptyList(),
        quality: Map<DiffSubsystem, String> = completeQuality(),
    ): InfrastructureSnapshot {
        val content = InfrastructureSnapshotContent(
            nodes,
            edges,
            quality.entries.map { SnapshotDiscoveryQuality(subsystemName(it.key), it.value) },
        )
        return InfrastructureSnapshot(
            snapshotId = snapshotId,
            serverId = serverId,
            capturedAt = Instant.parse(capturedAt),
            graphFingerprint = SnapshotProjection.fingerprint(content),
            content = content,
        )
    }

    private fun withContent(snapshot: InfrastructureSnapshot, content: InfrastructureSnapshotContent) =
        snapshot.copy(content = content, graphFingerprint = SnapshotProjection.fingerprint(content))

    private fun completeQuality() = mapOf(
        DiffSubsystem.DOCKER to "AVAILABLE",
        DiffSubsystem.CADDY to "DISCOVERED",
        DiffSubsystem.SYSTEMD to "DISCOVERED",
        DiffSubsystem.HOST_LISTENERS to "DISCOVERED",
    )

    private fun subsystemName(subsystem: DiffSubsystem) = when (subsystem) {
        DiffSubsystem.HOST_LISTENERS -> "HOST_LISTENERS"
        else -> subsystem.name
    }

    private fun node(id: String, type: NodeType, label: String, metadata: Map<String, String> = emptyMap()) = SnapshotNode(id, label, type.name, metadata)
    private fun edge(id: String, source: String, target: String, relation: RelationType) = SnapshotEdge(id, source, target, relation.name)

    private companion object {
        const val SERVER_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val OTHER_SERVER_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val SNAPSHOT_1 = "11111111-1111-4111-8111-111111111111"
        const val SNAPSHOT_2 = "22222222-2222-4222-8222-222222222222"
        val server = SnapshotNode("server:stable", "vps", NodeType.SERVER.name, mapOf("hostname" to "vps"))
    }
}
