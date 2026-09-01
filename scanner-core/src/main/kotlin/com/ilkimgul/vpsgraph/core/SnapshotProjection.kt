package com.ilkimgul.vpsgraph.core

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object SnapshotProjection {
    private val canonicalJson = Json { encodeDefaults = true }

    fun capture(
        graph: InfraGraph,
        serverId: String,
        snapshotId: String = UUID.randomUUID().toString(),
        capturedAt: Instant = Instant.now(),
    ): InfrastructureSnapshot {
        val content = content(graph)
        return InfrastructureSnapshot(
            snapshotId = snapshotId,
            serverId = serverId,
            capturedAt = capturedAt,
            graphFingerprint = fingerprint(content),
            content = content,
        )
    }

    fun content(graph: InfraGraph): InfrastructureSnapshotContent {
        require(graph.nodes.map(InfraNode::id).distinct().size == graph.nodes.size) { "Snapshot node IDs must be unique." }
        require(graph.edges.map(InfraEdge::id).distinct().size == graph.edges.size) { "Snapshot edge IDs must be unique." }
        val nodeIds = graph.nodes.mapTo(hashSetOf(), InfraNode::id)
        require(graph.edges.all { it.source in nodeIds && it.target in nodeIds }) { "Snapshot edges must reference existing nodes." }

        val nodes = graph.nodes.map { node ->
            SnapshotNode(
                id = node.id,
                label = node.label,
                type = node.type.name,
                metadata = node.metadata
                    .filterKeys { it in metadataAllowlist[node.type].orEmpty() }
                    .toSortedMap(),
            )
        }.sortedWith(compareBy(SnapshotNode::id, SnapshotNode::type, SnapshotNode::label))
        val edges = graph.edges.map { edge ->
            SnapshotEdge(edge.id, edge.source, edge.target, edge.relation.name)
        }.sortedWith(compareBy(SnapshotEdge::id, SnapshotEdge::source, SnapshotEdge::target, SnapshotEdge::relation))
        return InfrastructureSnapshotContent(nodes, edges, discoveryQuality(nodes))
    }

    fun fingerprint(content: InfrastructureSnapshotContent): String {
        val payload = SnapshotFingerprintPayload(SNAPSHOT_SCHEMA_VERSION, content)
        return MessageDigest.getInstance("SHA-256")
            .digest(canonicalJson.encodeToString(payload).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun discoveryQuality(nodes: List<SnapshotNode>): List<SnapshotDiscoveryQuality> {
        val server = nodes.firstOrNull { it.type == NodeType.SERVER.name }
        val docker = nodes.firstOrNull { it.type == NodeType.DOCKER_ENGINE.name }
        return listOf(
            quality("CADDY", docker?.metadata?.get("caddy discovery state"), CaddyDiscoveryState.entries.mapTo(hashSetOf()) { it.name }),
            quality("DOCKER", docker?.metadata?.get("docker discovery state"), DockerDiscoveryState.entries.mapTo(hashSetOf()) { it.name }),
            quality(
                "HOST_LISTENERS",
                server?.metadata?.get("listener discovery status"),
                OptionalDiscoveryState.entries.mapTo(hashSetOf()) { it.name },
                safeReasons(server?.metadata?.get("listener status reasons")),
            ),
            quality(
                "SYSTEMD",
                server?.metadata?.get("systemd discovery status"),
                OptionalDiscoveryState.entries.mapTo(hashSetOf()) { it.name },
                safeReasons(server?.metadata?.get("systemd status reasons")),
            ),
        ).sortedBy(SnapshotDiscoveryQuality::subsystem)
    }

    private fun quality(subsystem: String, state: String?, allowed: Set<String>, reasons: List<String> = emptyList()) =
        SnapshotDiscoveryQuality(subsystem, state?.takeIf { it in allowed } ?: "UNKNOWN", reasons.sorted())

    private fun safeReasons(value: String?): List<String> {
        val allowed = DiscoveryStatusReason.entries.mapTo(hashSetOf()) { it.name }
        return value.orEmpty().split(',').map(String::trim).filter { it in allowed }.distinct().sorted()
    }

    @Serializable
    private data class SnapshotFingerprintPayload(
        val schemaVersion: Int,
        val content: InfrastructureSnapshotContent,
    )

    private val metadataAllowlist: Map<NodeType, Set<String>> = mapOf(
        NodeType.SERVER to setOf(
            "hostname", "ssh host", "ssh username", "operating system", "os version", "kernel", "architecture",
            "systemd discovery status", "systemd status reasons", "listener discovery status", "listener status reasons",
        ),
        NodeType.DOCKER_ENGINE to setOf(
            "discovery status", "docker discovery state", "docker version", "caddy discovery status", "caddy discovery state",
        ),
        NodeType.DOCKER_COMPOSE_PROJECT to setOf("project", "working directory", "config files", "container count"),
        NodeType.DOCKER_CONTAINER to setOf(
            "container id", "image", "state", "restart policy", "compose project", "compose service", "ports", "networks", "mounts",
        ),
        NodeType.NETWORK to setOf("network", "connected containers"),
        NodeType.MOUNT to setOf("type", "source", "volume name", "destination", "access"),
        NodeType.PORT to setOf("container port", "protocol", "host binding", "exposure"),
        NodeType.REVERSE_PROXY to setOf("proxy", "container name", "discovery status", "route count", "domain count", "image"),
        NodeType.DOMAIN to setOf(
            "domain", "route count", "paths", "resolution", "upstreams", "caddy instance", "resolved containers", "resolved host services",
        ),
        NodeType.UPSTREAM to setOf(
            "dial", "resolution", "host resolution", "target kind", "matching port", "host resolution reason", "host service",
            "eligible listeners", "candidate services", "route id", "backend set size", "domains", "paths", "matching reason",
            "shared network", "container", "compose project", "compose service", "container state",
        ),
        NodeType.SYSTEMD_SERVICE to setOf(
            "unit", "description", "load state", "active state", "sub state", "unit file state", "service type", "user", "group",
            "control group", "listener count", "discovery status",
        ),
        NodeType.HOST_LISTENER to setOf(
            "protocol", "local address", "port", "bind", "wildcard", "loopback", "ownership state", "systemd unit", "process name", "discovery status",
        ),
        NodeType.DIRECTORY to setOf("path", "owner", "group", "permissions"),
    )
}
