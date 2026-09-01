package com.ilkimgul.vpsgraph.core

import kotlinx.serialization.Serializable

@Serializable
data class InfraGraph(
    val nodes: List<InfraNode>,
    val edges: List<InfraEdge>,
)

@Serializable
data class InfraNode(
    val id: String,
    val label: String,
    val type: NodeType,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class InfraEdge(
    val id: String,
    val source: String,
    val target: String,
    val relation: RelationType,
)

@Serializable
enum class NodeType {
    SERVER,
    REVERSE_PROXY,
    DOMAIN,
    UPSTREAM,
    DOCKER_ENGINE,
    DOCKER_COMPOSE_PROJECT,
    DOCKER_CONTAINER,
    NETWORK,
    MOUNT,
    DIRECTORY,
    PORT,
    SYSTEMD_SERVICE,
    HOST_LISTENER,
}

@Serializable
enum class RelationType {
    CONTAINS,
    ROUTES_TO,
    ROUTES_THROUGH,
    PROXIES_TO,
    RESOLVES_TO,
    RUNS,
    EXPOSES,
    CONNECTED_TO,
    MOUNTS,
    LOCATED_IN,
    LISTENS_ON,
    OWNED_BY,
    TARGETS,
    PUBLISHED_AS,
}
